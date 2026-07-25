package com.leafuke.minebackup.plugin.dedicated;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record DedicatedRestoreSession(
        UUID requestId,
        String callerId,
        String worldId,
        List<Path> worldPaths,
        Path workingDirectory,
        List<String> restartCommand,
        long parentPid,
        int worldReleaseTimeoutSeconds,
        int operationTimeoutSeconds,
        State state,
        Instant updatedAt,
        String detail) {
    public DedicatedRestoreSession {
        Objects.requireNonNull(requestId, "requestId");
        callerId = Objects.requireNonNull(callerId, "callerId").trim();
        worldId = Objects.requireNonNull(worldId, "worldId").trim();
        worldPaths = worldPaths.stream().map(path -> path.toAbsolutePath().normalize()).distinct().toList();
        if (worldPaths.isEmpty()) {
            throw new IllegalArgumentException("At least one world path is required");
        }
        workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory")
                .toAbsolutePath().normalize();
        restartCommand = List.copyOf(restartCommand);
        if (restartCommand.isEmpty()) {
            throw new IllegalArgumentException("Restart command is empty");
        }
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(updatedAt, "updatedAt");
        detail = detail == null ? "" : detail;
    }

    public DedicatedRestoreSession withState(State value, String valueDetail) {
        return new DedicatedRestoreSession(requestId, callerId, worldId, worldPaths, workingDirectory,
                restartCommand, parentPid, worldReleaseTimeoutSeconds, operationTimeoutSeconds,
                value, Instant.now(), valueDetail);
    }

    public enum State {
        PREPARED,
        READY,
        WAITING_FOR_RELEASE,
        RELEASE_ACKNOWLEDGED,
        RESTORE_SUCCEEDED,
        RESTORE_FAILED,
        RESTORE_CANCELLED,
        RESTART_STARTED,
        RESTART_FAILED,
        UNCERTAIN
    }
}
