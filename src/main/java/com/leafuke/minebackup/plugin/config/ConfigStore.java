package com.leafuke.minebackup.plugin.config;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

public final class ConfigStore {
    private static final String CONFIG_FILE = "config.yml";
    private static final DateTimeFormatter BACKUP_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path dataDirectory;
    private final Logger logger;
    private final Clock clock;
    private final AtomicReference<PluginConfig> current = new AtomicReference<>();

    public ConfigStore(Path dataDirectory, Logger logger) {
        this(dataDirectory, logger, Clock.systemDefaultZone());
    }

    ConfigStore(Path dataDirectory, Logger logger, Clock clock) {
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public synchronized PluginConfig load() throws IOException {
        Files.createDirectories(dataDirectory);
        Path configPath = dataDirectory.resolve(CONFIG_FILE);
        if (!Files.exists(configPath)) {
            writeDefault(configPath);
        } else {
            YamlConfiguration probe = YamlConfiguration.loadConfiguration(configPath.toFile());
            if (probe.getInt("config-version", -1) != PluginConfig.CURRENT_VERSION) {
                Path backup = nextBackupPath();
                move(configPath, backup, false);
                logger.warning("MineBackup configuration was reset to v2; previous file: " + backup);
                try {
                    writeDefault(configPath);
                } catch (IOException exception) {
                    move(backup, configPath, true);
                    throw exception;
                }
            }
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(configPath.toFile());
        PluginConfig parsed = parse(yaml);
        current.set(parsed);
        return parsed;
    }

    public PluginConfig current() {
        PluginConfig snapshot = current.get();
        if (snapshot == null) {
            throw new IllegalStateException("Configuration has not been loaded");
        }
        return snapshot;
    }

    public synchronized PluginConfig setAutoBackupInterval(int minutes) throws IOException {
        PluginConfig updated = current().withAutoBackupInterval(minutes);
        Path target = dataDirectory.resolve(CONFIG_FILE);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(target.toFile());
        yaml.set("auto-backup.interval-minutes", minutes);
        writeAtomically(target, yaml.saveToString());
        current.set(updated);
        return updated;
    }

    Path nextBackupPath() {
        String stamp = BACKUP_TIME.format(LocalDateTime.now(clock));
        Path candidate = dataDirectory.resolve("config-v1-backup-" + stamp + ".yml");
        for (int suffix = 1; Files.exists(candidate); suffix++) {
            candidate = dataDirectory.resolve("config-v1-backup-" + stamp + "-" + suffix + ".yml");
        }
        return candidate;
    }

    private PluginConfig parse(YamlConfiguration yaml) {
        return new PluginConfig(
                PluginConfig.CURRENT_VERSION,
                new PluginConfig.General(yaml.getBoolean("general.debug", false)),
                new PluginConfig.Localization(
                        yaml.getString("localization.default-language", "zh_cn"),
                        yaml.getBoolean("localization.follow-player-locale", true)),
                new PluginConfig.Backup(integer(yaml, "backup.freeze-timeout-seconds", 180, 10, 3_600)),
                new PluginConfig.Restore(integer(yaml, "restore.countdown-seconds", 10, 0, 300)),
                new PluginConfig.DedicatedRestore(
                        restoreMode(yaml.getString("dedicated-restore.mode", "SIDECAR")),
                        yaml.getString("dedicated-restore.restart-script", ""),
                        integer(yaml, "dedicated-restore.sidecar-start-timeout-seconds", 5, 1, 60),
                        integer(yaml, "dedicated-restore.world-release-timeout-seconds", 8, 1, 120),
                        integer(yaml, "dedicated-restore.operation-timeout-seconds", 3_600, 30, 86_400)),
                new PluginConfig.AutoBackup(integer(yaml, "auto-backup.interval-minutes", 0, 0,
                        PluginConfig.MAX_AUTO_BACKUP_INTERVAL_MINUTES)),
                new PluginConfig.Logging(
                        yaml.getBoolean("logging.enabled", true),
                        integer(yaml, "logging.max-size-mib", 10, 1, 1_024),
                        integer(yaml, "logging.retained-files", 5, 1, 100)));
    }

    private int integer(YamlConfiguration yaml, String key, int fallback, int minimum, int maximum) {
        Object raw = yaml.get(key);
        if (!(raw instanceof Number number)) {
            if (raw != null) {
                logger.warning("Invalid numeric config value for " + key + "; using " + fallback);
            }
            return fallback;
        }
        int value = number.intValue();
        int clamped = Math.max(minimum, Math.min(maximum, value));
        if (clamped != value) {
            logger.warning("Config value " + key + " was clamped to " + clamped);
        }
        return clamped;
    }

    private PluginConfig.DedicatedRestoreMode restoreMode(String raw) {
        if (raw == null) {
            return PluginConfig.DedicatedRestoreMode.DISABLED;
        }
        try {
            return PluginConfig.DedicatedRestoreMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            logger.warning("Invalid dedicated restore mode; restore is disabled for safety");
            return PluginConfig.DedicatedRestoreMode.DISABLED;
        }
    }

    private void writeDefault(Path target) throws IOException {
        try (InputStream stream = ConfigStore.class.getClassLoader().getResourceAsStream(CONFIG_FILE)) {
            if (stream == null) {
                throw new IOException("Bundled config.yml is missing");
            }
            writeAtomically(target, new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static void writeAtomically(Path target, String content) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            move(temporary, target, true);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void move(Path source, Path target, boolean replace) throws IOException {
        try {
            if (replace) {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (AtomicMoveNotSupportedException exception) {
            if (replace) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(source, target);
            }
        }
    }
}
