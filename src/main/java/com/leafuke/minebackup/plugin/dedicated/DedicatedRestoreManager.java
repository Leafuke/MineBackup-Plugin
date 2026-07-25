package com.leafuke.minebackup.plugin.dedicated;

import com.leafuke.minebackup.plugin.config.PluginConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class DedicatedRestoreManager {
    private final DedicatedRestoreStore store;
    private final Executor executor;
    private final Logger logger;
    private volatile Optional<DedicatedRestoreSession> lastResult = Optional.empty();

    public DedicatedRestoreManager(Path restartDirectory, Executor executor, Logger logger) {
        store = new DedicatedRestoreStore(restartDirectory);
        this.executor = Objects.requireNonNull(executor, "executor");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public void loadLastResult() {
        try {
            lastResult = store.readLastResult();
            Files.deleteIfExists(store.readyPath());
            Optional<DedicatedRestoreSession> unfinished = store.readActive();
            if (unfinished.isPresent()) {
                DedicatedRestoreSession uncertain = unfinished.orElseThrow().withState(
                        DedicatedRestoreSession.State.UNCERTAIN,
                        "Server started with an unfinished restore handoff");
                store.writeFinal(uncertain);
                lastResult = Optional.of(uncertain);
            }
        } catch (Exception exception) {
            logger.log(Level.WARNING, "Failed to load dedicated restore handoff state", exception);
        }
    }

    public Availability availability(PluginConfig.DedicatedRestore config, Path workingDirectory) {
        RestartScriptResolver.Resolution resolution = RestartScriptResolver.resolve(
                config, workingDirectory, System.getProperty("os.name", ""));
        if (!resolution.available()) {
            return new Availability(false, resolution.reason(), resolution);
        }
        try {
            Files.createDirectories(store.directory());
            if (!Files.isWritable(store.directory())) {
                return new Availability(false, "Restart session directory is not writable", resolution);
            }
        } catch (IOException exception) {
            return new Availability(false, exception.getMessage(), resolution);
        }
        return new Availability(true, "", resolution);
    }

    public CompletableFuture<Handoff> prepareAsync(
            PluginConfig.DedicatedRestore config,
            Path workingDirectory,
            List<Path> worldPaths,
            String worldId,
            UUID requestId,
            String callerId) {
        return CompletableFuture.supplyAsync(() -> prepare(
                config, workingDirectory, worldPaths, worldId, requestId, callerId), executor);
    }

    private Handoff prepare(
            PluginConfig.DedicatedRestore config,
            Path workingDirectory,
            List<Path> worldPaths,
            String worldId,
            UUID requestId,
            String callerId) {
        Availability availability = availability(config, workingDirectory);
        if (!availability.available()) {
            return Handoff.failed(availability.reason());
        }
        List<Path> normalizedWorlds = worldPaths.stream()
                .map(path -> path.toAbsolutePath().normalize()).distinct().toList();
        if (normalizedWorlds.isEmpty()) {
            return Handoff.failed("No loaded world paths were provided");
        }
        if (normalizedWorlds.stream().anyMatch(path -> store.directory().startsWith(path))) {
            return Handoff.failed("Restart session directory must be outside every world");
        }
        DedicatedRestoreSession session = new DedicatedRestoreSession(
                requestId, callerId, worldId, normalizedWorlds, workingDirectory,
                availability.resolution().command(), ProcessHandle.current().pid(),
                config.worldReleaseTimeoutSeconds(), config.operationTimeoutSeconds(),
                DedicatedRestoreSession.State.PREPARED, Instant.now(), "");
        Process process = null;
        try {
            store.clearTransient();
            store.writeActive(session);
            process = new ProcessBuilder(sidecarCommand(store.directory())).inheritIO().start();
            long deadline = System.nanoTime()
                    + Duration.ofSeconds(config.sidecarStartTimeoutSeconds()).toNanos();
            while (System.nanoTime() < deadline) {
                if (Files.isRegularFile(store.readyPath())) {
                    return new Handoff(true, "", session, process);
                }
                if (!process.isAlive()) {
                    int exitCode = process.exitValue();
                    store.clearTransient();
                    return Handoff.failed("Sidecar exited before ready (exit code " + exitCode + ")");
                }
                Thread.sleep(50L);
            }
            process.destroyForcibly();
            process.waitFor();
            store.clearTransient();
            return Handoff.failed("Timed out waiting for dedicated restore sidecar");
        } catch (Exception exception) {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            try {
                store.clearTransient();
            } catch (IOException cleanup) {
                exception.addSuppressed(cleanup);
            }
            return Handoff.failed(exception.getMessage());
        }
    }

    public Optional<DedicatedRestoreSession> lastResult() {
        return lastResult;
    }

    static List<String> sidecarCommand(Path restartDirectory) {
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        String executable = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java")
                .toString();
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.add("-cp");
        command.add(sidecarClasspath());
        command.add(DedicatedRestoreSidecar.class.getName());
        command.add(restartDirectory.toString());
        return List.copyOf(command);
    }

    private static String sidecarClasspath() {
        try {
            var source = DedicatedRestoreSidecar.class.getProtectionDomain().getCodeSource();
            if (source != null) {
                Path path = Path.of(source.getLocation().toURI()).toAbsolutePath().normalize();
                if (Files.exists(path)) {
                    return path.toString();
                }
            }
        } catch (Exception ignored) {
        }
        String fallback = System.getProperty("java.class.path", "").trim();
        if (fallback.isEmpty()) {
            throw new IllegalStateException("Unable to resolve MineBackup sidecar classpath");
        }
        return fallback;
    }

    public record Availability(boolean available, String reason, RestartScriptResolver.Resolution resolution) {
    }

    public record Handoff(boolean accepted, String reason, DedicatedRestoreSession session, Process sidecar) {
        static Handoff failed(String reason) {
            return new Handoff(false, reason == null ? "Sidecar handoff failed" : reason, null, null);
        }
    }
}
