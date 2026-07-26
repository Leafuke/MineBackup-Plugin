package com.leafuke.minebackup.plugin.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigStoreTest {
    @TempDir
    Path directory;

    @Test
    void createsDefaultsAndPersistsAutoBackup() throws Exception {
        ConfigStore store = new ConfigStore(directory, Logger.getAnonymousLogger());
        PluginConfig defaults = store.load();

        assertEquals(2, defaults.version());
        assertEquals("zh_cn", defaults.localization().defaultLanguage());
        assertTrue(defaults.localization().followPlayerLocale());
        assertEquals(180, defaults.backup().freezeTimeoutSeconds());
        assertFalse(defaults.autoBackup().enabled());

        store.setAutoBackupInterval(15);
        assertEquals(15, new ConfigStore(directory, Logger.getAnonymousLogger()).load()
                .autoBackup().intervalMinutes());
    }

    @Test
    void preservesLegacyConfigBeforeReset() throws Exception {
        Files.writeString(directory.resolve("config.yml"), "restart:\n  method: sidecar\n");
        Clock clock = Clock.fixed(Instant.parse("2026-07-26T01:02:03Z"), ZoneOffset.UTC);

        PluginConfig loaded = new ConfigStore(directory, Logger.getAnonymousLogger(), clock).load();

        assertEquals(2, loaded.version());
        Path backup = directory.resolve("config-v1-backup-20260726-010203.yml");
        assertTrue(Files.isRegularFile(backup));
        assertTrue(Files.readString(backup).contains("method: sidecar"));
        assertTrue(Files.readString(directory.resolve("config.yml")).contains("config-version: 2"));
    }

    @Test
    void invalidRestoreModeFailsClosedAndNumbersAreClamped() throws Exception {
        Files.writeString(directory.resolve("config.yml"), """
                config-version: 2
                backup:
                  freeze-timeout-seconds: 1
                restore:
                  countdown-seconds: 999
                dedicated-restore:
                  mode: anything
                """);

        PluginConfig loaded = new ConfigStore(directory, Logger.getAnonymousLogger()).load();

        assertEquals(10, loaded.backup().freezeTimeoutSeconds());
        assertEquals(300, loaded.restore().countdownSeconds());
        assertEquals(PluginConfig.DedicatedRestoreMode.DISABLED, loaded.dedicatedRestore().mode());
    }
}
