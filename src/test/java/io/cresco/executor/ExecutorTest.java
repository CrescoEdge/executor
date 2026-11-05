package io.cresco.executor;

import io.cresco.library.agent.AgentService;
import io.cresco.library.agent.AgentState;
import io.cresco.library.agent.ControllerState;
import io.cresco.library.capability.CrescoAction;
import io.cresco.library.data.DataPlaneService;
import io.cresco.library.messaging.MsgEvent;
import io.cresco.library.plugin.PluginBuilder;
import io.cresco.library.utilities.CLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jakarta.jms.MapMessage;
import jakarta.jms.Message;
import jakarta.jms.MessageListener;
import jakarta.jms.TextMessage;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The executor on a stand-in agent with a real-enough dataplane (listeners by selector): its roles (item 1), the
 * output tail delivered before the exit events (Runner's drain), and what a runner can reach (item 12): its own working
 * directory, an environment without the agent's secrets, stdin only when asked for and only of its own type, and a
 * stream name that cannot widen a selector.
 */
class ExecutorTest {

    @TempDir Path tmp;
    PluginBuilder plugin;
    RunnerEngine engine;
    ExecutorImpl exec;
    /** Every dataplane message sent, in order. */
    final List<Message> sent = new CopyOnWriteArrayList<>();
    final Map<String, Object[]> listeners = new ConcurrentHashMap<>();
    volatile long outputDelayMs;
    /** Every log line the executor wrote, at every level. */
    final List<String> logged = new CopyOnWriteArrayList<>();

    static Object dflt(Class<?> t) {
        if (t == boolean.class) return false;
        if (t == int.class) return 0;
        if (t == long.class) return 0L;
        if (t == double.class) return 0d;
        return null;
    }

    void start(String... kv) {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("pluginID", "plugin-0");
        for (int i = 0; i + 1 < kv.length; i += 2) cfg.put(kv[i], kv[i + 1]);
        plugin = new PluginBuilder(agent(), "io.cresco.executor.Plugin", null, cfg);
        plugin.setIsActive(true);
        engine = new RunnerEngine(plugin);
        exec = new ExecutorImpl(plugin, engine, new ExecutorMetrics(plugin, engine));
    }

    @AfterEach
    void down() {
        if (engine != null) engine.resetRunners();
        if (plugin != null) {
            plugin.setIsActive(false);
            plugin.close();
        }
    }

    private AgentService agent() {
        ControllerState cs = (ControllerState) Proxy.newProxyInstance(ControllerState.class.getClassLoader(),
                new Class<?>[]{ControllerState.class}, (p, m, a) -> {
                    switch (m.getName()) {
                        case "getRegion": return "region-1";
                        case "getAgent": return "agent-1";
                        case "isActive": return true;
                        default: return dflt(m.getReturnType());
                    }
                });
        AgentState state = new AgentState(cs);
        // every line logged, at every level (debug and trace included)
        CLogger log = (CLogger) Proxy.newProxyInstance(CLogger.class.getClassLoader(), new Class<?>[]{CLogger.class},
                (p, m, a) -> {
                    if (a != null) for (Object o : a) if (o != null) logged.add(m.getName() + ": " + o);
                    return null;
                });
        DataPlaneService dp = (DataPlaneService) Proxy.newProxyInstance(DataPlaneService.class.getClassLoader(),
                new Class<?>[]{DataPlaneService.class}, (p, m, a) -> {
                    switch (m.getName()) {
                        case "createTextMessage": return FakeJms.text();
                        case "createMapMessage": return FakeJms.map();
                        case "createBytesMessage": return FakeJms.bytes();
                        case "shardFor": return 0;
                        case "getDataPlaneShardCount": return 1;
                        case "addMessageListener": {
                            String id = "l" + listeners.size();
                            listeners.put(id, new Object[]{a[2], a[1]});
                            return id;
                        }
                        case "removeMessageListener":
                            listeners.remove(String.valueOf(a[0]));
                            return null;
                        case "sendMessage": {
                            Message msg = (Message) (a[1] instanceof Message ? a[1] : a[2]);
                            if (outputDelayMs > 0 && "output".equals(msg.getStringProperty("type"))) Thread.sleep(outputDelayMs);
                            sent.add(FakeJms.copy(msg));
                            publish(msg);
                            return true;
                        }
                        default: return dflt(m.getReturnType());
                    }
                });
        return (AgentService) Proxy.newProxyInstance(AgentService.class.getClassLoader(), new Class<?>[]{AgentService.class},
                (p, m, a) -> {
                    switch (m.getName()) {
                        case "getAgentState": return state;
                        case "getCLogger": return log;
                        case "getAgentDataDirectory": return tmp.resolve("agent").toString();
                        case "isSecurityEnabled": return true;
                        case "getDataPlaneService": return dp;
                        default: return dflt(m.getReturnType());
                    }
                });
    }

    void publish(Message m) {
        for (Object[] l : listeners.values()) {
            if (FakeJms.matches((String) l[0], m)) {
                try { ((MessageListener) l[1]).onMessage(FakeJms.copy(m)); } catch (RuntimeException ignore) { }
            }
        }
    }

    MsgEvent config(String action, String... kv) {
        MsgEvent m = new MsgEvent(MsgEvent.Type.CONFIG, "region-1", "agent-1", null, "region-1", "agent-1", "plugin-0", false, false);
        m.setParam("action", action);
        for (int i = 0; i + 1 < kv.length; i += 2) m.setParam(kv[i], kv[i + 1]);
        return exec.executeCONFIG(m);
    }

    MsgEvent run(String stream) {
        MsgEvent m = new MsgEvent(MsgEvent.Type.EXEC, "region-1", "agent-1", null, "region-1", "agent-1", "plugin-0", false, false);
        m.setParam("action", "run_process");
        m.setParam("stream_name", stream);
        return exec.executeEXEC(m);
    }

    /** Stamp {@code m} as infrastructure would: {@code principal} of tenant-a holding {@code roles}. */
    static MsgEvent as(MsgEvent m, String principal, String roles) {
        m.setParam("cresco_caller", principal);
        m.setParam("cresco_caller_tenant", "tenant-a");
        m.setParam("cresco_caller_roles", roles);
        return m;
    }

    MsgEvent exec(String action, String... kv) {
        MsgEvent m = new MsgEvent(MsgEvent.Type.EXEC, "region-1", "agent-1", null, "region-1", "agent-1", "plugin-0", false, false);
        m.setParam("action", action);
        for (int i = 0; i + 1 < kv.length; i += 2) m.setParam(kv[i], kv[i + 1]);
        return m;
    }

    static void await(BooleanSupplier cond, long ms, String what) throws Exception {
        long deadline = System.currentTimeMillis() + ms;
        while (!cond.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) fail("timed out: " + what);
            Thread.sleep(20);
        }
    }

    /** The output lines and the index (in send order) of the delete_exchange, for one stream. */
    List<String> output(String stream) throws Exception {
        List<String> out = new ArrayList<>();
        for (Message m : sent) {
            if (stream.equals(m.getStringProperty("stream_name")) && "output".equals(m.getStringProperty("type"))) {
                out.add(((TextMessage) m).getText());
            }
        }
        return out;
    }

    boolean ended(String stream) {
        for (Message m : sent) {
            try {
                if (m instanceof MapMessage && stream.equals(m.getStringProperty("stream_name"))
                        && "delete_exchange".equals(((MapMessage) m).getString("cmd"))) return true;
            } catch (Exception ignore) { }
        }
        return false;
    }

    // ------------------------------------------------------------------ roles (item 1)

    @Test
    void everyActionThatRunsOrStopsCodeNeedsInfraOrAdmin() {
        Map<String, Set<String>> roles = new LinkedHashMap<>();
        for (CrescoAction a : ExecutorImpl.class.getAnnotationsByType(CrescoAction.class)) {
            roles.put(a.name(), new HashSet<>(Arrays.asList(a.roles())));
        }
        // write_stdin too (the library's privileged floor for this bundle); the executor itself admits, of those, only
        // the runner's starter, an admin or infra
        for (String privileged : new String[]{"config_process", "run_process", "start_process", "end_process", "reset_runners",
                "write_stdin"}) {
            assertEquals(Set.of("infra", "admin"), roles.get(privileged), privileged);
        }
        for (String open : new String[]{"status_process", "getmetrics", "getcapabilities"}) {
            assertEquals(Set.of(), roles.get(open), open);
        }
        assertEquals(9, roles.size());
    }

    // ------------------------------------------------------------------ the output tail (Runner's drain)

    @Test
    void everyLineOfOutputIsSentBeforeTheProcessIsReportedEnded() throws Exception {
        start();
        outputDelayMs = 1;      // a slow consumer: the gobbler is still sending when the process has long exited
        assertEquals("true", config("config_process", "stream_name", "tail", "command",
                "i=0; while [ $i -lt 400 ]; do echo line$i; i=$((i+1)); done").getParam("config_status"));
        assertEquals("true", run("tail").getParam("status"));
        await(() -> ended("tail"), 30_000, "the exit events");
        List<String> lines = output("tail");
        assertEquals(400, lines.size(), "the whole tail arrived before delete_exchange");
        assertEquals("line399", lines.get(399));
        int lastOutput = -1, firstEvent = -1;
        for (int i = 0; i < sent.size(); i++) {
            Message m = sent.get(i);
            if (!"tail".equals(m.getStringProperty("stream_name"))) continue;
            if ("output".equals(m.getStringProperty("type"))) lastOutput = i;
            if ("event".equals(m.getStringProperty("type")) && firstEvent < 0) firstEvent = i;
        }
        assertTrue(lastOutput < firstEvent, "no output after the exit events");
    }

    @Test
    void aChildHoldingTheOutputOpenDelaysTheExitEventOnlyBriefly() throws Exception {
        start("drain_ms", "300");
        config("config_process", "stream_name", "bg", "command", "sleep 3 & echo started");
        long t0 = System.currentTimeMillis();
        run("bg");
        await(() -> ended("bg"), 2_500, "the exit event, without waiting for the background child");
        assertTrue(System.currentTimeMillis() - t0 < 2_500);
    }

    // ------------------------------------------------------------------ what a runner can reach (item 12)

    @Test
    void aRunnerStartsInItsOwnDirectory() throws Exception {
        start();
        config("config_process", "stream_name", "where", "command", "pwd");
        run("where");
        await(() -> ended("where"), 10_000, "pwd");
        Path expected = tmp.resolve("agent/plugin-data/plugin-0/runners/where").toRealPath();
        assertEquals(List.of(expected.toString()), output("where"));
    }

    @Test
    void aRunnersEnvironmentIsAnAllowlist() {
        Map<String, String> env = new HashMap<>();
        for (String k : new String[]{"PATH", "HOME", "LANG", "LC_ALL", "USER", "SSH_AUTH_SOCK", "CRESCO_keystorepwd",
                "CRESCO_BROKER_SECURITY_SECRET_FILE", "AWS_SECRET_ACCESS_KEY", "GITHUB_TOKEN", "DB_PASSWORD", "SIGNING_KEY",
                "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "JAVA_OPTS", "CATALINA_OPTS", "GPG_PASSPHRASE",
                "SIM_PIN", "DB_PASSWD", "MYSQL_PWD", "SOMETHING_NEW"}) env.put(k, "v");
        Map<String, String> scrubbed = new HashMap<>(env);
        RunnerEnvironment.apply(scrubbed, RunnerEnvironment.SCRUBBED);
        assertEquals(Set.of("PATH", "HOME", "LANG", "LC_ALL", "USER"), scrubbed.keySet(), "only what the allowlist names");
        // an admin may add names, never a secret-carrying one
        Map<String, String> allowed = new HashMap<>(env);
        RunnerEnvironment.apply(allowed, RunnerEnvironment.SCRUBBED,
                RunnerEnvironment.extra("SSH_AUTH_SOCK, DB_PASSWORD, CRESCO_keystorepwd, JAVA_OPTS, MYSQL_PWD, bad-name"));
        assertEquals(Set.of("PATH", "HOME", "LANG", "LC_ALL", "USER", "SSH_AUTH_SOCK"), allowed.keySet());
        Map<String, String> minimal = new HashMap<>(env);
        RunnerEnvironment.apply(minimal, RunnerEnvironment.MINIMAL);
        assertEquals(Set.of("PATH", "HOME", "LANG", "LC_ALL"), minimal.keySet());
        Map<String, String> all = new HashMap<>(env);
        RunnerEnvironment.apply(all, RunnerEnvironment.INHERIT);
        assertEquals(env.keySet(), all.keySet());
        assertNull(RunnerEnvironment.mode("everything"), "an unknown mode is refused, not guessed");
        assertEquals(RunnerEnvironment.SCRUBBED, RunnerEnvironment.mode(null));
    }

    @Test
    void theCommandRunsUnderALauncherAndOptionallyAsAnotherUser() throws Exception {
        start();
        Runner r = new Runner(plugin, "echo hi", "launch", false);
        List<String> line = r.commandLine();
        // the command is not on the command line: the launcher reads it from its stdin
        assertEquals(List.of("/bin/sh", "-c", Runner.LAUNCHER, "cresco-runner"), line);
        // the command's parent is the launcher shell, not this JVM
        config("config_process", "stream_name", "ppid", "command", "ps -o comm= -p $PPID");
        run("ppid");
        await(() -> ended("ppid"), 10_000, "ps");
        List<String> out = output("ppid");
        assertEquals(1, out.size(), out.toString());
        assertTrue(out.get(0).trim().endsWith("sh"), "the parent is the launcher shell: " + out);
        plugin.close();
        String me = System.getProperty("user.name");
        start("runner_user", me);
        assertEquals(List.of("sudo", "-n", "-u", me, "--", "/bin/sh", "-c", Runner.LAUNCHER, "cresco-runner"),
                new Runner(plugin, "echo hi", "launch", false).commandLine());
        plugin.close();
        start("runner_user", "root; rm -rf /");
        Runner bad = new Runner(plugin, "echo hi", "launch", false);
        assertThrows(IllegalStateException.class, bad::commandLine);
    }

    @Test
    void aMinimalEnvironmentReallyIsMinimal() throws Exception {
        start("runner_env", "minimal");
        config("config_process", "stream_name", "env", "command", "env");
        run("env");
        await(() -> ended("env"), 10_000, "env");
        for (String line : output("env")) {
            String name = line.substring(0, Math.max(0, line.indexOf('=')));
            if (name.isEmpty()) continue;
            assertTrue(Set.of("PATH", "HOME", "LANG", "TZ", "PWD", "SHLVL", "_", "OLDPWD").contains(name)
                    || name.startsWith("LC_"), "unexpected variable " + name);
        }
    }

    @Test
    void stdinIsWrittenOnlyByTheStarterAnAdminOrInfraAndNeverFromTheDataplane() throws Exception {
        start();
        // no stdin: the process sees EOF at once, so cat ends by itself; a write is refused
        config("config_process", "stream_name", "nostdin", "command", "cat");
        exec.executeEXEC(as(exec("run_process", "stream_name", "nostdin"), "api:tenant-a/alice", "user"));
        await(() -> ended("nostdin"), 10_000, "cat ended on EOF");
        assertEquals("no-stdin", exec.executeEXEC(as(exec("write_stdin", "stream_name", "nostdin", "data", "x"), "api:tenant-a/alice", "user"))
                .getParam("refusal"), "a runner configured without stdin takes none");
        assertEquals("8", exec.executeEXEC(as(exec("write_stdin", "stream_name", "nosuch", "data", "x"), "api:tenant-a/alice", "user"))
                .getParam("status"));

        assertEquals("true", config("config_process", "stream_name", "withstdin", "command", "cat", "stdin", "true").getParam("config_status"));
        assertEquals("true", exec.executeEXEC(as(exec("run_process", "stream_name", "withstdin"), "api:tenant-a/alice", "user")).getParam("status"));
        await(() -> engine.isRunning("withstdin"), 10_000, "running");
        Thread.sleep(200);
        // no dataplane listener takes stdin any more: a frame of any type reaches nothing
        assertTrue(listeners.isEmpty(), "no stdin listener on the dataplane");
        TextMessage frame = FakeJms.text();
        frame.setStringProperty("stream_name", "withstdin");
        frame.setStringProperty("type", "input");
        frame.setText("from the dataplane");
        publish(frame);
        // another user, and an unverified caller: refused
        MsgEvent bob = exec.executeEXEC(as(exec("write_stdin", "stream_name", "withstdin", "data", "from bob"), "api:tenant-a/bob", "user"));
        assertEquals("not-owner", bob.getParam("refusal"));
        assertEquals("not-owner", exec.executeEXEC(exec("write_stdin", "stream_name", "withstdin", "data", "unstamped")).getParam("refusal"));
        MsgEvent forged = exec("write_stdin", "stream_name", "withstdin", "data", "forged");
        forged.setParam("cresco_caller", "api:tenant-a/alice");      // a malformed stamp (no tenant, no roles)
        assertEquals("not-owner", exec.executeEXEC(forged).getParam("refusal"));
        // the starter, and an admin
        assertEquals("10", exec.executeEXEC(as(exec("write_stdin", "stream_name", "withstdin", "data", "from alice"), "api:tenant-a/alice", "user")).getParam("status"));
        assertEquals("10", exec.executeEXEC(as(exec("write_stdin", "stream_name", "withstdin", "data", "from an admin"), "api:tenant-a/root", "admin")).getParam("status"));
        await(() -> {
            try { return output("withstdin").containsAll(List.of("from alice", "from an admin")); } catch (Exception e) { return false; }
        }, 10_000, "the allowed lines echoed");
        // end of input: cat exits by itself
        assertEquals("10", exec.executeEXEC(as(exec("write_stdin", "stream_name", "withstdin", "close_stdin", "true"), "api:tenant-a/alice", "user")).getParam("status"));
        await(() -> ended("withstdin"), 10_000, "cat ended on the closed stdin");
        List<String> out = output("withstdin");
        assertFalse(out.contains("from the dataplane") || out.contains("from bob") || out.contains("unstamped") || out.contains("forged"), out.toString());
    }

    @Test
    void neitherStdinNorOutputIsEverLogged() throws Exception {
        start();
        String secretIn = "stdin-secret-" + System.nanoTime();
        String secretOut = "output-secret-" + System.nanoTime();
        config("config_process", "stream_name", "quiet", "command", "echo " + secretOut + "; cat", "stdin", "true");
        exec.executeEXEC(as(exec("run_process", "stream_name", "quiet"), "api:tenant-a/alice", "user"));
        assertEquals("10", exec.executeEXEC(as(exec("write_stdin", "stream_name", "quiet", "data", secretIn), "api:tenant-a/alice", "user"))
                .getParam("status"));
        assertEquals("10", exec.executeEXEC(as(exec("write_stdin", "stream_name", "quiet", "close_stdin", "true"), "api:tenant-a/alice", "user"))
                .getParam("status"));
        await(() -> ended("quiet"), 10_000, "the process");
        assertTrue(output("quiet").containsAll(List.of(secretOut, secretIn)), "precondition: both were forwarded");
        assertFalse(logged.isEmpty(), "precondition: the executor logs");
        for (String line : logged) {
            assertFalse(line.contains(secretIn) || line.contains(secretOut), "a log line holds process content: " + line);
        }
        // sizes and counts are logged instead
        assertTrue(logged.stream().anyMatch(l -> l.contains("byte(s) to quiet")), logged.toString());
        assertTrue(logged.stream().anyMatch(l -> l.contains("line(s)") && l.contains("quiet")), logged.toString());
    }

    @Test
    void theCommandIsNeverInAnyProcessArgumentsAndRunsExactly() throws Exception {
        start();
        String marker = "cresco-marker-" + Long.toHexString(System.nanoTime());
        // a comment carrying the marker: it is part of the command, never of what the command runs
        config("config_process", "stream_name", "argv", "command", "sleep 2 # " + marker);
        run("argv");
        Thread.sleep(700);
        Process ps = new ProcessBuilder("ps", "-axww", "-o", "args=").redirectErrorStream(true).start();
        String all = new String(ps.getInputStream().readAllBytes());
        ps.waitFor();
        assertTrue(all.contains("cresco-runner"), "precondition: the launcher is running");
        assertFalse(all.contains(marker), "the command text is visible in a process's arguments");
        await(() -> ended("argv"), 10_000, "argv");
        // quoting, several lines and non-ASCII text arrive exactly
        config("config_process", "stream_name", "exact", "command", "printf '%s|' \"a  b\" '$HOME'\necho \"\u00fc\u00f1\u00ee\"");
        run("exact");
        await(() -> ended("exact"), 10_000, "exact");
        assertEquals(List.of("a  b|$HOME|\u00fc\u00f1\u00ee"), output("exact"));
    }

    @Test
    void streamNamesMapToDirectoriesOneToOneWhateverTheCase() {
        assertEquals("%4aob", Runner.dirName("Job"));
        assertEquals("job", Runner.dirName("job"));
        assertEquals("job%3a1", Runner.dirName("job:1"));
        assertEquals("job_1", Runner.dirName("job_1"));
        Set<String> seen = new HashSet<>();
        for (String n : new String[]{"Job", "job", "JOB", "jOb", "job:1", "job_1", "a.b", "A.b", "x-1", "X-1"}) {
            assertTrue(seen.add(Runner.dirName(n).toLowerCase(java.util.Locale.ROOT)), n);
        }
    }

    /** A stand-in sudo: like sudo, it keeps its child as a child and passes no SIGKILL on; it records each call. */
    Path fakeSudo(Path log, Path extraPath) throws Exception {
        Path fake = tmp.resolve("fake-sudo");
        java.nio.file.Files.writeString(fake, "#!/bin/sh\n"
                + "echo \"$*\" >> '" + log + "'\n"
                + (extraPath == null ? "" : "PATH='" + extraPath + "':$PATH; export PATH\n")
                + "while [ \"$1\" != \"--\" ]; do shift; done; shift\n"
                + "\"$@\"; exit $?\n");
        assertTrue(fake.toFile().setExecutable(true));
        return fake;
    }

    /**
     * With runner_user, the agent may not signal the runner's processes, and sudo (root) does not relay SIGKILL: the
     * tree is killed as that user through sudo. Here (macOS) there is no setsid, so the runner has no process group of
     * its own and its processes are killed by pid.
     */
    @Test
    void endProcessKillsARunnerUsersProcessTreeThroughSudo() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(!onPath("setsid"), "with setsid, the group test below applies");
        Path log = tmp.resolve("sudo.log");
        String saved = Runner.sudo;
        Runner.sudo = fakeSudo(log, null).toString();
        String me = System.getProperty("user.name");
        try {
            start("runner_user", me);
            config("config_process", "stream_name", "long", "command", "sleep 300");
            run("long");
            Runner r = engine.get("long");
            await(() -> r.isRunning() && descendants(r).stream().anyMatch(h -> h.info().command().orElse("").endsWith("sleep")),
                    10_000, "sleep running under the stand-in sudo");
            List<ProcessHandle> tree = descendants(r);
            MsgEvent end = config("end_process", "stream_name", "long");
            assertNotNull(end);
            await(() -> tree.stream().noneMatch(ProcessHandle::isAlive), 10_000, "the runner's process tree killed");
            String calls = java.nio.file.Files.readString(log);
            assertTrue(calls.contains("-n -u " + me + " -- /bin/sh -c kill -KILL \"$@\""), "killed through sudo as the runner user: " + calls);
            await(() -> ended("long"), 10_000, "the runner reported ended");
        } finally {
            Runner.sudo = saved;
        }
    }

    /**
     * Where setsid exists the runner leads its own process group, and end_process kills that GROUP as the runner user
     * (kill -s KILL -- -pgid): a group id is never reused while the group lives, so no other process can be hit. Here a
     * stand-in setsid (perl's POSIX::setsid) gives macOS the same; the command starts two processes.
     */
    @Test
    void endProcessKillsARunnerUsersProcessGroupThroughSudo() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(new java.io.File("/usr/bin/perl").canExecute(), "needs perl for a stand-in setsid");
        Path bin = java.nio.file.Files.createDirectories(tmp.resolve("bin"));
        Path setsid = bin.resolve("setsid");
        java.nio.file.Files.writeString(setsid, "#!/bin/sh\nexec /usr/bin/perl -e 'use POSIX (); POSIX::setsid() or die \"setsid: $!\"; exec { $ARGV[0] } @ARGV or die' \"$@\"\n");
        assertTrue(setsid.toFile().setExecutable(true));
        Path log = tmp.resolve("sudo.log");
        String saved = Runner.sudo;
        Runner.sudo = fakeSudo(log, bin).toString();
        String me = System.getProperty("user.name");
        try {
            start("runner_user", me);
            config("config_process", "stream_name", "grp", "command", "sleep 301 & sleep 302");
            run("grp");
            Runner r = engine.get("grp");
            await(() -> descendants(r).stream().filter(h -> h.info().command().orElse("").endsWith("sleep")).count() == 2,
                    10_000, "two sleeps running in the runner's own group");
            List<ProcessHandle> tree = descendants(r);
            assertFalse(Runner.runnerGroups(tree, Runner.uidOf(me)).isEmpty(), "precondition: the runner leads a group");
            config("end_process", "stream_name", "grp");
            await(() -> tree.stream().noneMatch(ProcessHandle::isAlive), 10_000, "the runner's process group killed");
            String calls = java.nio.file.Files.readString(log);
            assertTrue(calls.matches("(?s).*kill -s KILL -- \"\\$@\" 2>/dev/null; exit 0 cresco-kill -[0-9]+.*"),
                    "killed as a process group: " + calls);
            await(() -> ended("grp"), 10_000, "the runner reported ended");
        } finally {
            Runner.sudo = saved;
        }
    }

    @Test
    void runnerUserMustBeARealUserThatIsNotRoot() {
        start("runner_user", "no-such-user-" + Long.toHexString(System.nanoTime()).substring(0, 8));
        assertThrows(IllegalStateException.class, new Runner(plugin, "id", "x", false)::runnerUser);
        plugin.close();
        start("runner_user", System.getProperty("user.name"));
        assertEquals(System.getProperty("user.name"), new Runner(plugin, "id", "x", false).runnerUser());
    }

    @Test
    void theLauncherKeepsTrailingNewlinesAndNamesItselfCrescoCommand() throws Exception {
        start();
        // a here-document that runs to the end of the command: its trailing empty lines are part of it, and arrive
        config("config_process", "stream_name", "nl", "command", "echo \"$0\"; cat <<'EOF' | wc -l\na\n\n\n");
        run("nl");
        await(() -> ended("nl"), 10_000, "nl");
        List<String> out = output("nl");
        assertEquals("cresco-command", out.get(0).trim());
        assertEquals("3", out.get(1).trim(), "the here-document's three lines, empty ones included");
    }

    static boolean onPath(String exe) {
        for (String d : System.getenv().getOrDefault("PATH", "").split(":")) {
            if (new java.io.File(d, exe).canExecute()) return true;
        }
        return false;
    }

    /** The same, for real, as another OS user: runs only where CRESCO_TEST_RUNNER_USER names one sudo -n can run. */
    @Test
    void endProcessKillsARealOtherUsersRunner() throws Exception {
        String user = System.getenv("CRESCO_TEST_RUNNER_USER");
        org.junit.jupiter.api.Assumptions.assumeTrue(user != null && !user.isEmpty(), "CRESCO_TEST_RUNNER_USER is not set");
        Process probe = new ProcessBuilder("sudo", "-n", "-u", user, "--", "/bin/sh", "-c", "true").start();
        org.junit.jupiter.api.Assumptions.assumeTrue(probe.waitFor() == 0, "sudo -n -u " + user + " /bin/sh is not allowed");
        start("runner_user", user);
        config("config_process", "stream_name", "other", "command", "sleep 300");
        run("other");
        Runner r = engine.get("other");
        await(() -> descendants(r).stream().anyMatch(h -> h.info().command().orElse("").endsWith("sleep")), 10_000, "sleep as " + user);
        List<ProcessHandle> tree = descendants(r);
        config("end_process", "stream_name", "other");
        await(() -> tree.stream().noneMatch(ProcessHandle::isAlive), 10_000, "the other user's process tree killed");
        await(() -> ended("other"), 10_000, "the runner reported ended");
    }

    static List<ProcessHandle> descendants(Runner r) {
        Process p = r.process();
        List<ProcessHandle> out = new ArrayList<>();
        if (p != null) p.descendants().forEach(out::add);
        return out;
    }

    @Test
    void aStreamNameCannotWidenASelectorOrLeaveTheRunnersDirectory() throws Exception {
        start();
        for (String bad : new String[]{"x' OR '1'='1", "../escape", ".hidden", "a/b", "", "with space"}) {
            MsgEvent r = config("config_process", "stream_name", bad, "command", "true", "stdin", "true");
            assertEquals("false", r.getParam("config_status"), bad);
        }
        assertEquals(0, engine.getRunnerCount());
    }
}
