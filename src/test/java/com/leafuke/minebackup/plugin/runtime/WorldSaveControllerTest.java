package com.leafuke.minebackup.plugin.runtime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldSaveControllerTest {
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @AfterEach
    void close() {
        scheduler.shutdownNow();
    }

    @Test
    void restoresExactAutosaveFlagsOnlyForMatchingOperation() throws Exception {
        FakeAccess access = new FakeAccess();
        access.worlds.add(new FakeWorld("world", true));
        access.worlds.add(new FakeWorld("world_nether", false));
        WorldSaveController controller = new WorldSaveController(access, scheduler, Duration.ofMinutes(3));
        UUID operation = UUID.randomUUID();

        controller.saveAndFreeze(operation).get();
        assertFalse(access.worlds.get(0).autoSave);
        assertFalse(access.worlds.get(1).autoSave);
        assertFalse(controller.unfreeze(UUID.randomUUID()));
        assertTrue(controller.unfreeze(operation));
        assertTrue(access.worlds.get(0).autoSave);
        assertFalse(access.worlds.get(1).autoSave);
    }

    @Test
    void partialSaveFailureDoesNotFreezeWorlds() {
        FakeAccess access = new FakeAccess();
        access.worlds.add(new FakeWorld("world", true));
        access.worlds.add(new FakeWorld("broken", true) {{ failSave = true; }});
        WorldSaveController controller = new WorldSaveController(access, scheduler, Duration.ofMinutes(3));

        assertThrows(Exception.class, () -> controller.saveAndFreeze(UUID.randomUUID()).get());
        assertTrue(access.worlds.stream().allMatch(world -> world.autoSave));
    }

    private static final class FakeAccess implements WorldAccess {
        final List<FakeWorld> worlds = new ArrayList<>();

        @Override
        public void executeMain(Runnable task) {
            task.run();
        }

        @Override
        public void savePlayers() {
        }

        @Override
        public List<ManagedWorld> loadedWorlds() {
            return List.copyOf(worlds);
        }
    }

    private static class FakeWorld implements WorldAccess.ManagedWorld {
        final UUID id = UUID.randomUUID();
        final String name;
        boolean autoSave;
        boolean failSave;

        FakeWorld(String name, boolean autoSave) {
            this.name = name;
            this.autoSave = autoSave;
        }

        @Override public UUID id() { return id; }
        @Override public String name() { return name; }
        @Override public Path directory() { return Path.of(name); }
        @Override public void save() { if (failSave) throw new IllegalStateException("save failed"); }
        @Override public boolean autoSave() { return autoSave; }
        @Override public void autoSave(boolean enabled) { autoSave = enabled; }
    }
}
