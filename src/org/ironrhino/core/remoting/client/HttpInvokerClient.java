package org.ironrhino.core.remoting.client;

import java.io.IOException;
import java.io.InvalidClassException;
import java.lang.reflect.InvocationTargetException;
import java.net.ConnectException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import javax.annotation.PostConstruct;

import org.aopalliance.intercept.MethodInvocation;
import org.apache.commons.lang3.StringUtils;
import org.ironrhino.core.remoting.Remoting;
import org.ironrhino.core.remoting.ServiceHostsChangedEvent;
import org.ironrhino.core.remoting.ServiceNotFoundException;
import org.ironrhino.core.remoting.ServiceRegistry;
import org.ironrhino.core.remoting.serializer.HttpInvokerSerializers;
import org.ironrhino.core.remoting.stats.ServiceStats;
import org.ironrhino.core.servlet.AccessFilter;
import org.ironrhino.core.spring.FallbackSupportMethodInterceptorFactoryBean;
import org.ironrhino.core.spring.RemotingClientProxy;
import org.ironrhino.core.throttle.CircuitBreaking;
import org.ironrhino.core.tracing.Tracing;
import org.ironrhino.core.util.AppInfo;
import org.ironrhino.core.util.CodecUtils;
import org.ironrhino.core.util.ExceptionUtils;
import org.ironrhino.core.util.JsonDesensitizer;
import org.ironrhino.core.util.MaxAttemptsExceededException;
import org.ironrhino.core.util.ReflectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.core.serializer.support.SerializationFailedException;
import org.springframework.remoting.RemoteAccessException;
import org.springframework.remoting.RemoteConnectFailureException;
import org.springframework.remoting.RemoteInvocationFailureException;
import org.springframework.remoting.RemoteLookupFailureException;
import org.springframework.remoting.support.RemoteInvocation;
import org.springframework.remoting.support.RemoteInvocationResult;
import org.springframework.util.Assert;

