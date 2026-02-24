package org.leafuke.mineBackupPlugin;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * MineBackup 配置管理类
 * 基于 Bukkit YAML 配置系统（config.yml），涵盖还原倒计时、重启策略、文件日志等全部设置
 */
public class Config {

    private static FileConfiguration config;

    // ==================== 加载与重载 ====================

    /**
     * 首次加载配置（插件启动时调用）
     */
    public static void load(MineBackupPlugin plugin) {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        config = plugin.getConfig();
        plugin.getLogger().info("[MineBackup] 配置已加载 (config.yml)");

        if (isDebug()) {
            plugin.getLogger().info("[MineBackup] 调试模式已启用");
        }
    }

    /**
     * 重新加载配置（/mb reload 时调用）
     */
    public static void reload(MineBackupPlugin plugin) {
        plugin.reloadConfig();
        config = plugin.getConfig();
    }

    // ==================== 通用 ====================

    public static boolean isDebug() {
        return config.getBoolean("general.debug", false);
    }

    // ==================== 还原 ====================

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

    // ==================== 重启 ====================

    public static boolean isRestartEnabled() {
        return config.getBoolean("restart.enabled", true);
    }

    /** 返回 spigot / script / none */
    public static String getRestartMethod() {
        return config.getString("restart.method", "spigot");
    }

    public static String getRestartScriptPath() {
        return config.getString("restart.script-path", "./start.sh");
    }

    // ==================== 文件日志 ====================

    public static boolean isFileLoggingEnabled() {
        return config.getBoolean("logging.enabled", true);
    }

    public static int getLogMaxSizeMb() {
        return config.getInt("logging.max-size-mb", 10);
    }

    public static int getLogMaxFiles() {
        return config.getInt("logging.max-files", 5);
    }

    // ==================== 自动备份 ====================

    public static boolean hasAutoBackup() {
        return getConfigId() != -1 && getWorldIndex() != -1 && getInternalTime() != -1;
    }

    public static int getConfigId() {
        return config.getInt("auto-backup.config-id", -1);
    }

    public static int getWorldIndex() {
        return config.getInt("auto-backup.world-index", -1);
    }

    public static int getInternalTime() {
        return config.getInt("auto-backup.interval-seconds", -1);
    }

    /**
     * 设置自动备份参数并持久化
     */
    public static void setAutoBackup(MineBackupPlugin plugin, int cid, int wid, int time) {
        config.set("auto-backup.config-id", cid);
        config.set("auto-backup.world-index", wid);
        config.set("auto-backup.interval-seconds", time);
        plugin.saveConfig();
        plugin.getLogger().info("[MineBackup] 自动备份配置已保存: configId=" + cid
                + ", worldIndex=" + wid + ", interval=" + time + "s");
    }

    /**
     * 清除自动备份配置并持久化
     */
    public static void clearAutoBackup(MineBackupPlugin plugin) {
        config.set("auto-backup.config-id", -1);
        config.set("auto-backup.world-index", -1);
        config.set("auto-backup.interval-seconds", -1);
        plugin.saveConfig();
    }
}
