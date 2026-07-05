package io.cresco.executor;

import io.cresco.library.data.TopicType;
import io.cresco.library.plugin.PluginBuilder;
import io.cresco.library.utilities.CLogger;

import jakarta.jms.MapMessage;
import jakarta.jms.Message;
import jakarta.jms.TextMessage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class Runner implements Runnable {

    private final PluginBuilder plugin;
    private final CLogger logger;
    private final String command;
    private final String streamName;
    private final boolean metrics;

    // read/written across threads (run thread, control thread) -> must be visible
    private volatile boolean running = false;
    private volatile boolean complete = false;
    private volatile Process process;
    private volatile String listenerid;

    public Runner(PluginBuilder plugin, String command, String streamName, boolean metrics) {
        this.plugin = plugin;
        logger = plugin.getLogger(Runner.class.getName(), CLogger.Level.Info);
        this.command = command;
        this.streamName = streamName;
        this.metrics = metrics;
    }

    @Override
    public void run() {
        try {
            logger.debug("Running Runner command: [" + command + "]");
            running = true;

            boolean isInteractive = command.toLowerCase().contains("-interactive-");
            String osName = System.getProperty("os.name");
            boolean windows = osName.startsWith("Windows");

            ProcessBuilder pb;
            if (isInteractive) {
                pb = windows ? new ProcessBuilder("CMD") : new ProcessBuilder("/bin/sh", "-i");
            } else {
                pb = windows ? new ProcessBuilder("CMD", "/C", command)
                             : new ProcessBuilder("/bin/sh", "-c", command);
            }

            logger.trace("Starting process");
            process = pb.start();

            if (metrics) {
                // Monitor the exact PID we launched (+ its descendants) instead of matching cmdlines.
                RunnerMetrics runnerMetrics = new RunnerMetrics(plugin, process.pid(), streamName);
                Thread mt = new Thread(runnerMetrics, "executor-metrics-" + streamName);
                mt.setDaemon(true);
                mt.start();
            }

            // stdin over the dataplane; stdout/stderr forwarded to the dataplane
            createListener(process.getOutputStream(), streamName, "input");
            StreamGobbler errorGobbler = new StreamGobbler(plugin, process.getErrorStream(), streamName, "error", "error");
            StreamGobbler outputGobbler = new StreamGobbler(plugin, process.getInputStream(), streamName, "output", "output");
            errorGobbler.start();
            outputGobbler.start();

            logger.trace("Waiting for process completion");
            int exitValue = process.waitFor();
            complete = true;
            running = false;
            logger.debug("Process " + streamName + " completed, exit=" + exitValue);

            io.cresco.library.data.DataPlaneService edps = plugin.getAgentService().getDataPlaneService();
            int eshard = edps.shardFor(streamName);
            MapMessage mapMessage = edps.createMapMessage();
            mapMessage.setString("cmd", "execution_log");
            mapMessage.setString("ts", Long.toString(new Date().getTime()));
            mapMessage.setString("log", "[" + new Date() + "] Exit Code: " + exitValue);
            mapMessage.setStringProperty("pluginname", plugin.getConfig().getStringParam("pluginname"));
            mapMessage.setStringProperty("region_id", plugin.getRegion());
            mapMessage.setStringProperty("agent_id", plugin.getAgent());
            mapMessage.setStringProperty("plugin_id", plugin.getPluginID());
            mapMessage.setStringProperty("stream_name", streamName);
            mapMessage.setStringProperty("type", "event");
            edps.sendMessage(TopicType.GLOBAL, mapMessage, jakarta.jms.DeliveryMode.NON_PERSISTENT, 0, 0, eshard);

            mapMessage.clearBody();
            mapMessage.setString("cmd", "delete_exchange");
            mapMessage.setString("ts", Long.toString(new Date().getTime()));
            mapMessage.setStringProperty("stream_name", streamName);
            mapMessage.setStringProperty("type", "event");
            edps.sendMessage(TopicType.GLOBAL, mapMessage, jakarta.jms.DeliveryMode.NON_PERSISTENT, 0, 0, eshard);

        } catch (Exception e) {
            logger.error("Runner.run() error for " + streamName + ": " + e.getMessage(), e);
        } finally {
            running = false;
            removeListener();
        }
    }

    private void writeString(OutputStream os, String request) throws IOException {
        // wrap without closing the process stdin; flush each line through
        Writer bw = new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8));
        bw.write(request);
        bw.write("\r\n");
        bw.flush();
    }

    private boolean createListener(OutputStream os, String streamName, String streamType) {
        try {
            String stream_query = "stream_name='" + streamName + "' and type='" + streamType + "'";
            jakarta.jms.MessageListener ml = msg -> {
                try {
                    if (msg instanceof TextMessage) {
                        String message = ((TextMessage) msg).getText();
                        logger.debug("stdin -> " + streamName + ": " + message);
                        writeString(os, message);
                    }
                } catch (Exception ex) {
                    logger.error("stdin listener error for " + streamName + ": " + ex.getMessage());
                }
            };
            io.cresco.library.data.DataPlaneService dps = plugin.getAgentService().getDataPlaneService();
            // stdin over the GLOBAL dataplane sharded by stream_name so an external client can feed it
            listenerid = dps.addMessageListener(TopicType.GLOBAL, ml, stream_query, dps.shardFor(streamName));
            return true;
        } catch (Exception ex) {
            logger.error("createListener error for " + streamName + ": " + ex.getMessage());
            return false;
        }
    }

    private void removeListener() {
        String id = listenerid;
        if (id != null) {
            try {
                plugin.getAgentService().getDataPlaneService().removeMessageListener(id);
            } catch (Exception ex) {
                logger.error("removeListener error for " + streamName + ": " + ex.getMessage());
            }
            listenerid = null;
        }
    }

    public Boolean isRunning() {
        return this.running;
    }

    public void shutdown() {
        if (complete) return;
        logger.debug("Killing process " + streamName);
        try {
            Process p = this.process;
            if (p != null) {
                // Kill the whole tree via the process handle we launched — no ps/grep/kill string
                // interpolation (injection-safe) and no risk of killing an unrelated process.
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
                // run()'s waitFor() unblocks; its finally removes the stdin listener.
            } else {
                running = false;
            }
        } catch (Exception e) {
            logger.error("shutdown() error for " + streamName + ": " + e.getMessage());
        }
    }
}
