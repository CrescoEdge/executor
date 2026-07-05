package io.cresco.executor;

import io.cresco.library.plugin.PluginBuilder;
import io.cresco.library.utilities.CLogger;

import java.util.*;

public class RunnerEngine {

    private final PluginBuilder plugin;
    private final CLogger logger;

    private final Object lock = new Object();
    private final Map<String, Runner> runnerMap = new HashMap<>();

    public RunnerEngine(PluginBuilder plugin) {
        this.plugin = plugin;
        logger = plugin.getLogger(RunnerEngine.class.getName(), CLogger.Level.Info);
    }

    public boolean resetRunners() {
        boolean isReset = true;
        List<String> names;
        synchronized (lock) {
            names = new ArrayList<>(runnerMap.keySet());
        }
        for (String streamName : names) {
            if (!stopRunner(streamName)) {
                isReset = false;
            }
        }
        return isReset;
    }

    public boolean createRunner(String runCommand, String streamName, boolean asSudo, boolean metrics) {
        try {
            synchronized (lock) {
                if (runnerMap.containsKey(streamName)) {
                    logger.error("createRunner: runner already exists for stream_name " + streamName);
                    return false;
                }
                runnerMap.put(streamName, new Runner(plugin, runCommand, streamName, metrics));
            }
            return true;
        } catch (Exception ex) {
            logger.error("createRunner() " + ex.getMessage());
            return false;
        }
    }

    public boolean runRunner(String streamName) {
        try {
            synchronized (lock) {
                Runner runner = runnerMap.get(streamName);
                if (runner == null) {
                    logger.error("runRunner: no runner for stream_name " + streamName);
                    return false;
                }
                if (runner.isRunning()) {
                    logger.error("runRunner: runner " + streamName + " is already running");
                    return false;
                }
                Thread t = new Thread(runner, "executor-runner-" + streamName);
                t.setDaemon(true);
                t.start();
            }
            return true;
        } catch (Exception ex) {
            logger.error("runRunner() " + ex.getMessage());
            return false;
        }
    }

    public boolean isRunning(String streamName) {
        try {
            synchronized (lock) {
                Runner runner = runnerMap.get(streamName);
                return runner != null && runner.isRunning();
            }
        } catch (Exception ex) {
            logger.error("isRunning() " + ex.getMessage());
            return false;
        }
    }

    public boolean stopRunner(String streamName) {
        try {
            Runner runner;
            synchronized (lock) {
                // remove first so the stream_name is immediately reusable
                runner = runnerMap.remove(streamName);
            }
            if (runner != null) {
                runner.shutdown();
                return true;
            }
            return false;
        } catch (Exception ex) {
            logger.error("stopRunner() " + ex.getMessage());
            return false;
        }
    }

    public boolean isRunner(String streamName) {
        try {
            synchronized (lock) {
                return runnerMap.containsKey(streamName);
            }
        } catch (Exception ex) {
            logger.error("isRunner() " + ex.getMessage());
            return false;
        }
    }

    /** Number of configured runners (running or not). */
    public int getRunnerCount() {
        synchronized (lock) {
            return runnerMap.size();
        }
    }

    /** Number of runners currently executing a process. */
    public int getActiveCount() {
        int active = 0;
        synchronized (lock) {
            for (Runner r : runnerMap.values()) {
                if (r.isRunning()) active++;
            }
        }
        return active;
    }
}
