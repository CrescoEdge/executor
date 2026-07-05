package io.cresco.executor;

import io.cresco.library.plugin.PluginBuilder;
import org.apache.felix.hc.api.HealthCheck;
import org.apache.felix.hc.api.Result;

/**
 * Central health for the executor. Registered as an {@code org.apache.felix.hc.api.HealthCheck}
 * OSGi service (name "executor", tag "local") so the controller's CrescoHealthExecutor discovers
 * and schedules it alongside the built-in broker/db/disk/memory/plugins checks — the same Felix
 * Health Check system the rest of Cresco uses. Self-guards while the plugin is still coming up.
 */
public class ExecutorHealthCheck implements HealthCheck {

    private final PluginBuilder plugin;
    private final RunnerEngine runnerEngine;

    public ExecutorHealthCheck(PluginBuilder plugin, RunnerEngine runnerEngine) {
        this.plugin = plugin;
        this.runnerEngine = runnerEngine;
    }

    @Override
    public Result execute() {
        try {
            if (plugin == null || !plugin.isActive() || runnerEngine == null) {
                return new Result(Result.Status.TEMPORARILY_UNAVAILABLE, "executor not active");
            }
            int active = runnerEngine.getActiveCount();
            int configured = runnerEngine.getRunnerCount();
            return new Result(Result.Status.OK,
                    "executor OK: " + active + " running / " + configured + " configured runner(s)");
        } catch (Exception ex) {
            return new Result(Result.Status.WARN, "executor health error: " + ex.getMessage());
        }
    }
}
