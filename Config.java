package org.leafuke.mineBackupPlugin;

import org.bukkit.configuration.file.FileConfiguration;

public final class Config {
    private static FileConfiguration config;

    private Config() {
    }

    public static void load(MineBackupPlugin plugin) {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        config = plugin.getConfig();
        plugin.getLogger().info("[MineBackup] Loaded config.yml");

        if (isDebug()) {
            plugin.getLogger().info("[MineBackup] Debug mode enabled");
        }
    }

    public static void reload(MineBackupPlugin plugin) {
        plugin.reloadConfig();
        config = plugin.getConfig();
    }

    public static boolean isDebug() {
        return config.getBoolean("general.debug", false);
    }

    public static int getBackupFreezeTimeoutSeconds() {
        return config.getInt("backup.freeze-timeout-seconds", 60);
    }

    public static long getCoordinationDelayTicks() {
        int seconds = config.getInt("backup.coordination-delay-seconds", 2);
        return Math.max(0, seconds) * 20L;
    }

    public static boolean isRequireConfirm() {
        return config.getBoolean("restore.require-confirm", true);
    }

    public static int getConfirmTimeoutSeconds() {
        return config.getInt("restore.confirm-timeout-seconds", 60);
    }

    public static int getCountdownSeconds() {
        return config.getInt("restore.countdown-seconds", 10);
    }

    public static boolean isRemoteRestoreCountdown() {
        return config.getBoolean("restore.remote-restore-countdown", true);
    }

    public static int getRemoteCountdownSeconds() {
        return config.getInt("restore.remote-countdown-seconds", 10);
    }

    public static boolean isRestartEnabled() {
        return config.getBoolean("restart.enabled", true);
    }

    public static String getRestartMethod() {
        return config.getString("restart.method", "sidecar");
    }

    public static String getRestartScriptPath() {
        return config.getString("restart.script-path", "./start.bat");
    }

    public static int getSidecarStartTimeoutSeconds() {
        return config.getInt("restart.sidecar.start-timeout-seconds", 5);
    }

    public static int getSidecarRelayTimeoutSeconds() {
        return config.getInt("restart.sidecar.relay-timeout-seconds", 20);
    }

    public static boolean isFileLoggingEnabled() {
        return config.getBoolean("logging.enabled", true);
    }

    public static int getLogMaxSizeMb() {
        return config.getInt("logging.max-size-mb", 10);
    }

    public static int getLogMaxFiles() {
        return config.getInt("logging.max-files", 5);
    }

    public static boolean hasAutoBackup() {
        return getConfigId() != null && !getConfigId().isBlank()
                && getWorldIndex() >= 0
                && getInternalTime() >= 0;
    }

    public static String getConfigId() {
        String value = config.getString("auto-backup.config-id", "");
        return value == null ? "" : value.trim();
    }

    public static int getWorldIndex() {
        return config.getInt("auto-backup.world-index", -1);
    }

    public static int getInternalTime() {
        return config.getInt("auto-backup.interval-seconds", -1);
    }

    public static void setAutoBackup(MineBackupPlugin plugin, String configId, int worldIndex, int intervalSeconds) {
        config.set("auto-backup.config-id", configId);
        config.set("auto-backup.world-index", worldIndex);
        config.set("auto-backup.interval-seconds", intervalSeconds);
        plugin.saveConfig();
        plugin.getLogger().info("[MineBackup] Saved auto-backup config: configId=" + configId
                + ", worldIndex=" + worldIndex + ", interval=" + intervalSeconds + "s");
    }

    public static void clearAutoBackup(MineBackupPlugin plugin) {
        config.set("auto-backup.config-id", "");
        config.set("auto-backup.world-index", -1);
        config.set("auto-backup.interval-seconds", -1);
        plugin.saveConfig();
    }
}