import io.github.resilience4j.circuitbreaker.CircuitBreakerOpenException;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class HttpInvokerClient extends FallbackSupportMethodInterceptorFactoryBean
		implements ApplicationListener<ServiceHostsChangedEvent> {

	private static final String SERVLET_PATH_PREFIX = "/remoting/httpinvoker/";

	private static Logger remotingLogger = LoggerFactory.getLogger("remoting");

	@Getter
	@Setter
	private Class<?> serviceInterface;

	private volatile String serviceUrl;

	@Getter
	@Setter
	@Autowired(required = false)
	private HttpInvokerRequestExecutor httpInvokerRequestExecutor = new SimpleHttpInvokerRequestExecutor();

	@Value("${httpInvoker.serializationType:}")
	private String serializationType;

	@Getter
	@Setter
	@Value("${httpInvoker.connectTimeout:5000}")
	private int connectTimeout = 5000;

	@Getter
	@Setter
	@Value("${httpInvoker.readTimeout:20000}")
	private int readTimeout = 20000;

	@Getter
	@Setter
	@Value("${httpInvoker.loggingPayload:true}")
	private boolean loggingPayload;

	@Getter
	@Setter
	@Value("${httpInvoker.circuitBreakerEnabled:true}")
	private boolean circuitBreakerEnabled;

	@Setter
	@Autowired(required = false)
	private ServiceRegistry serviceRegistry;

	@Setter
	@Autowired(required = false)
	private ServiceStats serviceStats;

	@Autowired
	private Environment env;

	@Setter
	private String host;

	@Setter
	private int port;

	@Setter
	private String contextPath;

	@Setter
	private int maxAttempts = 3;

	@Setter
	@Value("${httpInvoker.polling:true}")
	private boolean polling = true;

	private boolean urlFromDiscovery;

	private volatile String discoveredHost;

	private Object serviceProxy;

	@Override
	public Object getObject() {
		return this.serviceProxy;
	}

	@Override
	public Class<?> getObjectType() {
		return this.serviceInterface;
	}

	@PostConstruct
	public void afterPropertiesSet() {
		Assert.notNull(serviceInterface, "'serviceInterface' must not be null");
		Assert.isTrue(serviceInterface.isInterface(), "'serviceInterface' must be an interface");
		Assert.notNull(httpInvokerRequestExecutor, "'httpInvokerRequestExecutor' must not be null");
		Remoting anno = serviceInterface.getAnnotation(Remoting.class);
		if (anno != null && StringUtils.isNotBlank(anno.serializationType()))
			this.serializationType = anno.serializationType();
		this.serializationType = env.getProperty(serviceInterface.getName() + ".serializationType",
				this.serializationType);
		httpInvokerRequestExecutor.setSerializer(HttpInvokerSerializers.ofSerializationType(serializationType));
		httpInvokerRequestExecutor.setConnectTimeout(connectTimeout);
		httpInvokerRequestExecutor.setReadTimeout(readTimeout);
		if (StringUtils.isBlank(host)) {
			Assert.notNull(serviceRegistry, "serviceRegistry is missing");
			urlFromDiscovery = true;
		}
		if (port <= 0)
			port = AppInfo.getHttpPort();
		if (port <= 0)
			port = 8080;
		ProxyFactory pf = new ProxyFactory(serviceInterface, this);
		pf.addInterface(RemotingClientProxy.class);
		this.serviceProxy = pf.getProxy(serviceInterface.getClassLoader());
	}

	@Override
	protected boolean shouldFallBackFor(Throwable ex) {
		if (ex instanceof RemoteAccessException) {
			ex = ex.getCause();
			return ex instanceof CircuitBreakerOpenException || ex instanceof ServiceNotFoundException;
		}
		return false;
	}

	@Override
	protected Object doInvoke(MethodInvocation methodInvocation) throws Throwable {
		RemoteInvocation invocation = httpInvokerRequestExecutor.getSerializer()
				.createRemoteInvocation(methodInvocation);
		RemoteInvocationResult result;
		try {
			result = executeRequest(invocation, methodInvocation);
		} catch (Throwable ex) {
			RemoteAccessException rae = convertHttpInvokerAccessException(ex);
			throw (rae != null ? rae : ex);
		}
		try {
			return recreateRemoteInvocationResult(result);
		} catch (Throwable ex) {
			if (result.hasInvocationTargetException()) {
				throw ex;
			} else {
				throw new RemoteInvocationFailureException("Invocation of method [" + methodInvocation.getMethod()
						+ "] failed in HTTP invoker remote service at [" + serviceUrl + "]", ex);
			}
		}
	}

	protected RemoteInvocationResult executeRequest(RemoteInvocation invocation, MethodInvocation methodInvocation)
			throws Exception {
		return Tracing
				.execute(ReflectionUtils.stringify(methodInvocation.getMethod()),
						() -> circuitBreakerEnabled ? CircuitBreaking.execute(getServiceInterface().getName(),
								ex -> ex instanceof IOException, () -> doExecuteRequest(invocation, methodInvocation))
								: doExecuteRequest(invocation, methodInvocation),
						"span.kind", "client", "component", "remoting");
	}

	protected RemoteInvocationResult doExecuteRequest(RemoteInvocation invocation, MethodInvocation methodInvocation)
			throws Exception {
		String requestId = MDC.get(AccessFilter.MDC_KEY_REQUEST_ID);
		boolean requestIdGenerated = false;
		if (requestId == null) {
			requestId = CodecUtils.generateRequestId();
			MDC.put(AccessFilter.MDC_KEY_REQUEST_ID, requestId);
			MDC.put("request", "request:" + requestId);
			requestIdGenerated = true;
		}
		String service = ReflectionUtils.stringify(methodInvocation.getMethod());
		MDC.put("role", "CLIENT");
		MDC.put("service", service);
		if (loggingPayload) {
			Object payload;
			Object[] arguments = methodInvocation.getArguments();
			String[] parameterNames = ReflectionUtils.getParameterNames(methodInvocation.getMethod());
			if (parameterNames != null) {
				Map<String, Object> parameters = new LinkedHashMap<>();
				for (int i = 0; i < parameterNames.length; i++)
					parameters.put(parameterNames[i], arguments[i]);
				payload = parameters;
			} else {
				payload = arguments;
			}
			remotingLogger.info("Request: {}", JsonDesensitizer.DEFAULT_INSTANCE.toJson(payload));
		}
		RemoteInvocationResult result;
		try {
			result = doExecuteRequest(invocation, methodInvocation, maxAttempts);
			result = transformResult(invocation, methodInvocation, result);
			if (loggingPayload) {
				if (!result.hasInvocationTargetException()) {
					remotingLogger.info("Response: {}", JsonDesensitizer.DEFAULT_INSTANCE.toJson(result.getValue()));
				} else {
					InvocationTargetException ite = (InvocationTargetException) result.getException();
					if (ite != null)
						remotingLogger.error("Error:", ite.getTargetException());
				}
			}

		} finally {
			if (requestIdGenerated) {
				MDC.remove(AccessFilter.MDC_KEY_REQUEST_ID);
				MDC.remove("request");
			}
			MDC.remove("role");
		}
		return result;
	}

	protected RemoteInvocationResult doExecuteRequest(RemoteInvocation invocation, MethodInvocation methodInvocation,
			int maxAttempts) throws Exception {
		String method = null;
		if (serviceStats != null)
			method = ReflectionUtils.stringify(methodInvocation.getMethod(), false, true);
		int remainingAttempts = maxAttempts;
		do {
			String targetServiceUrl;
			if (polling) {
				targetServiceUrl = discoverServiceUrl(true);
			} else {
				targetServiceUrl = serviceUrl;
				if (targetServiceUrl == null) {
					targetServiceUrl = discoverServiceUrl(false);
				}
			}
			String targetDiscoveredHost = discoveredHost;
			long time = System.currentTimeMillis();
			try {
				RemoteInvocationResult result = httpInvokerRequestExecutor.executeRequest(targetServiceUrl, invocation,
						methodInvocation);
				if (urlFromDiscovery) {
					time = System.currentTimeMillis() - time;
					remotingLogger.info("Invoked to {} success in {}ms", targetDiscoveredHost, time);
					if (serviceStats != null) {
						serviceStats.clientSideEmit(targetDiscoveredHost, getServiceInterface().getName(), method, time,
								false);
					}
				}
				return result;
			} catch (Exception e) {
				remotingLogger.error("Exception:", e.getCause() != null ? e.getCause() : e);
				if (urlFromDiscovery) {
					time = System.currentTimeMillis() - time;
					remotingLogger.info("Invoked to {} fail in {}ms", targetDiscoveredHost, time);
					if (serviceStats != null) {
						serviceStats.clientSideEmit(targetDiscoveredHost, getServiceInterface().getName(), method, time,
								true);
					}
				}
				if (remainingAttempts <= 1)
					throw e;
				if ((e instanceof SerializationFailedException) && !httpInvokerRequestExecutor.getSerializer()
						.equals(HttpInvokerSerializers.DEFAULT_SERIALIZER)) {
					log.error("Downgrade service[{}] serialization from {} to {}: {}", getServiceInterface().getName(),
							httpInvokerRequestExecutor.getSerializer().getSerializationType(),
							HttpInvokerSerializers.DEFAULT_SERIALIZER.getSerializationType(), e.getMessage());
					httpInvokerRequestExecutor.setSerializer(HttpInvokerSerializers.DEFAULT_SERIALIZER);
					RemoteInvocation newInvocation = HttpInvokerSerializers.DEFAULT_SERIALIZER
							.createRemoteInvocation(methodInvocation);
					newInvocation.setAttributes(invocation.getAttributes());
					invocation = newInvocation;
				} else {
					if (urlFromDiscovery) {
						if (targetDiscoveredHost != null) {
							serviceRegistry.evict(targetDiscoveredHost);
						}
						if (!polling && targetServiceUrl.equals(serviceUrl)) {
							// avoid duplicated discoverServiceUrl, normally evict will trigger relocate
							String newServiceUrl = discoverServiceUrl(false);
							if (!newServiceUrl.equals(targetServiceUrl)) {
								targetServiceUrl = newServiceUrl;
								log.info("Relocate service url {}", targetServiceUrl);
							}
						}
					}
				}
			}
		} while (--remainingAttempts > 0);
		throw new MaxAttemptsExceededException(maxAttempts);
	}

	protected RemoteAccessException convertHttpInvokerAccessException(Throwable ex) {
		RemoteAccessException rae = null;
		if (ex instanceof ConnectException) {
			rae = new RemoteConnectFailureException(
					"Could not connect to HTTP invoker remote service at [" + serviceUrl + "]", ex);
		} else if (ex instanceof ServiceNotFoundException) {
			rae = new RemoteLookupFailureException(
					"Could not found remote service [" + getServiceInterface().getName() + "]", ex);
		} else if (ex instanceof ClassNotFoundException || ex instanceof NoClassDefFoundError
				|| ex instanceof InvalidClassException) {
			rae = new RemoteAccessException(
					"Could not deserialize result from HTTP invoker remote service [" + serviceUrl + "]", ex);
		} else if (ex instanceof Exception) {
			rae = new RemoteAccessException("Could not access HTTP invoker remote service at [" + serviceUrl + "]", ex);
		}
		if (rae != null) {
			if (rae.getCause() != null)
				ExceptionUtils.trimStackTrace(rae.getCause(), 20);
			ExceptionUtils.trimStackTrace(rae, 10);
		}
		return rae;
	}

	protected RemoteInvocationResult transformResult(RemoteInvocation invocation, MethodInvocation methodInvocation,
			RemoteInvocationResult result) {
		if (!result.hasException()) {
			Object value = result.getValue();
			if (methodInvocation.getMethod().getReturnType() == Optional.class && !(value instanceof Optional)) {
				result.setValue(Optional.ofNullable(value));
			}
		}
		return result;
	}

	protected Object recreateRemoteInvocationResult(RemoteInvocationResult result) throws Throwable {
		Throwable exception = result.getException();
		if (exception != null) {
			Throwable exToThrow = exception;
			if (exToThrow instanceof InvocationTargetException)
				exToThrow = ((InvocationTargetException) exToThrow).getTargetException();
			ExceptionUtils.fillInClientStackTraceIfPossible(exToThrow, 20);
			throw exToThrow;
		}
		return result.getValue();
	}

	private String discoverServiceUrl(boolean polling) {
		String serviceName = getServiceInterface().getName();
		StringBuilder sb = new StringBuilder();
		if (StringUtils.isBlank(host)) {
			String ho = serviceRegistry.discover(serviceName, polling);
			if (ho.indexOf("://") < 0)
				sb.append("http://");
			sb.append(ho);
			discoveredHost = ho;
		} else {
			sb.append("http://");
			sb.append(host);
			if (port != 80) {
				sb.append(':');
				sb.append(port);
			}
			if (StringUtils.isNotBlank(contextPath))
				sb.append(contextPath);
		}
		sb.append(SERVLET_PATH_PREFIX);
		sb.append(serviceName);
		return serviceUrl = sb.toString();
	}

	@Override
	public void onApplicationEvent(ServiceHostsChangedEvent event) {
		if (!polling && event.getServiceName().equals(getServiceInterface().getName())) {
			// force discover service for balance
			if (serviceUrl != null) {
				try {
					String old = serviceUrl;
					String newServiceUrl = discoverServiceUrl(false);
					if (!newServiceUrl.equals(old)) {
						log.info("Relocate service url {} for balancing", newServiceUrl);
					}
				} catch (ServiceNotFoundException ignored) {

				}
			}
		}
	}

}
