package io.cresco.executor;

import io.cresco.library.capability.*;
import io.cresco.library.messaging.MsgEvent;
import io.cresco.library.plugin.Executor;
import io.cresco.library.plugin.PluginBuilder;
import io.cresco.library.utilities.CLogger;

import java.util.UUID;

@CrescoCapabilities(namespace = "executor", target = "plugin",
    routingParams = {"region", "agent", "pluginid"},
    summary = "Runs OS processes / shell commands (and containers via CLI) on an agent, streaming " +
              "stdout/stderr over the dataplane (stream_name) and accepting stdin over the dataplane; " +
              "optional per-process OSHI metrics.")
@CrescoActions({
    @CrescoAction(name = "config_process", roles = {"infra", "admin"}, type = "CONFIG",
        summary = "Register a process/shell runner (does not start it).",
        why = "Prepare a command to run on the edge; its stdio will ride the dataplane under stream_name.",
        params = {
            @CrescoParam(name = "stream_name", required = true, description = "unique runner id + dataplane stdio stream_name"),
            @CrescoParam(name = "command", required = true, description = "shell command to run (e.g. 'tcpdump -w - port 514', 'docker run ...')"),
            @CrescoParam(name = "metrics", type = "boolean", description = "true to collect per-process OSHI metrics"),
            @CrescoParam(name = "stdin", type = "boolean", description = "true to take stdin over the dataplane (an interactive command always does)")},
        returns = {@CrescoReturn(name = "config_status", description = "true on success"),
                   @CrescoReturn(name = "stdin_type", description = "the dataplane type stdin must carry (input_id), when stdin is taken")}),
    @CrescoAction(name = "run_process", roles = {"infra", "admin"}, type = "EXEC",
        summary = "Start a configured runner; its stdout/stderr stream over the dataplane as stream_name.",
        why = "Begin execution and stream live output back over the mesh.",
        params = @CrescoParam(name = "stream_name", required = true, description = "runner id"),
        returns = @CrescoReturn(name = "status", description = "true on success")),
    @CrescoAction(name = "start_process", roles = {"infra", "admin"}, type = "CONFIG",
        summary = "Start a configured runner (CONFIG channel variant of run_process).",
        why = "Begin execution of a prepared runner.",
        params = @CrescoParam(name = "stream_name", required = true, description = "runner id"),
        returns = @CrescoReturn(name = "start_status", description = "true on success")),
    @CrescoAction(name = "status_process", type = "CONFIG",
        summary = "Check whether a runner is currently running.",
        why = "Poll process liveness.",
        params = @CrescoParam(name = "stream_name", required = true, description = "runner id"),
        returns = @CrescoReturn(name = "run_status", description = "true if running")),
    @CrescoAction(name = "end_process", roles = {"infra", "admin"}, type = "CONFIG",
        summary = "Stop/kill a runner.",
        why = "Terminate a running process.",
        params = @CrescoParam(name = "stream_name", required = true, description = "runner id"),
        returns = @CrescoReturn(name = "end_status", description = "true on success")),
    @CrescoAction(name = "reset_runners", roles = {"infra", "admin"}, type = "CONFIG",
        summary = "Stop all runners on this executor.",
        why = "Tear down every process this plugin is running.",
        returns = @CrescoReturn(name = "reset_status", description = "true on success")),
    @CrescoAction(name = "getmetrics", type = "EXEC",
        summary = "Return this plugin's live metrics (active/configured runner counts) as MeasurementEngine gauges JSON.",
        why = "Feeds the fabric-wide getmetricinventory; unified Micrometer metrics for the executor.",
        returns = @CrescoReturn(name = "metrics", type = "object", description = "getAllMetrics() JSON")),
    @CrescoAction(name = "getcapabilities", type = "EXEC",
        summary = "Return this plugin's self-describing capability document (its message actions as LLM tool specs).",
        why = "Discovery: lets a client/LLM learn what this plugin can do and how to call it.",
        returns = @CrescoReturn(name = "capabilities", type = "object", description = "CapabilityDocument JSON"))
})
public class ExecutorImpl implements Executor {

    private PluginBuilder plugin;
    private CLogger logger;
    private RunnerEngine runnerEngine;
    private ExecutorMetrics executorMetrics;


