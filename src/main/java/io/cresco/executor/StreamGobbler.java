package io.cresco.executor;


import io.cresco.library.data.TopicType;
import io.cresco.library.plugin.PluginBuilder;
import io.cresco.library.utilities.CLogger;

import javax.jms.TextMessage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

public class StreamGobbler extends Thread {
    private InputStream is;
    private String streamName;
    private PluginBuilder plugin;
    private CLogger logger;
    private String streamType;

    StreamGobbler(PluginBuilder plugin, InputStream is, String streamName, String streamType) {
        this.plugin = plugin;
        logger = plugin.getLogger(StreamGobbler.class.getName(),CLogger.Level.Info);
        this.is = is;
        this.streamName = streamName;
        this.streamType = streamType;

    }

    @Override
    public void run() {
        logger.info("StreamGobbler Type=" + streamType + " started for stream_name=" + streamName);
        try {
            InputStreamReader isr = new InputStreamReader(is);
            BufferedReader br = new BufferedReader(isr);
            String line;
            while ( ( line = br.readLine() ) != null ) {

                logger.trace("Output: {}", line);

                TextMessage tm = plugin.getAgentService().getDataPlaneService().createTextMessage();
                tm.setStringProperty("stream_name", streamName);
                tm.setStringProperty("type", "log");
                tm.setText(line);
                plugin.getAgentService().getDataPlaneService().sendMessage(TopicType.AGENT,tm);

                /*
                logger.debug("Output: {}", line);
                Map<String, String> params = new HashMap<>();
                params.put("src_region", plugin.getRegion());
                params.put("src_agent", plugin.getAgent());
                params.put("src_plugin", plugin.getPluginID());
                params.put("dst_region", dstRegion);
                params.put("dst_agent", dstAgent);
                params.put("dst_plugin", dstPlugin);
                params.put("ts", Long.toString(new Date().getTime()));
                params.put("cmd", "execution_log");
                params.put("exchange", exchangeID);
                params.put("log", "[" + new Date() + "] " + line);
                plugin.sendMsgEvent(new MsgEvent(MsgEvent.Type.EXEC, plugin.getRegion(), plugin.getAgent(),
                        plugin.getPluginID(), params));
                */
                Thread.sleep(50);
            }
            br.close();
        } catch (IOException e) {
            logger.error("run() : {}", e.getMessage());
        } catch (Exception e) {
            logger.error("run() : Interrupted : {}", e.getMessage());
        }
    }
}