package io.cresco.executor;

import com.google.gson.Gson;
import io.cresco.library.metrics.CMetric;
import io.cresco.library.metrics.MeasurementEngine;
import io.cresco.library.plugin.PluginBuilder;

/**
 * Central metrics for the executor. Uses the unified {@link MeasurementEngine} (Micrometer via
 * CrescoMeterRegistry) — the same model sysinfo/repo use — so the gauges below are aggregated
 * fabric-wide by the controller's {@code getmetricinventory} fan-out (which calls this plugin's
 * {@code getmetrics} action). This replaces the old ad-hoc dataplane "perf" MapMessages as the
 * inventory-level signal (per-runner live OSHI telemetry still streams separately via RunnerMetrics).
 */
public class ExecutorMetrics {

    private final RunnerEngine runnerEngine;
    private final MeasurementEngine me;
    private final Gson gson = new Gson();

    public ExecutorMetrics(PluginBuilder plugin, RunnerEngine runnerEngine) {
        this.runnerEngine = runnerEngine;
        this.me = new MeasurementEngine(plugin);
        me.setGauge("executor.runners.configured", "configured runners (running or not)", "executor", CMetric.MeasureClass.GAUGE_INT);
        me.setGauge("executor.runners.active", "runners currently executing a process", "executor", CMetric.MeasureClass.GAUGE_INT);
    }

    /** Pull current values into the gauges. Cheap (in-memory counts). */
    public void refresh() {
        me.updateIntGauge("executor.runners.configured", runnerEngine.getRunnerCount());
        me.updateIntGauge("executor.runners.active", runnerEngine.getActiveCount());
    }

    /** getmetrics payload — the standard getAllMetrics() shape the metric inventory expects. */
    public String getMetricsJson() {
        refresh();
        return gson.toJson(me.getAllMetrics());
    }

    public void shutdown() {
        try { me.shutdown(); } catch (Exception ignore) { }
    }
}
