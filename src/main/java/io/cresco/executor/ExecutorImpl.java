package io.cresco.executor;

import io.cresco.library.messaging.MsgEvent;
import io.cresco.library.plugin.Executor;
import io.cresco.library.plugin.PluginBuilder;
import io.cresco.library.utilities.CLogger;

import java.util.UUID;

public class ExecutorImpl implements Executor {

    private PluginBuilder plugin;
    private CLogger logger;
    private RunnerEngine runnerEngine;
    private Runner runner;
    private String runCommmand;

    public ExecutorImpl(PluginBuilder pluginBuilder, RunnerEngine runnerEngine) {
        this.plugin = pluginBuilder;
        logger = plugin.getLogger(ExecutorImpl.class.getName(),CLogger.Level.Info);
        this.runnerEngine = runnerEngine;
        runCommmand = plugin.getConfig().getStringParam("runCommand");
    }

    @Override
    public MsgEvent executeCONFIG(MsgEvent incoming) {

        switch (incoming.getParam("action")) {
            case "run_process":
                logger.info("{} cmd received", incoming.getParam("cmd"));
                if (runner != null) {
                    if(runner.isRunning()) {
                        logger.error("Trying to run, but runner != null, STOP first.");
                        incoming.setParam("error", Boolean.toString(true));
                        incoming.setParam("error_msg", "Process is already running");
                        return incoming;
                    }
                    runner = null;
                }

                if(runCommmand != null) {
                    //if(incoming.getParam("command") != null) {
                    String streamName = UUID.randomUUID().toString();
                    runner = new Runner(plugin, incoming.getParam("command"), streamName);
                    new Thread(runner).start();
                    //todo do some status here
                    incoming.setParam("status", Boolean.toString(true));
                    incoming.setParam("stream_name", streamName);
                }

                return incoming;
            case "status_process":
                logger.trace("{} cmd received", incoming.getParam("cmd"));
                if (runner == null || !runner.isRunning()) {
                    incoming.setParam("status_runner", Boolean.toString(runner == null));
                    if (runner == null)
                        incoming.setParam("status_process", Boolean.toString(false));
                    else
                        incoming.setParam("status_process", Boolean.toString(runner.isRunning()));
                } else
                    incoming.setParam("status", Boolean.toString(true));
                return incoming;
            case "end_process":
                logger.trace("{} cmd received", incoming.getParam("cmd"));
                if (runner == null || !runner.isRunning()) {
                    incoming.setParam("error", Boolean.toString(true));
                    if (runner == null)
                        incoming.setParam("error_msg", "Process is not currently running");
                    else
                        incoming.setParam("error_msg", "Process could not be run");
                    return incoming;
                }
                runner.shutdown();
                runner = null;

                incoming.setParam("status", Boolean.toString(true));

                return incoming;
            default:
                logger.error("Unknown action: {}", incoming.getParam("action"));
                incoming.setParam("error", Boolean.toString(true));
                incoming.setParam("error_msg", "Unknown action  [" + incoming.getParam("action") + "]");
                return incoming;
        }

    }
    @Override
    public MsgEvent executeDISCOVER(MsgEvent incoming) {
        return null;
    }
    @Override
    public MsgEvent executeERROR(MsgEvent incoming) {
        return null;
    }
    @Override
    public MsgEvent executeINFO(MsgEvent incoming) { return null; }
    @Override
    public MsgEvent executeEXEC(MsgEvent incoming) {

        switch (incoming.getParam("action")) {
            case "run_process":
                logger.info("{} action received", incoming.getParam("action"));
                if (runner != null) {
                    if(runner.isRunning()) {
                        logger.error("Trying to run, but runner != null, STOP first.");
                        incoming.setParam("error", Boolean.toString(true));
                        incoming.setParam("error_msg", "Process is already running");
                        return incoming;
                    }
                    runner = null;
                }

                if(runCommmand != null) {
                    //if(incoming.getParam("command") != null) {
                    String streamName = UUID.randomUUID().toString();
                    runner = new Runner(plugin, incoming.getParam("command"), streamName);
                    new Thread(runner).start();
                    //todo do some status here
                    incoming.setParam("status", Boolean.toString(true));
                    incoming.setParam("stream_name", streamName);
                }

                return incoming;
            case "status_process":
                logger.trace("{} cmd received", incoming.getParam("cmd"));
                if (runner == null || !runner.isRunning()) {
                    incoming.setParam("status_runner", Boolean.toString(runner == null));
                    if (runner == null)
                        incoming.setParam("status_process", Boolean.toString(false));
                    else
                        incoming.setParam("status_process", Boolean.toString(runner.isRunning()));
                } else
                    incoming.setParam("status", Boolean.toString(true));
                return incoming;
            case "end_process":
                logger.trace("{} cmd received", incoming.getParam("cmd"));
                if (runner == null || !runner.isRunning()) {
                    incoming.setParam("error", Boolean.toString(true));
                    if (runner == null)
                        incoming.setParam("error_msg", "Process is not currently running");
                    else
                        incoming.setParam("error_msg", "Process could not be run");
                    return incoming;
                }
                runner.shutdown();
                runner = null;

                incoming.setParam("status", Boolean.toString(true));

                return incoming;
            default:
                logger.error("Unknown cmd: {}", incoming.getParam("cmd"));
                incoming.setParam("error", Boolean.toString(true));
                incoming.setParam("error_msg", "Unknown cmd  [" + incoming.getParam("cmd") + "]");
                return incoming;
        }


    }
    @Override
    public MsgEvent executeWATCHDOG(MsgEvent incoming) {
        return null;
    }
    @Override
    public MsgEvent executeKPI(MsgEvent incoming) {
        return null;
    }


}