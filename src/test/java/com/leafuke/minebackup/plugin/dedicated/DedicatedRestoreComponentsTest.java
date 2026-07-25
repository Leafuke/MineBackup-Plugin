package com.leafuke.minebackup.plugin.dedicated;

import com.leafuke.minebackup.plugin.config.PluginConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DedicatedRestoreComponentsTest {
    @TempDir
    Path directory;

    @Test
    void storeRoundTripsEveryWorldAndFinalizesAtomically() throws Exception {
        Files.createDirectories(directory.resolve("world"));
        Files.createDirectories(directory.resolve("world_nether"));
        DedicatedRestoreStore store = new DedicatedRestoreStore(directory.resolve("restart"));
        DedicatedRestoreSession session = new DedicatedRestoreSession(
                UUID.randomUUID(), "minebackup.mod", "world",
                List.of(directory.resolve("world"), directory.resolve("world_nether")), directory,
                List.of("java", "-version"), 42L, 8, 3_600,
                DedicatedRestoreSession.State.PREPARED, Instant.now(), "");

        store.writeActive(session);
        assertEquals(session.worldPaths(), store.readActive().orElseThrow().worldPaths());
        store.markReady();
        store.writeFinal(session.withState(DedicatedRestoreSession.State.UNCERTAIN, "test"));
        assertFalse(Files.exists(store.activePath()));
        assertFalse(Files.exists(store.readyPath()));
        assertEquals(DedicatedRestoreSession.State.UNCERTAIN,
                store.readLastResult().orElseThrow().state());
    }

    @Test
    void scriptDiscoveryRequiresExactlyOneCandidate() throws Exception {
        PluginConfig.DedicatedRestore config = new PluginConfig.DedicatedRestore(
                PluginConfig.DedicatedRestoreMode.SIDECAR, "", 5, 8, 3_600);
        assertFalse(RestartScriptResolver.resolve(config, directory, "Windows 11").available());
        Files.writeString(directory.resolve("start.bat"), "java -jar server.jar");
        var one = RestartScriptResolver.resolve(config, directory, "Windows 11");
        assertTrue(one.available());
        assertEquals("cmd.exe", one.command().get(0));
        Files.writeString(directory.resolve("run.cmd"), "java -jar server.jar");
        assertFalse(RestartScriptResolver.resolve(config, directory, "Windows 11").available());
    }

    @Test
    void releaseGateRequiresThreeConsecutiveSamplesAndEmitsOnce() {
        ReleaseGate gate = new ReleaseGate(3);
        assertFalse(gate.observe(true, true));
        assertFalse(gate.observe(true, false));
        assertFalse(gate.observe(true, true));
        assertFalse(gate.observe(true, true));
        assertTrue(gate.observe(true, true));
        assertFalse(gate.observe(true, true));
    }

    @Test
    void signalTrackerRejectsOtherRequestsAndAcceptsExplicitTerminal() {
        UUID id = UUID.randomUUID();
        SidecarSignalTracker tracker = new SidecarSignalTracker(id, "world");
        tracker.accept(Map.of("event", "restore_finished", "status", "success",
                "request_id", UUID.randomUUID().toString(), "world", "world"));
        assertTrue(tracker.terminal().isEmpty());
        tracker.accept(Map.of("event", "restore_finished", "status", "success",
                "request_id", id.toString(), "world", "world"));
        assertEquals(SidecarSignalTracker.Outcome.SUCCESS, tracker.terminal().orElseThrow());
    }
}
