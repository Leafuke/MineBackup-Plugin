package com.leafuke.minebackup.plugin.runtime;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public final class AutoBackupScheduler implements AutoCloseable {
    private final ScheduledExecutorService scheduler;
    private final Supplier<CompletableFuture<Void>> trigger;
    private final Clock clock;

    private int intervalMinutes;
    private Instant nextRun;
    private ScheduledFuture<?> future;
    private boolean closed;

    public AutoBackupScheduler(ScheduledExecutorService scheduler, Supplier<CompletableFuture<Void>> trigger) {
        this(scheduler, trigger, Clock.systemUTC());
    }

    AutoBackupScheduler(
            ScheduledExecutorService scheduler,
            Supplier<CompletableFuture<Void>> trigger,
            Clock clock) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.trigger = Objects.requireNonNull(trigger, "trigger");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public synchronized void start(int minutes) {
        if (minutes < 1) {
            throw new IllegalArgumentException("Automatic backup interval must be positive");
        }
        if (closed) {
            throw new IllegalStateException("Automatic backup scheduler is closed");
        }
        intervalMinutes = minutes;
        scheduleNext();
    }

    public synchronized void stop() {
        intervalMinutes = 0;
        nextRun = null;
        if (future != null) {
            future.cancel(false);
            future = null;
        }
    }

    public synchronized Status status() {
        return new Status(intervalMinutes > 0, intervalMinutes, Optional.ofNullable(nextRun));
    }

    private void runOnce() {
        synchronized (this) {
            future = null;
            nextRun = null;
            if (closed || intervalMinutes == 0) {
                return;
            }
        }
        CompletableFuture<Void> completion;
        try {
            completion = trigger.get();
        } catch (Throwable exception) {
            completion = CompletableFuture.failedFuture(exception);
        }
        completion.whenComplete((ignored, error) -> {
            synchronized (AutoBackupScheduler.this) {
                if (!closed && intervalMinutes > 0) {
                    scheduleNext();
                }
            }
        });
    }

    private void scheduleNext() {
        if (future != null) {
            future.cancel(false);
        }
        nextRun = clock.instant().plusSeconds(Math.multiplyExact(intervalMinutes, 60L));
        future = scheduler.schedule(this::runOnce, intervalMinutes, TimeUnit.MINUTES);
    }

    @Override
    public synchronized void close() {
        closed = true;
        stop();
    }

    public record Status(boolean enabled, int intervalMinutes, Optional<Instant> nextRun) {
    }
}
