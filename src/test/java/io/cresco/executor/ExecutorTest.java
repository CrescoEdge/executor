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
        CLogger log = (CLogger) Proxy.newProxyInstance(CLogger.class.getClassLoader(), new Class<?>[]{CLogger.class},
                (p, m, a) -> null);
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
        for (String privileged : new String[]{"config_process", "run_process", "start_process", "end_process", "reset_runners"}) {
            assertEquals(Set.of("infra", "admin"), roles.get(privileged), privileged);
        }
        // write_stdin: open to the tenant at the door; the executor itself admits only the runner's starter, admin, infra
        for (String open : new String[]{"status_process", "getmetrics", "getcapabilities", "write_stdin"}) {
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
        assertEquals(List.of("/bin/sh", "-c", "/bin/sh -c \"$1\"; exit $?", "cresco-runner", "echo hi"), line);
        // the command's parent is the launcher shell, not this JVM
        config("config_process", "stream_name", "ppid", "command", "ps -o comm= -p $PPID");
        run("ppid");
        await(() -> ended("ppid"), 10_000, "ps");
        List<String> out = output("ppid");
        assertEquals(1, out.size(), out.toString());
        assertTrue(out.get(0).trim().endsWith("sh"), "the parent is the launcher shell: " + out);
        plugin.close();
        start("runner_user", "cresco-run");
        assertEquals(List.of("sudo", "-n", "-u", "cresco-run", "--", "/bin/sh", "-c", "/bin/sh -c \"$1\"; exit $?",
                "cresco-runner", "echo hi"), new Runner(plugin, "echo hi", "launch", false).commandLine());
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
    void aStreamNameCannotWidenASelectorOrLeaveTheRunnersDirectory() throws Exception {
        start();
        for (String bad : new String[]{"x' OR '1'='1", "../escape", ".hidden", "a/b", "", "with space"}) {
            MsgEvent r = config("config_process", "stream_name", bad, "command", "true", "stdin", "true");
            assertEquals("false", r.getParam("config_status"), bad);
        }
        assertEquals(0, engine.getRunnerCount());
    }
}
