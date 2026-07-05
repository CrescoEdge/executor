package io.cresco.executor;


import io.cresco.library.data.TopicType;
import io.cresco.library.plugin.PluginBuilder;
import io.cresco.library.utilities.CLogger;

import io.cresco.library.data.DataPlaneService;
import jakarta.jms.DeliveryMode;
import jakarta.jms.TextMessage;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class StreamGobbler extends Thread {
    private final InputStream is;
    private final String streamName;
    private final PluginBuilder plugin;
    private final CLogger logger;
    private final String streamType;
    private final String gobblerId;

    StreamGobbler(PluginBuilder plugin, InputStream is, String streamName, String streamType, String gobblerId) {
        this.plugin = plugin;
        logger = plugin.getLogger(StreamGobbler.class.getName(), CLogger.Level.Info);
        this.is = is;
        this.streamName = streamName;
        this.streamType = streamType;
        this.gobblerId = gobblerId;
        setName("executor-gobbler-" + streamType + "-" + streamName);
        setDaemon(true);
    }

    @Override
    public void run() {
        logger.debug("StreamGobbler type=" + streamType + " started for stream_name=" + streamName);
        // stdout/stderr ride the GLOBAL dataplane sharded by stream_name so an EXTERNAL client
        // (reading GLOBAL through wsapi) can see the output — the AGENT topic is node-local only.
        DataPlaneService dps = plugin.getAgentService().getDataPlaneService();
        int shard = dps.shardFor(streamName);
        // try-with-resources: the reader closes when the process stream reaches EOF (or errors).
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            // readLine() BLOCKS until a line is available, then returns null at EOF (process exit).
            // Break on EOF — the old code spun at 500ms until the whole plugin stopped (thread leak),
            // and slept 50ms per line (capped output at ~20 lines/s). Neither is needed.
            while (plugin.isActive() && (line = br.readLine()) != null) {
                logger.debug(gobblerId + " out(" + streamName + "): " + line);
                TextMessage tm = dps.createTextMessage();
                tm.setStringProperty("stream_name", streamName);
                tm.setStringProperty("type", streamType);
                tm.setText(line);
                dps.sendMessage(TopicType.GLOBAL, tm, DeliveryMode.NON_PERSISTENT, 0, 0, shard);
            }
        } catch (Exception e) {
            logger.debug("StreamGobbler " + streamType + " for " + streamName + " ended: " + e.getMessage());
        }
        logger.debug("StreamGobbler type=" + streamType + " exited for stream_name=" + streamName);
    }
}
