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
    private String gobblerId;

    StreamGobbler(PluginBuilder plugin, InputStream is, String streamName, String streamType, String gobblerId) {
        this.plugin = plugin;
        logger = plugin.getLogger(StreamGobbler.class.getName(),CLogger.Level.Info);
        this.is = is;
        this.streamName = streamName;
        this.streamType = streamType;
        this.gobblerId = gobblerId;
    }

    @Override
    public void run() {
        logger.debug("StreamGobbler Type=" + streamType + " started for stream_name=" + streamName);
        try {
            InputStreamReader isr = new InputStreamReader(is);

            BufferedReader br = new BufferedReader(isr);
            String line;
            while(plugin.isActive()) {
                if ((line = br.readLine()) != null) {

                    logger.debug(gobblerId + " OUTGOING FROM EXEC PLUGIN: " + line);
                    TextMessage tm = plugin.getAgentService().getDataPlaneService().createTextMessage();
                    tm.setStringProperty("stream_name", streamName);
                    tm.setStringProperty("type", streamType);
                    tm.setText(line);
                    plugin.getAgentService().getDataPlaneService().sendMessage(TopicType.AGENT, tm);
                    //logger.info("Output: {}", line);

                    Thread.sleep(50);
                } else {
                    Thread.sleep(500);
                }
            }
            br.close();
            isr.close();
        } catch (IOException e) {
            logger.error("run() : {}", e.getMessage());
        } catch (Exception e) {
            logger.error("run() : Interrupted : {}", e.getMessage());
        }
    }
}