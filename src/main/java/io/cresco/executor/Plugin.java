package io.cresco.executor;


import io.cresco.library.agent.AgentService;
import io.cresco.library.messaging.MsgEvent;
import io.cresco.library.plugin.Executor;
import io.cresco.library.plugin.PluginBuilder;
import io.cresco.library.plugin.PluginService;
import io.cresco.library.utilities.CLogger;
import org.apache.felix.hc.api.HealthCheck;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.annotations.*;

import java.util.Dictionary;
import java.util.Hashtable;
import java.util.Map;

@Component(
        service = { PluginService.class },
        scope=ServiceScope.PROTOTYPE,
        configurationPolicy = ConfigurationPolicy.REQUIRE,
        reference=@Reference(name="io.cresco.library.agent.AgentService", service=AgentService.class)
)

public class Plugin implements PluginService {

    public BundleContext context;
    public static  PluginBuilder pluginBuilder;
    private Executor executor;
    private CLogger logger;
    private Map<String, Object> map;
    public String myname;
    public RunnerEngine runnerEngine;
    private ExecutorMetrics executorMetrics;
    private ServiceRegistration<HealthCheck> healthReg;

    @Activate
    void activate(BundleContext context, Map<String, Object> map) {

        this.context = context;
        this.map = map;
        myname = "this is my name";

    }


    @Override
    public boolean isActive() {
        return pluginBuilder.isActive();
    }

    @Override
    public void setIsActive(boolean isActive) {
        pluginBuilder.setIsActive(isActive);
    }


    @Modified
    void modified(BundleContext context, Map<String, Object> map) {
        System.out.println("Modified Config Map PluginID:" + (String) map.get("pluginID"));
    }

    @Override
    public boolean inMsg(MsgEvent incoming) {
        pluginBuilder.msgIn(incoming);
        return true;
    }

    @Deactivate
    void deactivate(BundleContext context, Map<String,Object> map) {

        isStopped();
        this.context = null;
        this.map = null;

    }

    @Override
    public boolean isStarted() {

        //System.out.println("Started PluginID:" + (String) map.get("pluginID"));

        try {
            pluginBuilder = new PluginBuilder(this.getClass().getName(), context, map);
            this.logger = pluginBuilder.getLogger(Plugin.class.getName(), CLogger.Level.Info);

            logger.info("Started Executor PluginID:" + map.get("pluginID"));

            this.runnerEngine = new RunnerEngine(pluginBuilder);
            this.executorMetrics = new ExecutorMetrics(pluginBuilder, runnerEngine);

            this.executor = new ExecutorImpl(pluginBuilder, runnerEngine, executorMetrics);
            pluginBuilder.setExecutor(executor);

            registerHealthCheck();

            while (!pluginBuilder.getAgentService().getAgentState().isActive()) {
                logger.info("Plugin " + pluginBuilder.getPluginID() + " waiting on Agent Init");
                Thread.sleep(1000);
            }

            pluginBuilder.setIsActive(true);

            String stream_name = pluginBuilder.getConfig().getStringParam("stream_name");
            String command = pluginBuilder.getConfig().getStringParam("command");
            boolean metrics = pluginBuilder.getConfig().getBooleanParam("metrics",false);

            if((stream_name != null) && (command != null)) {
                logger.info("Local Config Found.  Starting runner");
                runnerEngine.createRunner(command,stream_name,true, metrics);
                runnerEngine.runRunner(stream_name);
            }

            //send a bunch of messages
            //MessageSender messageSender = new MessageSender(pluginBuilder);
            //new Thread(messageSender).start();
            //logger.info("Started Skeleton Example Message Sender");

            //set plugin active
            return true;

        } catch (Exception ex) {
            ex.printStackTrace();
            return false;
        }
    }

    /** Register the executor's Felix HealthCheck so CrescoHealthExecutor discovers it. Best-effort. */
    private void registerHealthCheck() {
        try {
            Dictionary<String, Object> props = new Hashtable<>();
            props.put(HealthCheck.NAME, "executor");
            props.put(HealthCheck.TAGS, new String[]{"local"});
            healthReg = context.registerService(HealthCheck.class,
                    new ExecutorHealthCheck(pluginBuilder, runnerEngine), props);
            logger.info("Registered executor HealthCheck (Felix HC)");
        } catch (Throwable t) {
            // health is best-effort: a missing hc.api bundle must never break the executor
            if (logger != null) logger.warn("Could not register executor HealthCheck: " + t.getMessage());
        }
    }

    @Override
    public boolean isStopped() {
        try {
            if (healthReg != null) { healthReg.unregister(); healthReg = null; }
        } catch (Exception ex) {
            if (logger != null) logger.error("health unregister error: " + ex.getMessage());
        }
        try {
            // stop every running process (and its stdio/metrics threads) so nothing is orphaned
            if (runnerEngine != null) {
                runnerEngine.resetRunners();
            }
        } catch (Exception ex) {
            if (logger != null) logger.error("isStopped resetRunners error: " + ex.getMessage());
        }
        try {
            if (executorMetrics != null) executorMetrics.shutdown();
        } catch (Exception ex) {
            if (logger != null) logger.error("metrics shutdown error: " + ex.getMessage());
        }
        pluginBuilder.setExecutor(null);
        pluginBuilder.setIsActive(false);
        return true;
    }
}