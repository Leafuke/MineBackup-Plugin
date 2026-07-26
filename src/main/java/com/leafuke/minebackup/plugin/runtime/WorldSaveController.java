package com.leafuke.minebackup.plugin.runtime;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class WorldSaveController implements AutoCloseable {
    private final WorldAccess worlds;
    private final ScheduledExecutorService scheduler;
    private volatile Duration freezeTimeout;
    private final Object lock = new Object();

    private UUID frozenFor;
    private Map<UUID, Boolean> previousAutoSave = Map.of();
    private ScheduledFuture<?> watchdog;

    public WorldSaveController(WorldAccess worlds, ScheduledExecutorService scheduler, Duration freezeTimeout) {
        this.worlds = Objects.requireNonNull(worlds, "worlds");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.freezeTimeout = Objects.requireNonNull(freezeTimeout, "freezeTimeout");
        if (freezeTimeout.isZero() || freezeTimeout.isNegative()) {
            throw new IllegalArgumentException("freezeTimeout must be positive");
        }
    }

    public void setFreezeTimeout(Duration value) {
        Objects.requireNonNull(value, "value");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("freezeTimeout must be positive");
        }
        freezeTimeout = value;
    }

    public CompletableFuture<List<Path>> saveOnly() {
        CompletableFuture<List<Path>> result = new CompletableFuture<>();
        worlds.executeMain(() -> {
            Map<UUID, Boolean> captured = new LinkedHashMap<>();
            List<Path> paths = List.of();
            Throwable failure = null;
            try {
                List<WorldAccess.ManagedWorld> loaded = pauseLoadedWorlds(captured);
                worlds.savePlayers();
                for (WorldAccess.ManagedWorld world : loaded) {
                    world.save();
                }
                paths = loaded.stream().map(WorldAccess.ManagedWorld::directory).distinct().toList();
            } catch (Throwable exception) {
                failure = exception;
            } finally {
                // 普通保存只在同步写盘期间短暂停止自动保存，结束后无条件恢复原值。
                restoreCaptured(captured);
            }
            if (failure == null) {
                result.complete(paths);
            } else {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    public CompletableFuture<List<Path>> saveAndFreeze(UUID operationId) {
        Objects.requireNonNull(operationId, "operationId");
        CompletableFuture<List<Path>> result = new CompletableFuture<>();
        worlds.executeMain(() -> {
            Map<UUID, Boolean> captured = new LinkedHashMap<>();
            try {
                synchronized (lock) {
                    if (frozenFor != null) {
                        throw new IllegalStateException("World autosave is already frozen");
                    }
                }
                // 必须先暂停自动保存再写盘：避免 Paper 的自动保存与插件快照交错，
                // 同时也不会触发“auto-save 开启时插件手动保存”的性能提示。
                List<WorldAccess.ManagedWorld> loaded = pauseLoadedWorlds(captured);
                worlds.savePlayers();
                for (WorldAccess.ManagedWorld world : loaded) {
                    world.save();
                }
                synchronized (lock) {
                    frozenFor = operationId;
                    previousAutoSave = Map.copyOf(captured);
                    watchdog = scheduler.schedule(() -> worlds.executeMain(() -> unfreeze(operationId)),
                            freezeTimeout.toMillis(), TimeUnit.MILLISECONDS);
                }
                result.complete(loaded.stream().map(WorldAccess.ManagedWorld::directory).distinct().toList());
            } catch (Throwable exception) {
                restoreCaptured(captured);
                result.completeExceptionally(exception);
            }
        });
        return result;
    }

    public boolean unfreeze(UUID operationId) {
        Map<UUID, Boolean> restore;
        synchronized (lock) {
            if (frozenFor == null || !frozenFor.equals(operationId)) {
                return false;
            }
            frozenFor = null;
            restore = previousAutoSave;
            previousAutoSave = Map.of();
            if (watchdog != null) {
                watchdog.cancel(false);
                watchdog = null;
            }
        }
        restoreCaptured(restore);
        return true;
    }

    private List<WorldAccess.ManagedWorld> pauseLoadedWorlds(Map<UUID, Boolean> captured) {
        List<WorldAccess.ManagedWorld> loaded = List.copyOf(worlds.loadedWorlds());
        for (WorldAccess.ManagedWorld world : loaded) {
            captured.put(world.id(), world.autoSave());
            world.autoSave(false);
        }
        return loaded;
    }

    public Status status() {
        synchronized (lock) {
            return new Status(frozenFor);
        }
    }

    private void restoreCaptured(Map<UUID, Boolean> captured) {
        if (captured.isEmpty()) {
            return;
        }
        for (WorldAccess.ManagedWorld world : worlds.loadedWorlds()) {
            Boolean enabled = captured.get(world.id());
            if (enabled != null) {
                world.autoSave(enabled);
            }
        }
    }

    @Override
    public void close() {
        UUID operation;
        synchronized (lock) {
            operation = frozenFor;
        }
        if (operation != null) {
            worlds.executeMain(() -> unfreeze(operation));
        }
    }

    public record Status(UUID operationId) {
        public boolean frozen() {
            return operationId != null;
        }
    }
}
