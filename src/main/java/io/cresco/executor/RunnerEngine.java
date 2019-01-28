package io.cresco.executor;

import org.osgi.service.cm.Configuration;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class RunnerEngine {

    private AtomicBoolean lockRunners = new AtomicBoolean();

    private Map<String,Configuration> runnerMap;

    public RunnerEngine() {

        runnerMap = Collections.synchronizedMap(new HashMap<>());

    }

    String createRunner(String runCommand, boolean asSudo) {
        String streamName = null;
        try {

        } catch (Exception ex) {

        }

        return streamName;
    }


}
