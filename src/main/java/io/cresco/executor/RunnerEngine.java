package io.cresco.executor;

import io.cresco.library.plugin.PluginBuilder;
import io.cresco.library.utilities.CLogger;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class RunnerEngine {

    private PluginBuilder plugin;
    private CLogger logger;

    private AtomicBoolean lockRunners = new AtomicBoolean();

    private Map<String,Runner> runnerMap;

    public RunnerEngine(PluginBuilder plugin) {
        this.plugin = plugin;
        logger = plugin.getLogger(RunnerMetrics.class.getName(), CLogger.Level.Info);
        runnerMap = Collections.synchronizedMap(new HashMap<>());

    }

    public boolean createRunner(String runCommand, String streamName, boolean asSudo, boolean metrics) {
        boolean isCreated = false;
        try {

            synchronized (lockRunners) {
                runnerMap.put(streamName,new Runner(plugin, runCommand, streamName, metrics));
            }
            isCreated = true;

        } catch (Exception ex) {
            logger.error("createRunner() " + ex.getMessage());
        }

        return isCreated;
    }


    public boolean runRunner(String streamName) {
        boolean isRunning = false;
        try {

            synchronized (lockRunners) {
                if(runnerMap.get(streamName) != null) {
                    new Thread(runnerMap.get(streamName)).start();
                }
            }
            isRunning = true;

        } catch (Exception ex) {
            logger.error("runRunner() " + ex.getMessage());
        }

        return isRunning;
    }


    public boolean isRunning(String streamName) {
        boolean isRunning = false;
        try {

            synchronized (lockRunners) {
                if(runnerMap.get(streamName) != null) {
                   isRunning = runnerMap.get(streamName).isRunning();
                }
            }

        } catch (Exception ex) {
            logger.error("isRunning() " + ex.getMessage());
        }

        return isRunning;
    }

    public boolean stopRunner(String streamName) {
        boolean isStopped = false;
        try {

            synchronized (lockRunners) {
                if(runnerMap.get(streamName) != null) {
                    runnerMap.get(streamName).shutdown();
                }
            }
            isStopped = true;

        } catch (Exception ex) {
            logger.error("stopRunner() " + ex.getMessage());
        }

        return isStopped;
    }

    public boolean isRunner(String streamName) {
        boolean isRunner = false;
        try {

            synchronized (lockRunners) {
                if(runnerMap.get(streamName) != null) {
                    isRunner = true;
                }
            }


        } catch (Exception ex) {
            logger.error("isRunner() " + ex.getMessage());
        }

        return isRunner;
    }




}
