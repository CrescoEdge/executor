package io.cresco.executor;

import io.cresco.library.data.TopicType;
import io.cresco.library.plugin.PluginBuilder;
import io.cresco.library.utilities.CLogger;

import jakarta.jms.MapMessage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;

/**
 * One configured command and, once started, its process. What the process can reach is in the README ("What a runner
 * can reach"): it runs through a launcher shell (so its parent is the launcher, not the agent JVM), as the agent's OS
 * user unless {@code runner_user} names another, starting in its own working directory, with the environment
 * {@code runner_env} allows, and with stdin only when it was asked for, written through {@code write_stdin} by the
 * caller that started it (or an admin or infra), never from the dataplane.
 */
public class Runner implements Runnable {

    /** A stream name: it names a dataplane stream, a selector value and a directory. */
    static final Pattern STREAM_NAME = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.:-]{0,127}");
    /** An OS user name runner_user may name. */
    static final Pattern USER_NAME = Pattern.compile("[a-z_][a-z0-9_-]{0,31}");
    /** Bound on one write_stdin. */
    static final int MAX_STDIN_WRITE = 64 * 1024;

    private final PluginBuilder plugin;
    private final CLogger logger;
    private final String command;
    private final String streamName;
    private final boolean metrics;
    // does the process take stdin (write_stdin)? otherwise it sees EOF at once
    private final boolean stdin;
    private final long drainMs;
    // who started it (the verified caller's principal), for write_stdin; null for a runner the plugin's own config started
    private volatile String startedBy;

    // read/written across threads (run thread, control thread) -> must be visible
    private volatile boolean running = false;
    private volatile Process process;
    // a Runner is single-use: re-running one instance overwrote the process field and left the
    // first process orphaned and unkillable; the engine claims it exactly once via tryStart()
    private final java.util.concurrent.atomic.AtomicBoolean started =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private final Object stdinLock = new Object();
    // counted down once the process is launched (and its stdin attached) or failed to launch
    private final java.util.concurrent.CountDownLatch launched = new java.util.concurrent.CountDownLatch(1);
    private volatile OutputStream stdinStream;
    // the exit code, once the process ended; for tests and status
    private volatile Integer exitCode;

    public Runner(PluginBuilder plugin, String command, String streamName, boolean metrics) {
        this(plugin, command, streamName, metrics, false);
    }

    /** @param stdin keep the process's stdin open for {@code write_stdin} (an interactive command always does) */
    public Runner(PluginBuilder plugin, String command, String streamName, boolean metrics, boolean stdin) {
        if (streamName == null || !STREAM_NAME.matcher(streamName).matches()) {
            throw new IllegalArgumentException("stream_name must match " + STREAM_NAME.pattern());
        }
        this.plugin = plugin;
        logger = plugin.getLogger(Runner.class.getName(), CLogger.Level.Info);
        this.command = command;
        this.streamName = streamName;
        this.metrics = metrics;
        this.stdin = stdin || isInteractive(command);
        this.drainMs = Math.max(0L, plugin.getConfig().getLongParam("drain_ms", 10_000L));
    }

    static boolean isInteractive(String command) {
        return command.toLowerCase(Locale.ROOT).contains("-interactive-");
    }

    /** True when the process takes stdin through write_stdin. */
    public boolean takesStdin() {
        return stdin;
    }

    void setStartedBy(String principal) {
        this.startedBy = principal;
    }

    String getStartedBy() {
        return startedBy;
    }

    Integer getExitCode() {
        return exitCode;
    }

    /**
     * The directory the process starts in: {@code <runner_workdir>/<stream_name>}, created if absent. The name maps
     * one to one (':' is the only character of a stream name a directory name may not keep, and becomes "%3A"; '%' is
     * not a stream-name character), so two runners never share a directory.
     */
    Path workDir() throws IOException {
        String base = plugin.getConfig().getStringParam("runner_workdir");
        Path root = base != null ? Paths.get(base) : Paths.get(plugin.getPluginDataDirectory(), "runners");
        Path dir = root.toAbsolutePath().normalize().resolve(streamName.replace(":", "%3A"));
        Files.createDirectories(dir);
        return dir;
    }

    /**
     * The command line that runs {@code command}. A launcher shell runs the command as its child, so the command's
     * parent is the launcher (whose environment is the runner's own), never the agent JVM; with {@code runner_user} set,
     * the launcher runs as that user through {@code sudo -n} (the agent's user needs a sudoers rule for it).
     */
    List<String> commandLine() {
        List<String> cmd = new ArrayList<>();
        if (System.getProperty("os.name").startsWith("Windows")) {
            if (isInteractive(command)) cmd.add("CMD");
            else Collections.addAll(cmd, "CMD", "/C", command);
            return cmd;
        }
        String user = plugin.getConfig().getStringParam("runner_user");
        if (user != null && !user.trim().isEmpty()) {
            if (!USER_NAME.matcher(user.trim()).matches()) {
                throw new IllegalStateException("runner_user must be an OS user name ([a-z_][a-z0-9_-]{0,31})");
            }
            Collections.addAll(cmd, "sudo", "-n", "-u", user.trim(), "--");
        }
        // the trailing "exit" keeps the launcher from exec'ing into the command: it stays the command's parent
        if (isInteractive(command)) {
            Collections.addAll(cmd, "/bin/sh", "-c", "/bin/sh -i; exit $?", "cresco-runner");
        } else {
            Collections.addAll(cmd, "/bin/sh", "-c", "/bin/sh -c \"$1\"; exit $?", "cresco-runner", command);
        }
        return cmd;
    }

    @Override
    public void run() {
        StreamGobbler errorGobbler = null, outputGobbler = null;
        try {
            logger.debug("Running runner " + streamName);
            running = true;

            ProcessBuilder pb = new ProcessBuilder(commandLine());
            pb.directory(workDir().toFile());
            String envMode = RunnerEnvironment.mode(plugin.getConfig().getStringParam("runner_env"));
            if (envMode == null) {
                throw new IllegalStateException("runner_env must be scrubbed, minimal or inherit");
            }
            if (RunnerEnvironment.INHERIT.equals(envMode)) {
                logger.warn("runner_env=inherit: " + streamName + " gets the agent's whole environment, secrets included");
            }
            RunnerEnvironment.apply(pb.environment(), envMode,
                    RunnerEnvironment.extra(plugin.getConfig().getStringParam("runner_env_allow")));

            logger.trace("Starting process");
            process = pb.start();

            if (metrics) {
                // Monitor the exact PID we launched (+ its descendants) instead of matching cmdlines.
                RunnerMetrics runnerMetrics = new RunnerMetrics(plugin, process.pid(), streamName);
                Thread mt = new Thread(runnerMetrics, "executor-metrics-" + streamName);
                mt.setDaemon(true);
                mt.start();
            }

            // stdin only through write_stdin, and only when asked for; otherwise EOF at once, so a command that reads
            // stdin never waits on a pipe nobody may write
            if (stdin) {
                stdinStream = process.getOutputStream();
            } else {
                process.getOutputStream().close();
            }
            launched.countDown();
            errorGobbler = new StreamGobbler(plugin, process.getErrorStream(), streamName, "error", "error");
            outputGobbler = new StreamGobbler(plugin, process.getInputStream(), streamName, "output", "output");
            errorGobbler.start();
            outputGobbler.start();

            logger.trace("Waiting for process completion");
            int exitValue = process.waitFor();
            running = false;
            exitCode = exitValue;
            closeStdin();
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
            launched.countDown();
            closeStdin();
        }
    }

    /** Outcome of a write to stdin. */
    enum StdinResult { WRITTEN, NO_STDIN, NOT_RUNNING, FAILED }

    /** Write {@code data} to the process's stdin, then close it if {@code close}. */
    StdinResult writeStdin(byte[] data, boolean close) {
        if (!stdin) return StdinResult.NO_STDIN;
        if (started.get()) {
            // started a moment ago: wait (bounded) for the process to be launched and its stdin attached
            try {
                launched.await(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (stdinLock) {
            OutputStream os = stdinStream;
            if (os == null || !running) return StdinResult.NOT_RUNNING;
            try {
                if (data != null && data.length > 0) {
                    os.write(data);
                    os.flush();
                }
                if (close) {
                    os.close();
                    stdinStream = null;
                }
                return StdinResult.WRITTEN;
            } catch (IOException ex) {
                logger.warn("stdin of " + streamName + " could not be written: " + ex.getMessage());
                return StdinResult.FAILED;
            }
        }
    }

    private void closeStdin() {
        synchronized (stdinLock) {
            OutputStream os = stdinStream;
            stdinStream = null;
            if (os != null) {
                try { os.close(); } catch (IOException ignore) { }
            }
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
                // run()'s waitFor() unblocks; its finally closes stdin.
            } else {
                running = false;
            }
        } catch (Exception e) {
            logger.error("shutdown() error for " + streamName + ": " + e.getMessage());
        }
    }
}
