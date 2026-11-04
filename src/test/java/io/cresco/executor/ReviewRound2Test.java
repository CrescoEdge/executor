package io.cresco.executor;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Adversarial review, round 2 (review/plugins-20261104), of executor 4658030. Fails on that commit. Local only.
 */
class ReviewRound2Test extends ExecutorTest {

    /**
     * LOW. "Two runners never share a working directory": stream names that differ only in case (Job, job) map to one
     * directory on a case-insensitive filesystem (macOS, Windows).
     */
    @Test
    void streamNamesThatDifferOnlyInCaseNeverShareAWorkingDirectory() throws Exception {
        start();
        Path probe = Files.createDirectories(tmp.resolve("CaseProbe"));
        assumeTrue(Files.exists(tmp.resolve("caseprobe")), "needs a case-insensitive filesystem");
        Runner upper = new Runner(plugin, "true", "Job", false);
        Runner lower = new Runner(plugin, "true", "job", false);
        Path a = upper.workDir();
        Path b = lower.workDir();
        assertFalse(Files.isSameFile(a, b), "runners Job and job share " + a.toRealPath());
    }
}
