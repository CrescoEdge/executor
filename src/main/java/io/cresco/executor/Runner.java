package io.cresco.executor;

import io.cresco.library.data.TopicType;
import io.cresco.library.plugin.PluginBuilder;
import io.cresco.library.utilities.CLogger;

import javax.jms.MapMessage;
import javax.jms.Message;
import javax.jms.TextMessage;
import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

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

            boolean isInteractive = false;
            if(command.toLowerCase().contains("-interactive-")) {
                isInteractive = true;
            }

            ProcessBuilder pb = null;

            logger.error(System.getProperty("os.name"));
            if(isInteractive) {

                pb =  new ProcessBuilder();

                if (System.getProperty("os.name").startsWith("Linux")) {
                    pb = new ProcessBuilder("/bin/sh", "-i");
                } else if (System.getProperty("os.name").startsWith("Mac OS X")) {
                    pb = new ProcessBuilder("/bin/sh", "-i");
                } else {
                    pb = new ProcessBuilder("CMD");
                }

            } else {
                if (System.getProperty("os.name").startsWith("Linux")) {
                    pb = new ProcessBuilder("/bin/sh", "-c", command);
                } else if (System.getProperty("os.name").startsWith("Mac OS X")) {
                    pb = new ProcessBuilder("/bin/sh", "-c", command);
                }  else {
                    pb = new ProcessBuilder("CMD", "/C", command);
                }
            }

            logger.trace("Starting Process");
            Process p = pb.start();

            if(metrics) {
                logger.trace("Starting Metric Collection");
                RunnerMetrics runnerMetrics = new RunnerMetrics(plugin, command, streamName);
                new Thread(runnerMetrics).start();
                //runnerMetrics.start();
            }

            logger.info("Starting Input Listener");
            createListener(p.getOutputStream(), streamName, "input");

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

    private void writeString(OutputStream os, String request,
                                         String charsetName) throws IOException {
        OutputStreamWriter writer = new OutputStreamWriter(os, charsetName);
        BufferedWriter bw = new BufferedWriter(writer);
        bw.write(request);
        bw.write("\r\n");
        bw.flush();
    }

    private boolean createListener(OutputStream os, String streamName, String streamType) {
        boolean isCreated = false;
        try{
            String stream_query = "stream_name='" + streamName + "' and type='" + streamType + "'";

            javax.jms.MessageListener ml = new javax.jms.MessageListener() {
                public void onMessage(Message msg) {
                    try {
                        logger.trace("INCOMING: " + msg.toString());

                        if (msg instanceof TextMessage) {
                            String message = ((TextMessage) msg).getText();
                            writeString(os, message, "UTF-8");
                        }
                    } catch(Exception ex) {

                        ex.printStackTrace();
                    }
                }
            };
            //logger.error("APIDataPlane: creating listener: " + "stream_query=" + stream_query + "");
            String listenerid = plugin.getAgentService().getDataPlaneService().addMessageListener(TopicType.AGENT,ml,stream_query);

            isCreated = true;

        } catch (Exception ex) {
            ex.printStackTrace();
        }

        return isCreated;
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
                logger.info("Running command on system type: " + System.getProperty("os.name"));
                if (System.getProperty("os.name").startsWith("Linux")) {
                    pb = new ProcessBuilder("sudo", "bash", "-c",
                            "kill -2 $(ps aux | grep '[" + command.charAt(0) + "]" + command.substring(1) + "' | awk '{print $2}')");
                } else if (System.getProperty("os.name").startsWith("MacOS")) {
                    pb = new ProcessBuilder("sudo", "bash", "-c",
                            "kill -2 $(ps aux | grep '[" + command.charAt(0) + "]" + command.substring(1) + "' | awk '{print $2}')");
                } else if (System.getProperty("os.name").startsWith("Windows")) {
                    pb = new ProcessBuilder("CMD", "/C", "taskkill","/IM", command, "/F");
                }
                else {
                    pb = new ProcessBuilder("sudo", "bash", "-c",
                            "kill -2 $(ps aux | grep '[" + command.charAt(0) + "]" + command.substring(1) + "' | awk '{print $2}')");
                    logger.error("Unknown OS");
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