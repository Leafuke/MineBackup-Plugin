package org.leafuke.mineBackupPlugin;

import org.bukkit.Bukkit;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

/**
 * 服务器重启管理器
 * <p>
 * 支持三种重启方式：
 * <ul>
 *   <li><b>spigot</b>: 使用 Spigot 内置重启机制（需要在 spigot.yml 中配置 restart-script）</li>
 *   <li><b>script</b>: 写入重启标志文件后关闭，由外部脚本检测标志文件并重启</li>
 *   <li><b>none</b>: 仅关闭服务器，依赖外部监控（如面板、systemd、Docker）自动重启</li>
 * </ul>
 */
public class ServerRestartManager {

    /** 重启标志文件名（位于服务器根目录） */
    private static final String RESTART_FLAG_FILE = ".minebackup-restart";

    private ServerRestartManager() {}

    /**
     * 准备服务器重启（在 Bukkit.shutdown() 之前调用）
     * <p>
     * 根据配置选择重启策略并执行相应准备工作。
     */
    public static void prepareRestart(MineBackupPlugin plugin) {
        BackupLogger logger = plugin.getBackupLogger();

        if (!Config.isRestartEnabled()) {
            logger.info("RESTART", "自动重启未启用，服务器将仅关闭");
            return;
        }

        String method = Config.getRestartMethod().toLowerCase();
        logger.info("RESTART", "准备服务器重启 (方式: " + method + ")");

        switch (method) {
            case "spigot" -> handleSpigotRestart(plugin, logger);
            case "script" -> handleScriptRestart(plugin, logger);
            case "none" -> logger.info("RESTART", "重启方式为 none，仅关闭服务器");
            default -> logger.warn("RESTART", "未知的重启方式: " + method + "，将仅关闭服务器");
        }
    }

    /**
     * 使用 Spigot 内置重启
     * <p>
     * Spigot 会读取 spigot.yml 中的 {@code settings.restart-script} 并在关闭后执行。
     * {@code spigot().restart()} 本身会停止服务器，因此调用后不应再调用 {@code Bukkit.shutdown()}。
     */
    private static void handleSpigotRestart(MineBackupPlugin plugin, BackupLogger logger) {
        writeRestartFlag(logger);
        try {
            logger.info("RESTART", "调用 Spigot.restart()，服务器将通过 spigot.yml 中配置的脚本重启");
            Bukkit.getServer().spigot().restart();
            // restart() 内部会调用 System.exit()，不会返回到这里
        } catch (Exception e) {
            logger.warn("RESTART", "Spigot.restart() 调用失败: " + e.getMessage()
                    + "，回退为普通关闭。请检查 spigot.yml 中 restart-script 配置");
            // 由调用方(RestoreTask)执行 Bukkit.shutdown()
        }
    }

    /**
     * 使用自定义脚本重启
     * <p>
     * 写入重启标志文件，服务器关闭后由外部脚本检测该文件并启动新的服务器实例。
     * 标志文件内容包含时间戳和原因，外部脚本可据此判断是否需要重启。
     */
    private static void handleScriptRestart(MineBackupPlugin plugin, BackupLogger logger) {
        String scriptPath = Config.getRestartScriptPath();
        writeRestartFlag(logger);
        logger.info("RESTART", "已写入重启标志文件，预期由外部脚本 '" + scriptPath + "' 重启服务器");
        logger.info("RESTART", "提示: 外部脚本应在服务器进程退出后检查 '"
                + RESTART_FLAG_FILE + "' 文件并执行重启");
    }

    /**
     * 写入重启标志文件
     */
    private static void writeRestartFlag(BackupLogger logger) {
        try {
            File flagFile = new File(RESTART_FLAG_FILE);
            try (FileWriter writer = new FileWriter(flagFile)) {
                writer.write("# MineBackup Restart Flag\n");
                writer.write("restart_requested=" + System.currentTimeMillis() + "\n");
                writer.write("reason=minebackup_restore\n");
                writer.write("timestamp=" + java.time.LocalDateTime.now() + "\n");
            }
            logger.debug("RESTART", "重启标志文件已写入: " + flagFile.getAbsolutePath());
        } catch (IOException e) {
            logger.error("RESTART", "写入重启标志文件失败: " + e.getMessage());
        }
    }

    /**
     * 清理重启标志文件（服务器启动时调用）
     */
    public static void cleanupRestartFlag(BackupLogger logger) {
        File flagFile = new File(RESTART_FLAG_FILE);
        if (flagFile.exists()) {
            if (flagFile.delete()) {
                if (logger != null) {
                    logger.info("RESTART", "已清理重启标志文件（检测到从还原后重启）");
                }
            }
        }
    }

    /**
     * 检测当前启动是否为还原后的重启
     *
     * @return true 表示上次关闭是由 MineBackup 还原操作触发的
     */
    public static boolean isPostRestoreRestart() {
        return new File(RESTART_FLAG_FILE).exists();
    }
}
