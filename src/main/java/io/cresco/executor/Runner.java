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
    /**
     * What the command shell runs: it reads the command from its stdin (a byte count on one line, then exactly that many
     * bytes, trailing newlines kept: the "x" sentinel stops command substitution from stripping them) and evaluates it.
     * Its {@code $0} is {@code cresco-command}. What follows on stdin is the command's own stdin.
     */
    static final String READER = "IFS= read -r n || exit 126; case $n in \"\"|*[!0-9]*) exit 126;; esac; "
            + "c=$(dd bs=1 count=\"$n\" 2>/dev/null; echo x); c=${c%x}; eval \"$c\"";
    /**
     * The launcher: a shell that stays the parent of the command shell. Where {@code setsid} exists (Linux), the command
     * shell leads a session and process group of its own, so the runner's processes are killed as one group (never by
     * pid, which another process may have reused); elsewhere (macOS) it is an ordinary child. The command is never in any
     * process's arguments ({@code ps}) nor in sudo's log.
     */
    static final String LAUNCHER = "if command -v setsid >/dev/null 2>&1; then setsid /bin/sh -c '" + READER
            + "' cresco-command; else /bin/sh -c '" + READER + "' cresco-command; fi; exit $?";
    /** The sudo binary (tests replace it with a stand-in). */
    static volatile String sudo = "sudo";

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
    // the OS user the launcher runs as (runner_user), or null for the agent's own user
    private volatile String runAs;

    public Runner(PluginBuilder plugin, String command, String streamName, boolean metrics) {
        this(plugin, command, streamName, metrics, false);
    }

    /** @param stdin keep the process's stdin open for {@code write_stdin} (an interactive command always does) */
    public Runner(PluginBuilder plugin, String command, String streamName, boolean metrics, boolean stdin) {
        if (streamName == null || !STREAM_NAME.matcher(streamName).matches()) {
            throw new IllegalArgumentException("stream_name must match " + STREAM_NAME.pattern());
        }
        if (command == null || command.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("command must be text without NUL characters");
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

    /** The launched process (the launcher, or sudo), or null before launch (tests). */
    Process process() {
        return process;
    }

    /**
     * The directory the process starts in: {@code <runner_workdir>/<dirName(stream_name)>}, created if absent.
     */
    Path workDir() throws IOException {
        String base = plugin.getConfig().getStringParam("runner_workdir");
        Path root = base != null ? Paths.get(base) : Paths.get(plugin.getPluginDataDirectory(), "runners");
        Path dir = root.toAbsolutePath().normalize().resolve(dirName(streamName));
        Files.createDirectories(dir);
        return dir;
    }

    /**
     * A stream name as a directory name, one to one even where the filesystem ignores case (macOS, Windows): ':' and
     * every upper-case letter become '%' and two lower-case hex digits ("Job" is "%4aob", "job:1" is "job%3a1"); '%' is
     * not a stream-name character, and nothing else is upper case, so no two names share a directory.
     */
    static String dirName(String streamName) {
        StringBuilder sb = new StringBuilder(streamName.length() + 8);
        for (int i = 0; i < streamName.length(); i++) {
            char c = streamName.charAt(i);
            if (c == ':' || (c >= 'A' && c <= 'Z')) {
                sb.append('%').append(Character.forDigit((c >> 4) & 0xF, 16)).append(Character.forDigit(c & 0xF, 16));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean windows() {
        return System.getProperty("os.name").startsWith("Windows");
    }

    /** The configured runner_user, checked, or null for the agent's own user. */
    String runnerUser() {
        String user = plugin.getConfig().getStringParam("runner_user");
        if (user == null || user.trim().isEmpty()) return null;
        user = user.trim();
        if (!USER_NAME.matcher(user).matches()) {
            throw new IllegalStateException("runner_user must be an OS user name ([a-z_][a-z0-9_-]{0,31})");
        }
        if ("root".equals(user)) throw new IllegalStateException("runner_user may never be root");
        Long uid = uidOf(user);
        if (uid == null) throw new IllegalStateException("runner_user " + user + " is not a user of this host (id -u)");
        if (uid == 0L) throw new IllegalStateException("runner_user " + user + " has uid 0: it may never be root");
        return user;
    }

    /** The uid of {@code user} ({@code id -u}), or null when it cannot be told. */
    static Long uidOf(String user) {
        try {
            Process id = new ProcessBuilder("id", "-u", user).redirectErrorStream(true).start();
            String out = new String(id.getInputStream().readAllBytes(), StandardCharsets.US_ASCII).trim();
            if (!id.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) || id.exitValue() != 0) return null;
            return Long.parseLong(out);
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * The command line that starts the launcher. A launcher shell runs the command as its child, so the command's
     * parent is the launcher (whose environment is the runner's own), never the agent JVM; with {@code runner_user} set,
     * the launcher runs as that user through {@code sudo -n} (the agent's user needs a sudoers rule allowing it to run
     * /bin/sh as that user without a password). The command itself is not on this line: the launcher reads it from its
     * stdin ({@link #LAUNCHER}), so neither {@code ps} nor sudo's log shows it.
     */
    List<String> commandLine() {
        List<String> cmd = new ArrayList<>();
        if (windows()) {
            if (isInteractive(command)) cmd.add("CMD");
            else Collections.addAll(cmd, "CMD", "/C", command);
            return cmd;
        }
        String user = runnerUser();
        if (user != null) Collections.addAll(cmd, sudo, "-n", "-u", user, "--");
        // the trailing "exit" keeps the launcher from exec'ing into the command: it stays the command's parent
        if (isInteractive(command)) {
            Collections.addAll(cmd, "/bin/sh", "-c", "/bin/sh -i; exit $?", "cresco-runner");
        } else {
            Collections.addAll(cmd, "/bin/sh", "-c", LAUNCHER, "cresco-runner");
        }
        return cmd;
    }

    /** What the launcher reads first on its stdin: the command's length in bytes, a newline, the command. */
    byte[] launchHeader() {
        byte[] c = command.getBytes(StandardCharsets.UTF_8);
        byte[] n = (c.length + "\n").getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[n.length + c.length];
        System.arraycopy(n, 0, out, 0, n.length);
        System.arraycopy(c, 0, out, n.length, c.length);
        return out;
    }

    /** True when the command goes to the launcher on its stdin (everywhere but Windows, and not an interactive shell). */
    private boolean commandOnStdin() {
        return !windows() && !isInteractive(command);
    }

    @Override
    public void run() {
        StreamGobbler errorGobbler = null, outputGobbler = null;
        try {
            logger.debug("Running runner " + streamName);
            running = true;

            List<String> line = commandLine();
            runAs = windows() ? null : runnerUser();
            ProcessBuilder pb = new ProcessBuilder(line);
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

            // the command, to the launcher, before anything else is written to its stdin
            if (commandOnStdin()) {
                OutputStream os = process.getOutputStream();
                os.write(launchHeader());
                os.flush();
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

    /**
     * Kill the process and everything it started (its process tree as it is now). As the agent's own user the tree is
     * killed directly. As {@code runner_user}, the agent may not signal that user's processes, nor sudo (which runs as
     * root and does not relay SIGKILL): the tree's processes are killed as that user, with
     * {@code sudo -n -u <user> /bin/sh -c 'kill -KILL ...'} (the same sudoers rule the launcher needs), and sudo exits
     * when its child is gone. A process that left the tree (daemonized, re-parented to init) is not reached either way.
     */
    public void shutdown() {
        logger.debug("Killing process " + streamName);
        try {
            // kill unconditionally on a live handle: gating on a completion flag left a
            // re-run or racing process orphaned while stopRunner reported success
            Process p = this.process;
            if (p != null && p.isAlive()) {
                // the tree via the process handle we launched: no ps/grep, no string interpolation of names
                List<ProcessHandle> tree = new ArrayList<>();
                p.descendants().forEach(tree::add);
                String user = runAs;
                if (user != null) {
                    // by process group where the runner has its own (setsid): a group id is never reused while the
                    // group lives, so no other process can be hit; by pid only where it has none (macOS)
                    List<Long> groups = runnerGroups(tree, uidOf(user));
                    if (!groups.isEmpty()) killGroupsAs(user, groups);
                    else killAs(user, tree);
                } else {
                    // ProcessHandle checks each process's start time: a reused pid is never signalled
                    tree.forEach(ProcessHandle::destroyForcibly);
                }
                p.destroyForcibly();
                if (user != null && !p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                    logger.error("runner " + streamName + " (runner_user " + user + ") did not exit after its process"
                            + " tree was killed: check the sudoers rule allows /bin/sh as " + user);
                }
                // run()'s waitFor() unblocks; its finally closes stdin.
            } else {
                running = false;
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.error("shutdown() error for " + streamName + ": " + e.getMessage());
        }
    }

    /**
     * The process groups the runner's processes lead, among {@code tree}: those whose group id is their own pid and that
     * run as {@code uid} (the runner user), as {@code ps} reports them. Empty when none (no setsid here).
     */
    static List<Long> runnerGroups(List<ProcessHandle> tree, Long uid) {
        List<Long> out = new ArrayList<>();
        if (tree.isEmpty() || uid == null) return out;
        StringBuilder pids = new StringBuilder();
        for (ProcessHandle h : tree) {
            if (pids.length() > 0) pids.append(',');
            pids.append(h.pid());
        }
        try {
            Process ps = new ProcessBuilder("ps", "-o", "pid=,pgid=,uid=", "-p", pids.toString()).redirectErrorStream(true).start();
            String text = new String(ps.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
            ps.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            for (String line : text.split("\n")) {
                String[] f = line.trim().split("\\s+");
                if (f.length < 3) continue;
                try {
                    long pid = Long.parseLong(f[0]), pgid = Long.parseLong(f[1]), u = Long.parseLong(f[2]);
                    if (pid == pgid && u == uid) out.add(pgid);
                } catch (NumberFormatException ignore) {
                }
            }
        } catch (Exception ex) {
            return new ArrayList<>();
        }
        return out;
    }

    /** SIGKILL the process groups {@code groups} as {@code user}, through sudo ({@code kill -s KILL -- -<pgid>}). */
    private void killGroupsAs(String user, List<Long> groups) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        Collections.addAll(cmd, sudo, "-n", "-u", user, "--", "/bin/sh", "-c", "kill -s KILL -- \"$@\" 2>/dev/null; exit 0",
                "cresco-kill");
        for (Long g : groups) cmd.add("-" + g);
        runKill(user, cmd);
    }

    /** SIGKILL {@code tree} as {@code user}, through sudo, by pid (where the runner has no process group of its own). */
    private void killAs(String user, List<ProcessHandle> tree) throws IOException, InterruptedException {
        if (tree.isEmpty()) return;
        List<String> cmd = new ArrayList<>();
        Collections.addAll(cmd, sudo, "-n", "-u", user, "--", "/bin/sh", "-c", "kill -KILL \"$@\" 2>/dev/null; exit 0",
                "cresco-kill");
        for (ProcessHandle h : tree) cmd.add(String.valueOf(h.pid()));
        runKill(user, cmd);
    }

    private void runKill(String user, List<String> cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        RunnerEnvironment.apply(pb.environment(), RunnerEnvironment.MINIMAL);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        Process k = pb.start();
        if (!k.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
            k.destroyForcibly();
            logger.error("killing runner " + streamName + " as " + user + " did not finish in 10 s");
        } else if (k.exitValue() != 0) {
            logger.error("killing runner " + streamName + " as " + user + " failed (exit " + k.exitValue()
                    + "): the sudoers rule must allow the agent's user to run /bin/sh as " + user + " without a password");
        }
    }
}
