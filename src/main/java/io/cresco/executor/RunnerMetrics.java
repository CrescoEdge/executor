package io.cresco.executor;

import com.google.gson.Gson;
import io.cresco.library.data.TopicType;
import io.cresco.library.plugin.PluginBuilder;
import io.cresco.library.utilities.CLogger;
import oshi.SystemInfo;
import oshi.software.os.OSProcess;
import oshi.software.os.OperatingSystem;

import jakarta.jms.MapMessage;
import java.util.*;

public class RunnerMetrics extends Thread {
    private final PluginBuilder plugin;
    private final CLogger logger;
    private final long rootPid;
    private final String streamName;
    private final Gson gson;

    public RunnerMetrics(PluginBuilder plugin, long rootPid, String streamName) {
        this.plugin = plugin;
        logger = plugin.getLogger(RunnerMetrics.class.getName(), CLogger.Level.Info);
        this.rootPid = rootPid;
        this.streamName = streamName;
        this.gson = new Gson();
    }

    @Override
    public void run() {
        try {
            SystemInfo systemInfo = new SystemInfo();
            OperatingSystem os = systemInfo.getOperatingSystem();

            // Sample the exact process we launched (by PID) plus its descendants until it exits.
            // The old code matched processes by command-line string and re-listed every process on
            // the host each cycle; here we resolve the tree from the JVM ProcessHandle (targeted).
            while (plugin.isActive()) {
                OSProcess root = os.getProcess((int) rootPid);
                if (root == null || root.getState() == OSProcess.State.INVALID) {
                    break; // process exited
                }

                List<Long> pids = new ArrayList<>();
                pids.add(rootPid);
                ProcessHandle.of(rootPid).ifPresent(h -> h.descendants().forEach(d -> pids.add(d.pid())));

                long processCount = 0, bytesRead = 0, bytesWritten = 0, kernelTime = 0,
                     threadCount = 0, setSize = 0, virtualSize = 0;
                for (long pid : pids) {
                    OSProcess op = os.getProcess((int) pid);
                    if (op != null && op.getState() != OSProcess.State.INVALID) {
                        processCount++;
                        bytesRead += op.getBytesRead();
                        bytesWritten += op.getBytesWritten();
                        kernelTime += op.getKernelTime();
                        threadCount += op.getThreadCount();
                        setSize += op.getResidentMemory();
                        virtualSize += op.getVirtualSize();
                    }
                }

                if (processCount > 0) {
                    List<Map<String, String>> metricList = new ArrayList<>();
                    metricList.add(getMetric("process.count", String.valueOf(processCount)));
                    metricList.add(getMetric("bytes.read", String.valueOf(bytesRead)));
                    metricList.add(getMetric("bytes.written", String.valueOf(bytesWritten)));
                    metricList.add(getMetric("kernel.time", String.valueOf(kernelTime)));
                    metricList.add(getMetric("thread.count", String.valueOf(threadCount)));
                    metricList.add(getMetric("set.size", String.valueOf(setSize)));
                    metricList.add(getMetric("virtual.size", String.valueOf(virtualSize)));

                    Map<String, List<Map<String, String>>> info = new HashMap<>();
                    info.put("runner-" + streamName, metricList);

                    Map<String, String> metricsMap = new HashMap<>();
                    metricsMap.put("name", "executor");
                    metricsMap.put("metrics", gson.toJson(info));

                    List<Map<String, String>> metricsList = new ArrayList<>();
                    metricsList.add(metricsMap);

                    MapMessage mapMessage = plugin.getAgentService().getDataPlaneService().createMapMessage();
                    mapMessage.setString("perf", gson.toJson(metricsList));
                    mapMessage.setStringProperty("pluginname", plugin.getConfig().getStringParam("pluginname"));
                    mapMessage.setStringProperty("region_id", plugin.getRegion());
                    mapMessage.setStringProperty("agent_id", plugin.getAgent());
                    mapMessage.setStringProperty("plugin_id", plugin.getPluginID());
                    plugin.getAgentService().getDataPlaneService().sendMessage(TopicType.AGENT, mapMessage);
                }

                Thread.sleep(5000);
            }

        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.error("RunnerMetrics.run() error for " + streamName + ": " + e.getMessage());
        }
    }

    Map<String, String> getMetric(String name, String value) {
        Map<String, String> info = new HashMap<>();
        info.put("name", name);
        info.put("value", value);
        info.put("type", "APP");
        info.put("class", "GAUGE");
        return info;
    }
}
