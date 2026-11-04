package io.cresco.executor;

import io.cresco.library.capability.*;
import io.cresco.library.messaging.MsgEvent;
import io.cresco.library.plugin.Executor;
import io.cresco.library.plugin.PluginBuilder;
import io.cresco.library.utilities.CLogger;

import java.util.Base64;
import java.util.UUID;

@CrescoCapabilities(namespace = "executor", target = "plugin",
    routingParams = {"region", "agent", "pluginid"},
    summary = "Runs OS processes / shell commands (and containers via CLI) on an agent, streaming " +
              "stdout/stderr over the dataplane (stream_name) and accepting stdin through write_stdin; " +
              "optional per-process OSHI metrics.")
@CrescoActions({
    @CrescoAction(name = "config_process", roles = {"infra", "admin"}, type = "CONFIG",
        summary = "Register a process/shell runner (does not start it).",
        why = "Prepare a command to run on the edge; its stdio will ride the dataplane under stream_name.",
        params = {
            @CrescoParam(name = "stream_name", required = true, description = "unique runner id + dataplane stdio stream_name"),
            @CrescoParam(name = "command", required = true, description = "shell command to run (e.g. 'tcpdump -w - port 514', 'docker run ...')"),
            @CrescoParam(name = "metrics", type = "boolean", description = "true to collect per-process OSHI metrics"),
            @CrescoParam(name = "stdin", type = "boolean", description = "true to keep stdin open for write_stdin (an interactive command always does)")},
        returns = @CrescoReturn(name = "config_status", description = "true on success")),
    @CrescoAction(name = "run_process", roles = {"infra", "admin"}, type = "EXEC",
        summary = "Start a configured runner; its stdout/stderr stream over the dataplane as stream_name.",
        why = "Begin execution and stream live output back over the mesh.",
        params = @CrescoParam(name = "stream_name", required = true, description = "runner id"),
        returns = @CrescoReturn(name = "status", description = "true on success")),
    @CrescoAction(name = "write_stdin", type = "EXEC",
        summary = "Write to a running runner's stdin (one line of text, or bytes).",
        why = "Drive a process that reads stdin, e.g. an interactive shell. Only the caller that started the runner, an admin or infra may write.",
        params = {
            @CrescoParam(name = "stream_name", required = true, description = "runner id"),
            @CrescoParam(name = "data", description = "text to write; a newline follows unless newline=false"),
            @CrescoParam(name = "data_b64", description = "bytes to write, base64 (instead of data)"),
            @CrescoParam(name = "newline", type = "boolean", description = "append a newline to data (default true)"),
            @CrescoParam(name = "close_stdin", type = "boolean", description = "close stdin after writing (end of input)")},
        returns = @CrescoReturn(name = "status", description = "10 written; 9 refused (see refusal); 8 bad params")),
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


    // explicit insecure development mode (broker_security_enabled=false on the agent): an unstamped caller is anonymous
    private final boolean insecure = Callers.insecureMode();

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
                                incoming.setParam("stdin", Boolean.toString(r.takesStdin()));
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
                        incoming.setParam("start_status", Boolean.toString(start(streamName, incoming)));
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
                    incoming.setParam("status", Boolean.toString(start(streamName, incoming)));
                }
                return incoming;
            case "write_stdin":
                return writeStdin(incoming);
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



    /** Start a configured runner, recording who started it (the verified caller) for write_stdin. */
    private boolean start(String streamName, MsgEvent incoming) {
        Runner r = runnerEngine.get(streamName);
        if (r != null) {
            io.cresco.library.security.Caller c = Callers.of(incoming, insecure);
            r.setStartedBy(c == null || c.isAnonymous() ? null : c.getPrincipal());
        }
        return runnerEngine.runRunner(streamName);
    }

    /**
     * write_stdin: write to a running process's stdin. Only the verified caller that started the runner, or an admin
     * or infra caller, may write; stdin never comes from the dataplane, where any member of the tenant could see and
     * forge frames. With no verified caller the write is refused (development mode excepted).
     */
    private MsgEvent writeStdin(MsgEvent incoming) {
        String streamName = incoming.getParam("stream_name");
        Runner r = streamName == null ? null : runnerEngine.get(streamName);
        if (r == null) {
            incoming.setParam("status", "8");
            incoming.setParam("status_desc", "no runner for stream_name " + streamName);
            return incoming;
        }
        io.cresco.library.security.Caller c = Callers.of(incoming, insecure);
        boolean allowed = c != null && (Callers.hasRole(c, "admin") || Callers.hasRole(c, "infra")
                || (r.getStartedBy() != null && r.getStartedBy().equals(c.getPrincipal())));
        if (!allowed) {
            logger.warn("write_stdin to " + streamName + " refused for " + (c == null ? "an unverified caller" : c));
            incoming.setParam("status", "9");
            incoming.setParam("status_desc", "refused: only the caller that started the runner, an admin or infra may write its stdin");
            incoming.setParam("refusal", "not-owner");
            return incoming;
        }
        byte[] data;
        try {
            if (incoming.getParam("data_b64") != null) {
                data = Base64.getDecoder().decode(incoming.getParam("data_b64"));
            } else {
                String text = incoming.getParam("data");
                if (text == null) text = "";
                if (!"false".equalsIgnoreCase(incoming.getParam("newline")) && incoming.getParam("data") != null) text = text + "\n";
                data = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (IllegalArgumentException bad) {
            incoming.setParam("status", "8");
            incoming.setParam("status_desc", "data_b64 is not base64");
            return incoming;
        }
        if (data.length > Runner.MAX_STDIN_WRITE) {
            incoming.setParam("status", "8");
            incoming.setParam("status_desc", "at most " + Runner.MAX_STDIN_WRITE + " bytes per write_stdin");
            return incoming;
        }
        // never what was written: its size, and to which runner
        logger.debug("write_stdin: " + data.length + " byte(s) to " + streamName);
        Runner.StdinResult res = r.writeStdin(data, "true".equalsIgnoreCase(incoming.getParam("close_stdin")));
        switch (res) {
            case WRITTEN:
                incoming.setParam("status", "10");
                incoming.setParam("status_desc", "written");
                break;
            case NO_STDIN:
                incoming.setParam("status", "9");
                incoming.setParam("status_desc", "this runner takes no stdin (config_process stdin=true)");
                incoming.setParam("refusal", "no-stdin");
                break;
            case NOT_RUNNING:
                incoming.setParam("status", "9");
                incoming.setParam("status_desc", "the process is not running, or its stdin is closed");
                incoming.setParam("refusal", "not-running");
                break;
            default:
                incoming.setParam("status", "7");
                incoming.setParam("status_desc", "stdin could not be written");
        }
        return incoming;
    }

}
