package com.leafuke.minebackup.plugin.runtime;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class OperationCoordinator {
    private final ScheduledExecutorService scheduler;
    private final Clock clock;

    private Operation active;
    private OperationResult lastResult;
    private ScheduledFuture<?> countdown;
    private Consumer<UUID> countdownSubmit;

    public OperationCoordinator(ScheduledExecutorService scheduler) {
        this(scheduler, Clock.systemUTC());
    }

    OperationCoordinator(ScheduledExecutorService scheduler, Clock clock) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public synchronized Optional<Operation> begin(Type type, Origin origin, String actor, String target) {
        // 所有会改变存档的动作共用一个门，避免保存、备份和还原互相穿插。
        return begin(UUID.randomUUID(), type, origin, actor, target, Phase.SUBMITTED);
    }

    public synchronized Optional<Operation> adopt(UUID id, Type type, String target) {
        return begin(id, type, Origin.FOLDER_REWIND, "FolderRewind", target, Phase.SUBMITTED);
    }

    public synchronized Optional<Operation> beginRestoreCountdown(
            Origin origin,
            String actor,
            String target,
            int seconds,
            Consumer<UUID> submit) {
        Objects.requireNonNull(submit, "submit");
        Optional<Operation> created = begin(UUID.randomUUID(), Type.RESTORE, origin, actor, target,
                Phase.COUNTDOWN);
        if (created.isEmpty()) {
            return Optional.empty();
        }
        countdownSubmit = submit;
        if (seconds > 0) {
            UUID id = created.orElseThrow().id();
            countdown = scheduler.schedule(() -> submitCountdown(id), seconds, TimeUnit.SECONDS);
        }
        return created;
    }

    private Optional<Operation> begin(
            UUID id,
            Type type,
            Origin origin,
            String actor,
            String target,
            Phase phase) {
        if (active != null) {
            return Optional.empty();
        }
        active = new Operation(
                Objects.requireNonNull(id, "id"),
                Objects.requireNonNull(type, "type"),
                Objects.requireNonNull(origin, "origin"),
                normalize(actor),
                normalize(target),
                phase,
                clock.instant());
        return Optional.of(active);
    }

    public boolean confirmRestore() {
        Consumer<UUID> submit;
        UUID id;
        synchronized (this) {
            if (active == null || active.type() != Type.RESTORE || active.phase() != Phase.COUNTDOWN) {
                return false;
            }
            cancelCountdownLocked();
            active = active.withPhase(Phase.SUBMITTED);
            id = active.id();
            submit = countdownSubmit;
            countdownSubmit = null;
        }
        // 回调可能执行网络请求，必须在释放 synchronized 锁后调用，防止重入死锁。
        submit.accept(id);
        return true;
    }

    public synchronized boolean cancelPendingRestore() {
        if (active == null || active.type() != Type.RESTORE || active.phase() != Phase.COUNTDOWN) {
            return false;
        }
        Operation cancelled = active;
        cancelCountdownLocked();
        countdownSubmit = null;
        active = null;
        lastResult = new OperationResult(cancelled, Outcome.CANCELLED, "Cancelled before submission", clock.instant());
        return true;
    }

    public synchronized boolean transition(UUID id, Phase phase) {
        if (!matches(id)) {
            return false;
        }
        active = active.withPhase(Objects.requireNonNull(phase, "phase"));
        return true;
    }

    public synchronized boolean complete(UUID id, Outcome outcome, String detail) {
        if (!matches(id)) {
            return false;
        }
        Operation completed = active;
        active = null;
        cancelCountdownLocked();
        countdownSubmit = null;
        lastResult = new OperationResult(completed, Objects.requireNonNull(outcome, "outcome"),
                detail == null ? "" : detail, clock.instant());
        return true;
    }

    public synchronized Optional<Operation> active() {
        return Optional.ofNullable(active);
    }

    public synchronized Optional<OperationResult> lastResult() {
        return Optional.ofNullable(lastResult);
    }

    public synchronized boolean isBusy() {
        return active != null;
    }

    private void submitCountdown(UUID id) {
        Consumer<UUID> submit;
        synchronized (this) {
            if (!matches(id) || active.phase() != Phase.COUNTDOWN) {
                return;
            }
            countdown = null;
            active = active.withPhase(Phase.SUBMITTED);
            submit = countdownSubmit;
            countdownSubmit = null;
        }
        submit.accept(id);
    }

    private boolean matches(UUID id) {
        return active != null && active.id().equals(id);
    }

    private void cancelCountdownLocked() {
        if (countdown != null) {
            countdown.cancel(false);
            countdown = null;
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    public enum Type {
        SAVE,
        CURRENT_BACKUP,
        TARGET_BACKUP,
        RESTORE
    }

    public enum Origin {
        PLAYER,
        CONSOLE,
        AUTO,
        FOLDER_REWIND
    }

    public enum Phase {
        COUNTDOWN,
        SUBMITTED,
        SAVING,
        WAITING_BACKEND,
        SIDECAR_HANDOFF
    }

    public enum Outcome {
        SUCCESS,
        NO_CHANGES,
        FAILED,
        CANCELLED,
        HANDOFF_ACCEPTED,
        UNCERTAIN
    }

    public record Operation(
            UUID id,
            Type type,
            Origin origin,
            String actor,
            String target,
            Phase phase,
            Instant startedAt) {
        Operation withPhase(Phase value) {
            return new Operation(id, type, origin, actor, target, value, startedAt);
        }
    }

    public record OperationResult(Operation operation, Outcome outcome, String detail, Instant finishedAt) {
    }
}
