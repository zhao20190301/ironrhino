package org.ironrhino.core.remoting.stats;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.ironrhino.core.model.Tuple;

public interface ServiceStats {

	public void serverSideEmit(String serviceName, String method, long time);

	public void clientSideEmit(String target, String serviceName, String method, long time, boolean failed);

	public Map<String, Set<String>> getServices();

	public Tuple<String, Long> getMaxCount(String service, StatsType type);

	public long getCount(String service, String key, StatsType type);

	public Map<String, Long> findHotspots(int limit);

	public List<InvocationWarning> getWarnings();

	public List<InvocationSample> getSamples(String service, StatsType type);

}