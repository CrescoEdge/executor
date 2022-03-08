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


    public ExecutorImpl(PluginBuilder pluginBuilder, RunnerEngine runnerEngine) {
        this.plugin = pluginBuilder;
        logger = plugin.getLogger(ExecutorImpl.class.getName(),CLogger.Level.Info);
        this.runnerEngine = runnerEngine;

    }

    @Override
    public MsgEvent executeCONFIG(MsgEvent incoming) {

        String streamName = incoming.getParam("stream_name");

        switch (incoming.getParam("action")) {
            case "config_process":
                logger.debug("{} command received", incoming.getParam("command"));

                if(streamName != null) {
                    if(runnerEngine.isRunner(streamName)) {
                            logger.error("Trying to create runner, but runner != null, STOP first.");
                            incoming.setParam("error", Boolean.toString(true));
                            incoming.setParam("error_msg", "Runner already exist stream_name: " + streamName);
                            incoming.setParam("config_status", Boolean.toString(false));
                    } else {
                        if(incoming.getParam("command") != null) {
                            if(incoming.getParam("metrics") != null) {
                                runnerEngine.createRunner(incoming.getParam("command"),streamName,true, Boolean.getBoolean(incoming.getParam("metrics")));
                            } else {
                                runnerEngine.createRunner(incoming.getParam("command"),streamName,true,false);
                            }
                            incoming.setParam("config_status", Boolean.toString(true));
                        } else {
                            logger.error("Must provide command");
                            incoming.setParam("error", Boolean.toString(true));
                            incoming.setParam("error_msg", "Runner already exist stream_name: " + streamName);
                            incoming.setParam("config_status", Boolean.toString(false));
                        }
                    }
                } else {
                    logger.error("Must provide stream_name");
                    incoming.setParam("error", Boolean.toString(true));
                    incoming.setParam("error_msg", "Runner already exist stream_name: " + streamName);
                    incoming.setParam("config_status", Boolean.toString(false));
                }

                return incoming;


            case "status_process":

                if(streamName != null) {
                    if (runnerEngine.isRunner(streamName)) {
                        incoming.setParam("run_status", Boolean.toString(runnerEngine.isRunning(streamName)));
                    } else {
                        incoming.setParam("error_msg", "stream_name: " + streamName + " not found.");
                        incoming.setParam("run_status", Boolean.toString(false));
                    }
                } else {
                    incoming.setParam("error_msg", "stream_name = null");
                    incoming.setParam("run_status", Boolean.toString(false));
                }

                return incoming;


            case "start_process":

                if(streamName != null) {
                    if (runnerEngine.isRunner(streamName)) {
                        incoming.setParam("start_status", Boolean.toString(runnerEngine.runRunner(streamName)));
                    } else {
                        incoming.setParam("error_msg", "stream_name: " + streamName + " not found.");
                        incoming.setParam("start_status", Boolean.toString(false));
                    }
                } else {
                    incoming.setParam("error_msg", "stream_name = null");
                    incoming.setParam("start_status", Boolean.toString(false));
                }

                return incoming;

            case "end_process":
                if(streamName != null) {
                    if (runnerEngine.isRunner(streamName)) {
                        incoming.setParam("end_status", Boolean.toString(runnerEngine.stopRunner(streamName)));
                    } else {
                        incoming.setParam("error_msg", "stream_name: " + streamName + " not found.");
                        incoming.setParam("end_status", Boolean.toString(false));
                    }
                } else {
                    incoming.setParam("error_msg", "stream_name = null");
                    incoming.setParam("end_status", Boolean.toString(false));
                }
                return incoming;

            case "reset_runners":

                incoming.setParam("reset_status", Boolean.toString(runnerEngine.resetRunners()));

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

        String streamName = incoming.getParam("stream_name");

        switch (incoming.getParam("action")) {
            case "run_process":
                logger.debug("{} command received", incoming.getParam("command"));

                if(streamName != null) {
                    if(runnerEngine.isRunner(streamName)) {
                        if(runnerEngine.isRunning(streamName)) {
                            logger.error("Trying to run, but runner != null, STOP first.");
                            incoming.setParam("error", Boolean.toString(true));
                            incoming.setParam("error_msg", "Process is already running");
                            incoming.setParam("status", Boolean.toString(false));
                        } else {
                            runnerEngine.runRunner(streamName);
                            incoming.setParam("status", Boolean.toString(true));
                        }
                    }
                }
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