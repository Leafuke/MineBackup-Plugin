package org.leafuke.mineBackupPlugin.sidecar;

import org.leafuke.mineBackupPlugin.RestartRelayStore;
import org.leafuke.mineBackupPlugin.knotlink.SignalSubscriber;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public final class RestartSidecarMain {
    private static final String BROADCAST_APP_ID = "0x00000020";
    private static final String BROADCAST_SIGNAL_ID = "0x00000020";

    private RestartSidecarMain() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> arguments = parseArgs(args);
        Path dataDirectory = Path.of(require(arguments, "data-dir"));
        Path workDirectory = Path.of(require(arguments, "work-dir"));
        long parentPid = Long.parseLong(require(arguments, "parent-pid"));
        int relayTimeoutSeconds = Integer.parseInt(require(arguments, "relay-timeout-seconds"));
        String sessionId = require(arguments, "session-id");
        String restartScript = require(arguments, "restart-script");

        AtomicLong relaySequence = new AtomicLong(0L);
        SignalSubscriber subscriber = new SignalSubscriber(BROADCAST_APP_ID, BROADCAST_SIGNAL_ID);
        subscriber.setSignalListener(payload -> {
            if (!shouldRelayPayload(payload)) {
                return;
            }
            try {
                RestartRelayStore.appendRelayEvent(dataDirectory, relaySequence.incrementAndGet(), payload);
            } catch (Exception e) {
                System.err.println("[MineBackup-Sidecar] Failed to persist relay event: " + e.getMessage());
            }
        });

        Thread subscriberThread = new Thread(subscriber::start, "minebackup-sidecar-subscriber");
        subscriberThread.setDaemon(true);
        subscriberThread.start();

        RestartRelayStore.markSidecarReady(dataDirectory, sessionId, ProcessHandle.current().pid());
        waitForParentExit(parentPid);
        launchRestartScript(workDirectory, restartScript);

        long deadline = Instant.now().toEpochMilli() + relayTimeoutSeconds * 1000L;
        while (Instant.now().toEpochMilli() < deadline) {
            try {
                Thread.sleep(500L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        subscriber.stop();
    }

    private static void waitForParentExit(long parentPid) {
        while (true) {
            ProcessHandle handle = ProcessHandle.of(parentPid).orElse(null);
            if (handle == null || !handle.isAlive()) {
                return;
            }
            try {
                Thread.sleep(250L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void launchRestartScript(Path workDirectory, String restartScript) throws Exception {
        Path resolvedScript = workDirectory.resolve(restartScript).normalize();
        ProcessBuilder builder;
        String osName = System.getProperty("os.name", "").toLowerCase();
        if (osName.contains("win")) {
            builder = new ProcessBuilder("cmd.exe", "/c", resolvedScript.toString());
        } else {
            builder = new ProcessBuilder("sh", resolvedScript.toString());
        }
        builder.directory(workDirectory.toFile());
        builder.start();
    }

    private static boolean shouldRelayPayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return false;
        }
        if ("minebackup save".equals(payload)) {
            return false;
        }

        Map<String, String> eventData = parsePayload(payload);
        String eventType = eventData.get("event");
        return "restore_finished".equals(eventType)
                || "restore_success".equals(eventType)
                || "rejoin_world".equals(eventType)
                || "handshake".equals(eventType);
    }

    private static Map<String, String> parsePayload(String payload) {
        Map<String, String> dataMap = new HashMap<>();
        for (String pair : payload.split(";")) {
            String[] keyValue = pair.split("=", 2);
            if (keyValue.length == 2) {
                dataMap.put(keyValue[0].trim(), keyValue[1].trim());
            }
        }
        return dataMap;
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> arguments = new HashMap<>();
        for (int i = 0; i < args.length - 1; i += 2) {
            String key = args[i];
            if (!key.startsWith("--")) {
                continue;
            }
            arguments.put(key.substring(2), args[i + 1]);
        }
        return arguments;
    }

    private static String require(Map<String, String> arguments, String key) {
        String value = arguments.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required argument: " + key);
        }
        return value;
    }
}
