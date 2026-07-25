package com.leafuke.minebackup.plugin.runtime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperationCoordinatorTest {
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @AfterEach
    void close() {
        scheduler.shutdownNow();
    }

    @Test
    void admitsOnlyOneMutationAndRequiresMatchingCompletion() {
        OperationCoordinator coordinator = new OperationCoordinator(scheduler);
        var first = coordinator.begin(OperationCoordinator.Type.CURRENT_BACKUP,
                OperationCoordinator.Origin.PLAYER, "Alice", "world").orElseThrow();
        assertTrue(coordinator.isBusy());
        assertTrue(coordinator.begin(OperationCoordinator.Type.TARGET_BACKUP,
                OperationCoordinator.Origin.CONSOLE, "console", "target").isEmpty());
        assertFalse(coordinator.complete(UUID.randomUUID(), OperationCoordinator.Outcome.SUCCESS, "wrong"));
        assertTrue(coordinator.complete(first.id(), OperationCoordinator.Outcome.NO_CHANGES, "none"));
        assertFalse(coordinator.isBusy());
        assertEquals(OperationCoordinator.Outcome.NO_CHANGES,
                coordinator.lastResult().orElseThrow().outcome());
    }

    @Test
    void countdownCanBeConfirmedOnceOrCancelledBeforeSubmission() {
        OperationCoordinator coordinator = new OperationCoordinator(scheduler);
        AtomicInteger submitted = new AtomicInteger();
        coordinator.beginRestoreCountdown(OperationCoordinator.Origin.CONSOLE, "console", "latest", 60,
                submitted::incrementAndGet).orElseThrow();
        assertTrue(coordinator.confirmRestore());
        assertEquals(1, submitted.get());
        assertFalse(coordinator.confirmRestore());
        assertFalse(coordinator.cancelPendingRestore());

        UUID active = coordinator.active().orElseThrow().id();
        coordinator.complete(active, OperationCoordinator.Outcome.FAILED, "test");
        coordinator.beginRestoreCountdown(OperationCoordinator.Origin.PLAYER, "Bob", "latest", 60,
                submitted::incrementAndGet).orElseThrow();
        assertTrue(coordinator.cancelPendingRestore());
        assertEquals(1, submitted.get());
    }
}
