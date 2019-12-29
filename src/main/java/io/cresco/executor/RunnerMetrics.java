package io.cresco.executor;

import com.google.gson.Gson;
import io.cresco.library.data.TopicType;
import io.cresco.library.plugin.PluginBuilder;
import io.cresco.library.utilities.CLogger;
import oshi.SystemInfo;
import oshi.software.os.OSProcess;
import oshi.software.os.OperatingSystem;

import javax.jms.MapMessage;
import java.util.*;

public class RunnerMetrics extends Thread {
    private PluginBuilder plugin;
    private CLogger logger;
    private String command;
    private Gson gson;
    private String streamName;


    public RunnerMetrics(PluginBuilder plugin, String command, String streamName) {
        this.plugin = plugin;
        logger = plugin.getLogger(RunnerMetrics.class.getName(),CLogger.Level.Info);

        this.streamName = streamName;
        this.command = command;
        this.gson = new Gson();
    }



    @Override
    public void run() {
        try {

            List<Integer> processList = new ArrayList<>();
            int rootProcessId = -1;

            SystemInfo systemInfo = new SystemInfo();
            OperatingSystem os = systemInfo.getOperatingSystem();

            OSProcess[] p = os.getProcesses(0, OperatingSystem.ProcessSort.CPU);
            for(OSProcess op : p) {

                //System.out.println(op.getCommandLine());
                String commandLine = op.getCommandLine().replace('\0',' ');

                if(commandLine.startsWith("/bin/sh -c ")) {
                    commandLine = commandLine.replace("/bin/sh -c ","");
                }

                if(commandLine.startsWith("/bin/sh ")) {
                    commandLine = commandLine.replace("/bin/sh ","");
                }

                commandLine = commandLine.trim();

                if(commandLine.equals(command)) {
                    rootProcessId = op.getProcessID();
                    processList.add(rootProcessId);
                }

            }

            if(rootProcessId != -1) {
                OSProcess rootOp = os.getProcess(rootProcessId);
                while (!os.getProcess(rootProcessId).getState().equals(State.TERMINATED)) {

                    long processCount = 0;
                    long bytesRead = 0;
                    long bytesWritten = 0;
                    long kernelTime = 0;
                    long threadCount = 0;
                    long setSize = 0;
                    long virtualSize = 0;

                    OSProcess[] pp = os.getProcesses(0, OperatingSystem.ProcessSort.CPU);
                    for(OSProcess op : pp) {


                        if(processList.contains(op.getParentProcessID())) {
                            processList.add(op.getProcessID());
                        }

                        if((processList.contains(op.getProcessID())) && (!op.getState().equals(State.TERMINATED))) {

                            processCount++;
                            bytesRead += op.getBytesRead();
                            bytesWritten += op.getBytesWritten();
                            kernelTime += op.getKernelTime();
                            threadCount += op.getThreadCount();
                            setSize += op.getResidentSetSize();
                            virtualSize += op.getVirtualSize();
                        }
                    }

                    if(processCount > 0) {

                        List<Map<String,String>> metricList = new ArrayList<>();
                        metricList.add(getMetric("process.count",String.valueOf(processCount)));
                        metricList.add(getMetric("bytes.read",String.valueOf(bytesRead)));
                        metricList.add(getMetric("bytes.written",String.valueOf(bytesWritten)));
                        metricList.add(getMetric("kernel.time",String.valueOf(kernelTime)));
                        metricList.add(getMetric("thread.count",String.valueOf(threadCount)));
                        metricList.add(getMetric("set.size",String.valueOf(setSize)));
                        metricList.add(getMetric("virtual.size",String.valueOf(virtualSize)));

                        Map<String,List<Map<String,String>>> info = new HashMap<>();
                        info.put("runner-" + streamName,metricList);

                        Map<String,String> metricsMap = new HashMap<>();
                        metricsMap.put("name","executor");
                        metricsMap.put("metrics",gson.toJson(info));

                        List<Map<String,String>> metricsList = new ArrayList<>();
                        metricsList.add(metricsMap);


                        MapMessage mapMessage = plugin.getAgentService().getDataPlaneService().createMapMessage();
                        mapMessage.setString("perf",gson.toJson(metricsList));

                        //set property
                        mapMessage.setStringProperty("pluginname",plugin.getConfig().getStringParam("pluginname"));
                        mapMessage.setStringProperty("region_id",plugin.getRegion());
                        mapMessage.setStringProperty("agent_id",plugin.getAgent());
                        mapMessage.setStringProperty("plugin_id", plugin.getPluginID());

                        plugin.getAgentService().getDataPlaneService().sendMessage(TopicType.AGENT,mapMessage);

                        /*
                        if((plugin.getConfig().getStringParam("resource_id") != null) && (plugin.getConfig().getStringParam("inode_id") != null)) {
                            MsgEvent tick = new MsgEvent(MsgEvent.Type.KPI, plugin.getRegion(), plugin.getAgent(), plugin.getPluginID(), "Performance Monitoring tick.");
                            tick.setParam("src_region", plugin.getRegion());
                            tick.setParam("src_agent", plugin.getAgent());
                            tick.setParam("src_plugin", plugin.getPluginID());
                            tick.setParam("dst_region", plugin.getRegion());
                            tick.setParam("dst_agent", plugin.getAgent());
                            tick.setParam("dst_plugin", "plugin/0");
                            tick.setParam("is_regional", Boolean.TRUE.toString());
                            tick.setParam("is_global", Boolean.TRUE.toString());

                            tick.setParam("resource_id", plugin.getConfig().getStringParam("resource_id"));
                            tick.setParam("inode_id", plugin.getConfig().getStringParam("inode_id"));

                            tick.setCompressedParam("perf", gson.toJson(info));
                            plugin.sendMsgEvent(tick);
                        }
                        */

                    }

                    Thread.sleep(5000);
                }
            }

        } catch (Exception e) {
            logger.error("run() : Interrupted : {}", e.getMessage());
        }
    }

    Map<String,String> getMetric(String name, String value) {
        Map<String, String> info = new HashMap<>();
        info.put("name",name);
        info.put("value",value);
        info.put("type","APP");
        info.put("class", "GAUGE");
        return info;
    }

}