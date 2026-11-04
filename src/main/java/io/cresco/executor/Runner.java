package io.cresco.executor;

import io.cresco.library.data.TopicType;
import io.cresco.library.plugin.PluginBuilder;
import io.cresco.library.utilities.CLogger;

import jakarta.jms.MapMessage;
import jakarta.jms.TextMessage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.*;
import java.util.regex.Pattern;

/**
 * One configured command and, once started, its process. What the process can reach (see the README's "What a runner
 * can reach"): it runs as the agent's OS user through {@code /bin/sh -c} (or {@code CMD /C}), starting in its own
 * working directory ({@code <runner_workdir>/<stream_name>}, not a chroot), with the environment {@code runner_env}
 * allows (by default the agent's without its secrets), and stdin only when it was asked for, from writers that know
 * its stdin type.
 */
public class Runner implements Runnable {

    /** A stream name: it names a dataplane stream, a selector value and a directory. */
    static final Pattern STREAM_NAME = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.:-]{0,127}");

    private static final SecureRandom RANDOM = new SecureRandom();

    private final PluginBuilder plugin;
    private final CLogger logger;
    private final String command;
    private final String streamName;
    private final boolean metrics;
    // the dataplane type stdin must carry to reach this process; null: no stdin (the process sees EOF)
    private final String stdinType;
    private final long drainMs;

    // read/written across threads (run thread, control thread) -> must be visible
    private volatile boolean running = false;
    private volatile Process process;
    // a Runner is single-use: re-running one instance overwrote the process field and left the
    // first process orphaned and unkillable; the engine claims it exactly once via tryStart()
    private final java.util.concurrent.atomic.AtomicBoolean started =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile String listenerid;
    // the exit code, once the process ended; for tests and status
    private volatile Integer exitCode;

    public Runner(PluginBuilder plugin, String command, String streamName, boolean metrics) {
        this(plugin, command, streamName, metrics, false);
    }

    /**
     * @param stdin accept stdin over the dataplane (an interactive command always does): it must carry the type
     *              {@link #getStdinType()}, which only the caller that configured the runner is told
     */
    public Runner(PluginBuilder plugin, String command, String streamName, boolean metrics, boolean stdin) {
        if (streamName == null || !STREAM_NAME.matcher(streamName).matches()) {
            throw new IllegalArgumentException("stream_name must match " + STREAM_NAME.pattern());
        }
        this.plugin = plugin;
        logger = plugin.getLogger(Runner.class.getName(), CLogger.Level.Info);
        this.command = command;
        this.streamName = streamName;
        this.metrics = metrics;
        boolean interactive = command.toLowerCase().contains("-interactive-");
        if (stdin || interactive) {
            if (plugin.getConfig().getBooleanParam("stdin_legacy_type", false)) {
                // development only: any dataplane writer that knows the stream name can type into the process
                logger.warn("stdin_legacy_type=true: " + streamName + " takes stdin from any writer of type=input");
                this.stdinType = "input";
            } else {
                byte[] b = new byte[16];
                RANDOM.nextBytes(b);
                StringBuilder sb = new StringBuilder("input-");
                for (byte x : b) sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
                this.stdinType = sb.toString();
            }
        } else {
            this.stdinType = null;
        }
        this.drainMs = Math.max(0L, plugin.getConfig().getLongParam("drain_ms", 10_000L));
    }

    /** The dataplane {@code type} stdin must carry, or null when the process takes no stdin. */
    public String getStdinType() {
        return stdinType;
    }

    Integer getExitCode() {
        return exitCode;
    }

    /** The directory the process starts in: {@code <runner_workdir>/<stream_name>}, created if absent. */
    Path workDir() throws IOException {
        String base = plugin.getConfig().getStringParam("runner_workdir");
        Path root = base != null ? Paths.get(base) : Paths.get(plugin.getPluginDataDirectory(), "runners");
        Path dir = root.toAbsolutePath().normalize().resolve(streamName.replace(':', '_'));
        Files.createDirectories(dir);
        return dir;
    }

    @Override
    public void run() {
        StreamGobbler errorGobbler = null, outputGobbler = null;
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
            pb.directory(workDir().toFile());
            String envMode = RunnerEnvironment.mode(plugin.getConfig().getStringParam("runner_env"));
            if (envMode == null) {
                throw new IllegalStateException("runner_env must be scrubbed, minimal or inherit");
            }
            if (RunnerEnvironment.INHERIT.equals(envMode)) {
                logger.warn("runner_env=inherit: " + streamName + " gets the agent's whole environment, secrets included");
            }
            RunnerEnvironment.apply(pb.environment(), envMode);

            logger.trace("Starting process");
            process = pb.start();

            if (metrics) {
                // Monitor the exact PID we launched (+ its descendants) instead of matching cmdlines.
                RunnerMetrics runnerMetrics = new RunnerMetrics(plugin, process.pid(), streamName);
                Thread mt = new Thread(runnerMetrics, "executor-metrics-" + streamName);
                mt.setDaemon(true);
                mt.start();
            }

            // stdin over the dataplane only when asked for, and only of this runner's own type; otherwise EOF at once,
            // so a command that reads stdin never waits on a pipe nobody may write
            if (stdinType != null) {
                createListener(process.getOutputStream(), streamName, stdinType);
            } else {
                process.getOutputStream().close();
            }
            errorGobbler = new StreamGobbler(plugin, process.getErrorStream(), streamName, "error", "error");
            outputGobbler = new StreamGobbler(plugin, process.getInputStream(), streamName, "output", "output");
            errorGobbler.start();
            outputGobbler.start();

            logger.trace("Waiting for process completion");
            int exitValue = process.waitFor();
            running = false;
            exitCode = exitValue;
            logger.debug("Process " + streamName + " completed, exit=" + exitValue);

            // Forward everything the process wrote BEFORE saying it ended: a consumer tears the stream down on
            // delete_exchange, so an event sent while the gobblers still held buffered output cut off its tail.
            // Bounded: a background child that keeps the pipe open must not hold the exit event forever.
            long deadline = System.currentTimeMillis() + drainMs;
            for (StreamGobbler g : new StreamGobbler[]{outputGobbler, errorGobbler}) {
                long left = deadline - System.currentTimeMillis();
                if (left > 0) g.join(left);
            }
            if (outputGobbler.isAlive() || errorGobbler.isAlive()) {
                logger.warn("output of " + streamName + " is still open " + drainMs
                        + " ms after the process exited (a child holds it); its exit is reported now");
            }

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

        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
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
            // both values are validated identifiers: nothing a caller sends can widen this selector
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

    /** Claim this Runner for its one and only run; false if it already ran or is running. */
    public boolean tryStart() {
        return started.compareAndSet(false, true);
    }

    public void shutdown() {
        logger.debug("Killing process " + streamName);
        try {
            // kill unconditionally on a live handle: gating on a completion flag left a
            // re-run or racing process orphaned while stopRunner reported success
            Process p = this.process;
            if (p != null && p.isAlive()) {
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
