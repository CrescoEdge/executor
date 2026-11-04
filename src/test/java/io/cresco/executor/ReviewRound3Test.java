package io.cresco.executor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial review, round 3 (review/plugins-20261104), of executor d234c98. Fails on that commit. Local only.
 */
class ReviewRound3Test extends ExecutorTest {

    /**
     * LOW. runner_user is "the only real boundary" (README), yet it accepts root (and any uid-0 name): a typo or a
     * convenience setting turns every config_process into a root shell through the agent's sudoers rule, and the sudo
     * kill path then kills as root too. It should refuse root.
     */
    @Test
    void runnerUserIsNeverRoot() {
        start("runner_user", "root");
        Runner r = new Runner(plugin, "id", "asroot", false);
        assertThrows(IllegalStateException.class, r::runnerUser,
                "runner_user=root is accepted: commands would run as root via sudo");
    }
}