    public ExecutorImpl(PluginBuilder pluginBuilder, RunnerEngine runnerEngine, ExecutorMetrics executorMetrics) {
        this.plugin = pluginBuilder;
        logger = plugin.getLogger(ExecutorImpl.class.getName(),CLogger.Level.Info);
        this.runnerEngine = runnerEngine;
        this.executorMetrics = executorMetrics;

    }

    @Override
    public MsgEvent executeCONFIG(MsgEvent incoming) {

        String streamName = incoming.getParam("stream_name");
        String action = incoming.getParam("action");
        if (action == null) {
            incoming.setParam("error", Boolean.toString(true));
            incoming.setParam("error_msg", "no action provided");
            return incoming;
        }

        switch (action) {
            case "config_process":
                // the command itself is never logged: it may carry credentials
                logger.debug("config_process for stream_name " + streamName);

                if(streamName != null) {
                    if (!Runner.STREAM_NAME.matcher(streamName).matches()) {
                        incoming.setParam("error", Boolean.toString(true));
                        incoming.setParam("error_msg", "stream_name must match " + Runner.STREAM_NAME.pattern());
                        incoming.setParam("config_status", Boolean.toString(false));
                    } else if(runnerEngine.isRunner(streamName)) {
                            logger.error("Trying to create runner, but runner != null, STOP first.");
                            incoming.setParam("error", Boolean.toString(true));
                            incoming.setParam("error_msg", "Runner already exist stream_name: " + streamName);
                            incoming.setParam("config_status", Boolean.toString(false));
                    } else {
                        if(incoming.getParam("command") != null) {
                            boolean metrics = Boolean.parseBoolean(incoming.getParam("metrics"));
                            boolean stdin = Boolean.parseBoolean(incoming.getParam("stdin"));
                            Runner r = runnerEngine.create(incoming.getParam("command"), streamName, metrics, stdin);
                            if (r == null) {
                                incoming.setParam("error", Boolean.toString(true));
                                incoming.setParam("error_msg", "runner not created for stream_name: " + streamName);
                                incoming.setParam("config_status", Boolean.toString(false));
                            } else {
                                // only the caller that configured the runner learns the type its stdin must carry
                                if (r.getStdinType() != null) incoming.setParam("stdin_type", r.getStdinType());
                                incoming.setParam("config_status", Boolean.toString(true));
                            }
                        } else {
                            logger.error("Must provide command");
                            incoming.setParam("error", Boolean.toString(true));
                            incoming.setParam("error_msg", "Must provide command");
                            incoming.setParam("config_status", Boolean.toString(false));
                        }
                    }
                } else {
                    logger.error("Must provide stream_name");
                    incoming.setParam("error", Boolean.toString(true));
                    incoming.setParam("error_msg", "Must provide stream_name");
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
        String action = incoming.getParam("action");
        if (action == null) {
            incoming.setParam("error", Boolean.toString(true));
            incoming.setParam("error_msg", "no action provided");
            return incoming;
        }

        switch (action) {
            case "run_process":

                if(streamName == null) {
                    incoming.setParam("error", Boolean.toString(true));
                    incoming.setParam("error_msg", "Must provide stream_name");
                    incoming.setParam("status", Boolean.toString(false));
                } else if(!runnerEngine.isRunner(streamName)) {
                    incoming.setParam("error", Boolean.toString(true));
                    incoming.setParam("error_msg", "stream_name: " + streamName + " not configured (config_process first)");
                    incoming.setParam("status", Boolean.toString(false));
                } else if(runnerEngine.isRunning(streamName)) {
                    logger.error("Trying to run, but runner is already running, STOP first.");
                    incoming.setParam("error", Boolean.toString(true));
                    incoming.setParam("error_msg", "Process is already running");
                    incoming.setParam("status", Boolean.toString(false));
                } else {
                    incoming.setParam("status", Boolean.toString(runnerEngine.runRunner(streamName)));
                }
                return incoming;
            case "getmetrics":
                incoming.setParam("metrics", executorMetrics.getMetricsJson());
                incoming.setParam("status", "10");
                return incoming;
            case "getcapabilities":
                return CapabilityResponder.respond(incoming, this);
            default:
                logger.error("Unknown action: {}", action);
                incoming.setParam("error", Boolean.toString(true));
                incoming.setParam("error_msg", "Unknown action  [" + action + "]");
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