package io.cresco.executor;

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
     */
    @Test
    void aBystanderWhoSeesOneStdinFrameCannotTypeIntoTheProcess() throws Exception {
        start();
        String type = config("config_process", "stream_name", "shell", "command", "cat", "stdin", "true").getParam("stdin_type");
        assertNotNull(type);
        run("shell");
        await(() -> listeners.values().stream().anyMatch(l -> ((String) l[0]).contains(type)), 10_000, "the stdin listener");

        // a tenant member subscribed to the stream (as a dataplane client can, to read its output)
        List<Message> seen = new CopyOnWriteArrayList<>();
        listeners.put("bystander", new Object[]{"stream_name='shell'", (MessageListener) seen::add});

        TextMessage owner = FakeJms.text();
        owner.setStringProperty("stream_name", "shell");
        owner.setStringProperty("type", type);
        owner.setText("ls");
        sent.add(owner);
        publish(owner);

        String learned = null;
        for (Message m : seen) {
            String t = m.getStringProperty("type");
            if (t != null && t.startsWith("input-")) learned = t;
        }
        assertNotNull(learned, "precondition: the bystander saw the stdin frame");
        TextMessage injected = FakeJms.text();
        injected.setStringProperty("stream_name", "shell");
        injected.setStringProperty("type", learned);
        injected.setText("injected by a bystander");
        publish(injected);
        Thread.sleep(1000);
        assertFalse(output("shell").contains("injected by a bystander"),
                "a bystander typed into the process with the type it read off the dataplane");
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
