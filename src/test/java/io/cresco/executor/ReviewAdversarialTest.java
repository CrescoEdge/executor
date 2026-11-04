package io.cresco.executor;

import io.cresco.library.messaging.MsgEvent;
import org.junit.jupiter.api.Test;

import jakarta.jms.Message;
import jakarta.jms.MessageListener;
import jakarta.jms.TextMessage;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Adversarial review (review/plugins-20261104) of the executor hardening (2400f5e). Each test states what the
 * hardening claims; each FAILS on that commit. Local only: never pushed.
 */
class ReviewAdversarialTest extends ExecutorTest {

    /**
     * HIGH when a runner takes stdin (always for an -interactive- shell). The stdin "type" is a bearer secret carried as
     * a property of every stdin frame on the tenant's dataplane topic; any subscriber of stream_name='X' (a selector
     * wsapi's StreamSelectors accepts from any dataplane client) sees it on the owner's first keystroke and can then
     * type into the process -- run commands as the agent's user without config_process/run_process's infra/admin role.
     *
     * <p>Adapted (coordinator, 2026-11-04) to the stdin path that replaced the dataplane: the owner writes with
     * write_stdin; stdin never travels on the dataplane, so the bystander sees no stdin frame at all. It still tries
     * every way it has -- dataplane frames of any input type, and write_stdin as itself -- and must not type.
     */
    @Test
    void aBystanderWhoSeesOneStdinFrameCannotTypeIntoTheProcess() throws Exception {
        start();
        assertEquals("true", config("config_process", "stream_name", "shell", "command", "cat", "stdin", "true").getParam("config_status"));
        assertEquals("true", exec.executeEXEC(as(exec("run_process", "stream_name", "shell"), "api:tenant-a/owner", "user"))
                .getParam("status"));
        await(() -> engine.isRunning("shell"), 10_000, "the process");

        // a tenant member subscribed to the stream (as a dataplane client can, to read its output)
        List<Message> seen = new CopyOnWriteArrayList<>();
        listeners.put("bystander", new Object[]{"stream_name='shell'", (MessageListener) seen::add});

        // the owner types
        assertEquals("10", exec.executeEXEC(as(exec("write_stdin", "stream_name", "shell", "data", "ls"), "api:tenant-a/owner", "user"))
                .getParam("status"));
        await(() -> {
            try { return output("shell").contains("ls"); } catch (Exception e) { return false; }
        }, 10_000, "the owner's line echoed");

        // the bystander saw the output, and no stdin frame: none is sent
        Thread.sleep(200);
        for (Message m : seen) {
            String t = m.getStringProperty("type");
            assertFalse(t != null && t.startsWith("input"), "a stdin frame was on the dataplane: " + t);
        }

        // it tries every way it has
        for (String type : new String[]{"input", "input-00000000000000000000000000000000", "output", "error"}) {
            TextMessage injected = FakeJms.text();
            injected.setStringProperty("stream_name", "shell");
            injected.setStringProperty("type", type);
            injected.setText("injected by a bystander");
            publish(injected);
        }
        MsgEvent asItself = exec.executeEXEC(as(exec("write_stdin", "stream_name", "shell", "data", "injected by a bystander"),
                "api:tenant-a/bystander", "user"));
        assertEquals("not-owner", asItself.getParam("refusal"));
        MsgEvent unstamped = exec.executeEXEC(exec("write_stdin", "stream_name", "shell", "data", "injected by a bystander"));
        assertEquals("not-owner", unstamped.getParam("refusal"));
        Thread.sleep(1000);
        assertFalse(output("shell").contains("injected by a bystander"),
                "a bystander typed into the process");
        config("end_process", "stream_name", "shell");
    }

    /**
     * MED (the README's "an environment without the agent's secrets" does not hold). The scrub edits only the child's
     * own environment; the child is spawned by the agent JVM, so `ps -E -p $PPID` (macOS) or /proc/$PPID/environ
     * (Linux) hands it the agent's whole environment, CRESCO_* secrets included. Run with a CRESCO_* variable set in
     * the test JVM's environment, e.g. CRESCO_REVIEW_SECRET=s3cret mvn test.
     */
    @Test
    void aScrubbedRunnerCannotReadTheAgentsEnvironmentThroughItsParent() throws Exception {
        String secret = null;
        for (Map.Entry<String, String> e : System.getenv().entrySet()) {
            if (e.getKey().startsWith("CRESCO_") && e.getValue().length() >= 4) secret = e.getKey();
        }
        assumeTrue(secret != null, "set a CRESCO_* variable in the environment mvn runs in");
        start();
        String cmd = System.getProperty("os.name").startsWith("Mac")
                ? "ps -E -ww -o command= -p $PPID | tr ' ' '\\n'"
                : "tr '\\0' '\\n' < /proc/$PPID/environ";
        config("config_process", "stream_name", "peek", "command", cmd);
        run("peek");
        await(() -> ended("peek"), 15_000, "peek");
        final String name = secret;
        assertFalse(output("peek").stream().anyMatch(l -> l.startsWith(name + "=")),
                "runner_env=scrubbed, yet the runner read " + name + " from its parent, the agent JVM");
    }

    /** LOW. JAVA_OPTS (what launch scripts pass -D secrets in) and *_PASSWD / MYSQL_PWD pass the default scrub. */
    @Test
    void commonSecretCarryingVariablesAreScrubbed() {
        for (String name : new String[]{"JAVA_OPTS", "DB_PASSWD", "MYSQL_PWD"}) {
            assertFalse(RunnerEnvironment.keeps(RunnerEnvironment.SCRUBBED, name), name + " is handed to every runner");
        }
    }

    /** LOW. "One working directory per runner": stream names a:b and a_b map to the same directory. */
    @Test
    void twoRunnersNeverShareAWorkingDirectory() throws Exception {
        start();
        Runner one = new Runner(plugin, "true", "job:1", false);
        Runner two = new Runner(plugin, "true", "job_1", false);
        Path a = one.workDir();
        Path b = two.workDir();
        assertNotEquals(a, b, "runners job:1 and job_1 share " + a);
    }
}
