package org.leafuke.mineBackupPlugin;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

public final class RestartRelayStore {
    private static final String SESSION_FILE = "restart-session.properties";
    private static final String RELAY_FILE = "restart-relay.log";
    private static final String READY_FILE = "restart-sidecar.ready";

    private RestartRelayStore() {
    }

    public static Session createSession(Path dataDirectory, String restartScriptPath, int relayTimeoutSeconds)
            throws IOException {
        Files.createDirectories(dataDirectory);

        Session session = new Session(
                UUID.randomUUID().toString(),
                Instant.now().toEpochMilli(),
                relayTimeoutSeconds,
                restartScriptPath
        );

        writeSession(dataDirectory, session);
        Files.deleteIfExists(getRelayPath(dataDirectory));
        Files.deleteIfExists(getReadyPath(dataDirectory));
        return session;
    }

    public static void writeSession(Path dataDirectory, Session session) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("session.id", session.id());
        properties.setProperty("session.created_at", Long.toString(session.createdAtMillis()));
        properties.setProperty("session.relay_timeout_seconds", Integer.toString(session.relayTimeoutSeconds()));
        properties.setProperty("session.restart_script_path", session.restartScriptPath());
        try (BufferedWriter writer = Files.newBufferedWriter(
                getSessionPath(dataDirectory), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            properties.store(writer, "MineBackup restart relay session");
        }
    }

    public static Session readSession(Path dataDirectory) {
        Path sessionPath = getSessionPath(dataDirectory);
        if (!Files.exists(sessionPath)) {
            return null;
        }

        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(sessionPath, StandardCharsets.UTF_8)) {
            properties.load(reader);
            return new Session(
                    properties.getProperty("session.id", ""),
                    Long.parseLong(properties.getProperty("session.created_at", "0")),
                    Integer.parseInt(properties.getProperty("session.relay_timeout_seconds", "0")),
                    properties.getProperty("session.restart_script_path", "")
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    public static void markSidecarReady(Path dataDirectory, String sessionId, long processId) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("session.id", sessionId);
        properties.setProperty("sidecar.pid", Long.toString(processId));
        properties.setProperty("sidecar.ready_at", Long.toString(Instant.now().toEpochMilli()));
        try (BufferedWriter writer = Files.newBufferedWriter(
                getReadyPath(dataDirectory), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            properties.store(writer, "MineBackup sidecar ready marker");
        }
    }

    public static boolean isSidecarReady(Path dataDirectory, String sessionId) {
        Path readyPath = getReadyPath(dataDirectory);
        if (!Files.exists(readyPath)) {
            return false;
        }

        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(readyPath, StandardCharsets.UTF_8)) {
            properties.load(reader);
            return sessionId.equals(properties.getProperty("session.id"));
        } catch (Exception ignored) {
            return false;
        }
    }

    public static synchronized void appendRelayEvent(Path dataDirectory, long sequence, String payload) throws IOException {
        Files.createDirectories(dataDirectory);
        String encodedPayload = Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String line = sequence + "|" + Instant.now().toEpochMilli() + "|" + encodedPayload + System.lineSeparator();
        Files.writeString(
                getRelayPath(dataDirectory),
                line,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
        );
    }

    public static List<RelayEvent> readRelayEvents(Path dataDirectory) {
        Path relayPath = getRelayPath(dataDirectory);
        if (!Files.exists(relayPath)) {
            return List.of();
        }

        List<RelayEvent> events = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(relayPath, StandardCharsets.UTF_8)) {
                String[] parts = line.split("\\|", 3);
                if (parts.length != 3) {
                    continue;
                }

                long sequence = Long.parseLong(parts[0]);
                long receivedAtMillis = Long.parseLong(parts[1]);
                String payload = new String(Base64.getDecoder().decode(parts[2]), StandardCharsets.UTF_8);
                events.add(new RelayEvent(sequence, receivedAtMillis, payload));
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return events;
    }

    public static void cleanup(Path dataDirectory) {
        try {
            Files.deleteIfExists(getSessionPath(dataDirectory));
            Files.deleteIfExists(getRelayPath(dataDirectory));
            Files.deleteIfExists(getReadyPath(dataDirectory));
        } catch (IOException ignored) {
        }
    }

    public static Path getSessionPath(Path dataDirectory) {
        return dataDirectory.resolve(SESSION_FILE);
    }

    public static Path getRelayPath(Path dataDirectory) {
        return dataDirectory.resolve(RELAY_FILE);
    }

    public static Path getReadyPath(Path dataDirectory) {
        return dataDirectory.resolve(READY_FILE);
    }

    public record Session(String id, long createdAtMillis, int relayTimeoutSeconds, String restartScriptPath) {
        public long relayDeadlineMillis() {
            return createdAtMillis + relayTimeoutSeconds * 1000L;
        }
    }

    public record RelayEvent(long sequence, long receivedAtMillis, String payload) {
    }
}
