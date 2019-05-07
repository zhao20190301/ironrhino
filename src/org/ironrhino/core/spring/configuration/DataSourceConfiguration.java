package org.ironrhino.core.spring.configuration;

import java.util.Collections;

import javax.sql.DataSource;

import org.apache.commons.lang3.StringUtils;
import org.flywaydb.core.Flyway;
import org.ironrhino.core.jdbc.DatabaseProduct;
import org.ironrhino.core.tracing.Tracing;
import org.ironrhino.core.util.AppInfo;
import org.ironrhino.core.util.AppInfo.Stage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Role;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.util.ClassUtils;

import com.zaxxer.hikari.HikariDataSource;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Order(0)
@Configuration
@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
@ResourcePresentConditional("resources/spring/applicationContext-hibernate.xml")
@Slf4j
public class DataSourceConfiguration {

	@Autowired
	private Environment env;

	@Value("${jdbc.driverClass:}")
	private String driverClass;

	@Value("${jdbc.driverClassName:}")
	private String driverClassName;

	@Value("${jdbc.url:jdbc:mysql://localhost/#{systemProperties['app.name'].replaceAll('-','_').replaceAll('\\.','_')}}")
	private String jdbcUrl;

	@Value("${jdbc.username:root}")
	private String username;

	@Value("${jdbc.password:}")
	private String password;

	@Value("${dataSource.maximumPoolSize:500}")
	private int maximumPoolSize;

	@Value("${dataSource.minimumIdle:5}")
	private int minimumIdle;

	@Value("${dataSource.connectionTimeout:10000}")
	private long connectionTimeout;

	@Value("${dataSource.idleTimeout:1800000}")
	private long idleTimeout;

	@Value("${dataSource.maxLifetime:7200000}")
	private long maxLifetime;

	@Value("${dataSource.autoCommit:true}")
	private boolean autoCommit;

	@Value("${dataSource.registerMbeans:false}")
	private boolean registerMbeans;

	@Value("${dataSource.connectionTestQuery:}")
	private String connectionTestQuery;

	@Value("${dataSource.lazyConnect:false}")
	private boolean lazyConnect;

	@Value("${dataSource.enableMigrations:false}")
	@Getter
	private boolean enableMigrations;

	protected DataSource createDataSource() {
		if (AppInfo.getStage() == Stage.DEVELOPMENT && StringUtils.isBlank(env.getProperty("jdbc.url"))) {
			boolean available = AddressAvailabilityCondition.check(jdbcUrl, 5000);
			if (!available && ClassUtils.isPresent("org.h2.Driver", getClass().getClassLoader())) {
				String newJdbcUrl = "jdbc:h2:" + AppInfo.getAppHome() + "/db/h2";
				log.warn("Default jdbcUrl {} is not available, switch to {}", jdbcUrl, newJdbcUrl);
				jdbcUrl = newJdbcUrl;
			}
		}
		DatabaseProduct databaseProduct = DatabaseProduct.parse(jdbcUrl);
		HikariDataSource ds = new HikariDataSource();
		if (StringUtils.isNotBlank(driverClass))
			driverClassName = driverClass;
		if (StringUtils.isNotBlank(driverClassName))
			ds.setDriverClassName(driverClassName);
		else if (databaseProduct != null)
			ds.setDriverClassName(databaseProduct.getDefaultDriverClass());
		ds.setJdbcUrl(databaseProduct != null ? databaseProduct.polishJdbcUrl(jdbcUrl) : jdbcUrl);
		ds.setUsername(username);
		ds.setPassword(password);
		ds.setMaximumPoolSize(maximumPoolSize);
		ds.setMinimumIdle(minimumIdle);
		ds.setConnectionTimeout(connectionTimeout);
		ds.setIdleTimeout(idleTimeout);
		ds.setMaxLifetime(maxLifetime);
		ds.setAutoCommit(autoCommit);
		ds.setRegisterMbeans(registerMbeans);
		if (StringUtils.isNotBlank(connectionTestQuery))
			ds.setConnectionTestQuery(connectionTestQuery);
		ds.setPoolName("HikariPool-" + AppInfo.getAppName());
		log.info("Using {} to connect {}", ds.getClass().getName(), ds.getJdbcUrl());

		if (enableMigrations) {
			Flyway.configure().baselineOnMigrate(true).dataSource(ds).load().migrate();
		}

		if (Tracing.isEnabled()) {
			ds.setDriverClassName("io.opentracing.contrib.jdbc.TracingDriver");
			String url = ds.getJdbcUrl();
			if (url.startsWith("jdbc:"))
				url = url.substring(5);
			if (databaseProduct != null)
				url = databaseProduct.appendJdbcUrlProperties(url,
						Collections.singletonMap("traceWithActiveSpanOnly", "true"));
			ds.setJdbcUrl("jdbc:tracing:" + url);
		}
		return ds;
	}

	@Bean(autowireCandidate = false)
	@ApplicationContextPropertiesConditional(key = "dataSource.lazyConnect", value = "true")
	protected DataSource targetDataSource() {
		return createDataSource();
	}

	@Bean
	@Primary
	public DataSource dataSource() {
		return lazyConnect ? new LazyConnectionDataSourceProxy(targetDataSource()) : createDataSource();
	}

	@Bean
	@Primary
	public JdbcTemplate jdbcTemplate(DataSource dataSource) {
		return new JdbcTemplate(dataSource);
	}

	@Bean
	@Primary
	public NamedParameterJdbcTemplate namedParameterJdbcTemplate(DataSource dataSource) {
		return new NamedParameterJdbcTemplate(dataSource);
	}

}
