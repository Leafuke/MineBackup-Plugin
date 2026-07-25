package com.leafuke.minebackup.plugin.runtime;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

public interface WorldAccess {
    void executeMain(Runnable task);

    void savePlayers();

    List<ManagedWorld> loadedWorlds();

    interface ManagedWorld {
        UUID id();

        String name();

        Path directory();

        void save();

        boolean autoSave();

        void autoSave(boolean enabled);
    }
}
