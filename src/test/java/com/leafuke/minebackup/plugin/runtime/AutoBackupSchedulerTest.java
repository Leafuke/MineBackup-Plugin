package com.leafuke.minebackup.plugin.runtime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoBackupSchedulerTest {
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    @AfterEach
    void close() {
        executor.shutdownNow();
    }

    @Test
    void startRescheduleAndStopKeepOnlyOneFutureRun() {
        AutoBackupScheduler scheduler = new AutoBackupScheduler(
                executor, () -> CompletableFuture.completedFuture(null));

        scheduler.start(15);
        var first = scheduler.status();
        assertTrue(first.enabled());
        assertEquals(15, first.intervalMinutes());
        assertTrue(first.nextRun().isPresent());

        scheduler.start(30);
        var replacement = scheduler.status();
        assertEquals(30, replacement.intervalMinutes());
        assertTrue(replacement.nextRun().orElseThrow().isAfter(first.nextRun().orElseThrow()));

        scheduler.stop();
        assertFalse(scheduler.status().enabled());
        assertTrue(scheduler.status().nextRun().isEmpty());
    }

    @Test
    void rejectsNonPositiveIntervals() {
        AutoBackupScheduler scheduler = new AutoBackupScheduler(
                executor, () -> CompletableFuture.completedFuture(null));
        assertThrows(IllegalArgumentException.class, () -> scheduler.start(0));
    }
}
