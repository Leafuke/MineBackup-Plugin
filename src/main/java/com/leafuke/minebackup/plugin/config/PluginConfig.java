package com.leafuke.minebackup.plugin.config;

import java.util.Objects;

public record PluginConfig(
        int version,
        General general,
        Localization localization,
        Backup backup,
        Restore restore,
        DedicatedRestore dedicatedRestore,
        AutoBackup autoBackup,
        Logging logging) {
    public static final int CURRENT_VERSION = 2;
    public static final int MAX_AUTO_BACKUP_INTERVAL_MINUTES = 525_600;

    public PluginConfig {
        if (version != CURRENT_VERSION) {
            throw new IllegalArgumentException("Unsupported config version: " + version);
        }
        Objects.requireNonNull(general, "general");
        Objects.requireNonNull(localization, "localization");
        Objects.requireNonNull(backup, "backup");
        Objects.requireNonNull(restore, "restore");
        Objects.requireNonNull(dedicatedRestore, "dedicatedRestore");
        Objects.requireNonNull(autoBackup, "autoBackup");
        Objects.requireNonNull(logging, "logging");
    }

    public PluginConfig withAutoBackupInterval(int minutes) {
        return new PluginConfig(version, general, localization, backup, restore, dedicatedRestore,
                new AutoBackup(minutes), logging);
    }

    public record General(boolean debug) {
    }

    public record Localization(String defaultLanguage, boolean followPlayerLocale) {
        public Localization {
            defaultLanguage = normalizeLanguage(defaultLanguage);
        }

        private static String normalizeLanguage(String value) {
            if (value == null) {
                return "zh_cn";
            }
            return value.trim().toLowerCase(java.util.Locale.ROOT).replace('-', '_').startsWith("zh")
                    ? "zh_cn"
                    : "en_us";
        }
    }

    public record Backup(int freezeTimeoutSeconds) {
        public Backup {
            requireRange(freezeTimeoutSeconds, 10, 3_600, "backup.freeze-timeout-seconds");
        }
    }

    public record Restore(int countdownSeconds) {
        public Restore {
            requireRange(countdownSeconds, 0, 300, "restore.countdown-seconds");
        }
    }

    public enum DedicatedRestoreMode {
        SIDECAR,
        DISABLED
    }

    public record DedicatedRestore(
            DedicatedRestoreMode mode,
            String restartScript,
            int sidecarStartTimeoutSeconds,
            int worldReleaseTimeoutSeconds,
            int operationTimeoutSeconds) {
        public DedicatedRestore {
            Objects.requireNonNull(mode, "mode");
            restartScript = restartScript == null ? "" : restartScript.trim();
            requireRange(sidecarStartTimeoutSeconds, 1, 60,
                    "dedicated-restore.sidecar-start-timeout-seconds");
            requireRange(worldReleaseTimeoutSeconds, 1, 120,
                    "dedicated-restore.world-release-timeout-seconds");
            requireRange(operationTimeoutSeconds, 30, 86_400,
                    "dedicated-restore.operation-timeout-seconds");
        }
    }

    public record AutoBackup(int intervalMinutes) {
        public AutoBackup {
            requireRange(intervalMinutes, 0, MAX_AUTO_BACKUP_INTERVAL_MINUTES,
                    "auto-backup.interval-minutes");
        }

        public boolean enabled() {
            return intervalMinutes > 0;
        }
    }

    public record Logging(boolean enabled, int maxSizeMib, int retainedFiles) {
        public Logging {
            requireRange(maxSizeMib, 1, 1_024, "logging.max-size-mib");
            requireRange(retainedFiles, 1, 100, "logging.retained-files");
        }
    }

    private static void requireRange(int value, int minimum, int maximum, String key) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(key + " is outside " + minimum + ".." + maximum);
        }
    }
}
