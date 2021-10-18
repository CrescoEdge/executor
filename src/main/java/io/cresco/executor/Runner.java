package io.cresco.executor;

import io.cresco.library.data.TopicType;
import io.cresco.library.plugin.PluginBuilder;
import io.cresco.library.utilities.CLogger;

import javax.jms.MapMessage;
import java.io.IOException;
import java.util.*;

public class Runner implements Runnable {

    private PluginBuilder plugin;
    private CLogger logger;
    private String command;
    private String streamName;
    private boolean running = false;
    private boolean complete = false;
    private boolean metrics;

    public Runner(PluginBuilder plugin, String command, String streamName, boolean metrics) {

        this.plugin = plugin;
        logger = plugin.getLogger(ExecutorImpl.class.getName(),CLogger.Level.Info);

        this.command = command;
        this.streamName = streamName;
        this.metrics = metrics;
    }

    @Override
    public void run() {
        try {
            logger.info("Running Runner");
            logger.debug("Command: [" + command + "]");
            boolean canRun = false;
            logger.trace("Checking to see if eligable for running");
            /*
            for (String executable : executables)
                if (command.startsWith(executable))
                    canRun = true;
            */
            canRun = true;
            logger.debug("canRun = {}", canRun);
            if (!canRun) return;
            running = true;
            logger.trace("Setting up ProcessBuilder");
            ProcessBuilder pb = null;
            if (System.getProperty("os.name").startsWith("Linux")) {
                pb = new ProcessBuilder("/bin/sh", "-c", command);
            } else {
                pb = new ProcessBuilder("CMD", "/C", command);
            }

            logger.trace("Starting Process");
            Process p = pb.start();

            if(metrics) {
                logger.trace("Starting Metric Collection");
                RunnerMetrics runnerMetrics = new RunnerMetrics(plugin, command, streamName);
                new Thread(runnerMetrics).start();
                //runnerMetrics.start();
            }

            logger.trace("Starting Output Forwarders");
            StreamGobbler errorGobbler = new StreamGobbler(plugin, p.getErrorStream(), streamName, "error");
            StreamGobbler outputGobbler = new StreamGobbler(plugin, p.getInputStream(), streamName, "output");

            errorGobbler.start();
            outputGobbler.start();


            logger.trace("Waiting for process completion");
            int exitValue = p.waitFor();
            logger.trace("Process has completed");
            complete = true;
            running = false;

            MapMessage mapMessage = plugin.getAgentService().getDataPlaneService().createMapMessage();

            mapMessage.setString("cmd", "execution_log");
            mapMessage.setString("ts", Long.toString(new Date().getTime()));
            mapMessage.setString("log", "[" + new Date() + "] Exit Code: " + Integer.toString(exitValue));

            //set property
            mapMessage.setStringProperty("pluginname",plugin.getConfig().getStringParam("pluginname"));
            mapMessage.setStringProperty("region_id",plugin.getRegion());
            mapMessage.setStringProperty("agent_id",plugin.getAgent());
            mapMessage.setStringProperty("plugin_id", plugin.getPluginID());

            mapMessage.setStringProperty("stream_name", streamName);
            mapMessage.setStringProperty("type", "event");

            plugin.getAgentService().getDataPlaneService().sendMessage(TopicType.AGENT,mapMessage);

            mapMessage.clearBody();

            mapMessage.setString("cmd", "delete_exchange");
            mapMessage.setString("ts", Long.toString(new Date().getTime()));

            plugin.getAgentService().getDataPlaneService().sendMessage(TopicType.AGENT,mapMessage);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public Boolean isRunning() {
        return this.running;
    }

    public void shutdown() {
        if (!complete) {
            logger.info("Killing process");
            try {
                /*
                    ProcessBuilder pb = new ProcessBuilder("sudo", "bash", "-c",
                            "kill -2 $(ps aux | grep '[" +
                                    exchangeID.charAt(0) + "]" + exchangeID.substring(1) + "' | awk '{print $2}')");
                  */
                ProcessBuilder pb = null;

                if (System.getProperty("os.name").startsWith("Linux")) {
                    pb = new ProcessBuilder("sudo", "bash", "-c",
                            "kill -2 $(ps aux | grep '[" + command.charAt(0) + "]" + command.substring(1) + "' | awk '{print $2}')");

                } else {
                    pb = new ProcessBuilder("CMD", "/C", "taskkill","/IM", command, "/F");
                }

                Process p = pb.start();
                    try {
                        p.waitFor();
                        running = false;
                    } catch (InterruptedException e) {
                        // Todo: Maybe this should be pushed up the stack?
                    }

            } catch (IOException e) {
                logger.error("IOException in shutdown() : " + e.getMessage());
            }
        }
    }
}