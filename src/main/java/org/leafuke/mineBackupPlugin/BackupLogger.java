package org.leafuke.mineBackupPlugin;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.logging.*;

/**
 * 操作审计日志记录器
 * <p>
 * 将备份、还原、自动备份、握手等关键操作记录到独立的日志文件中，
 * 便于服主事后分析和故障排查。日志文件位于 plugins/MineBackupPlugin/logs/ 下，
 * 支持自动轮转（大小限制 + 数量限制）。
 * <p>
 * 日志格式示例:
 * <pre>
 * [2026-02-24 12:34:56.789] [INFO] [RESTORE] Restore confirmed by operator
 * [2026-02-24 12:34:57.001] [WARNING] [RESTORE] Failed to save world 'world_nether': IOException
 * </pre>
 */
public class BackupLogger {

    private static final String LOG_DIR = "logs";
    private static final String LOG_FILE = "operations.log";

    private final MineBackupPlugin plugin;
    private Logger fileLogger;
    private FileHandler fileHandler;
    private boolean enabled;

    public BackupLogger(MineBackupPlugin plugin) {
        this.plugin = plugin;
        this.enabled = Config.isFileLoggingEnabled();
        if (enabled) {
            initFileLogger();
        }
    }

    /**
     * 初始化文件日志记录器（RotatingFileHandler）
     */
    private void initFileLogger() {
        try {
            File logDir = new File(plugin.getDataFolder(), LOG_DIR);
            if (!logDir.exists() && !logDir.mkdirs()) {
                plugin.getLogger().warning("[MineBackup] 无法创建日志目录: " + logDir.getAbsolutePath());
                enabled = false;
                return;
            }

            String logPath = new File(logDir, LOG_FILE).getAbsolutePath();
            int maxSizeBytes = Config.getLogMaxSizeMb() * 1024 * 1024;
            int maxFiles = Config.getLogMaxFiles();

            fileLogger = Logger.getLogger("MineBackup-Operations");
            fileLogger.setUseParentHandlers(false); // 不要输出到控制台
            fileLogger.setLevel(Level.ALL);

            // 清除旧 handler（防止 reload 后重复添加）
            for (Handler h : fileLogger.getHandlers()) {
                fileLogger.removeHandler(h);
                h.close();
            }

            fileHandler = new FileHandler(logPath, maxSizeBytes, maxFiles, true);
            fileHandler.setEncoding("UTF-8");
            fileHandler.setFormatter(new Formatter() {
                private final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

                @Override
                public String format(LogRecord record) {
                    return String.format("[%s] [%s] %s%n",
                            sdf.format(new Date(record.getMillis())),
                            record.getLevel().getName(),
                            record.getMessage());
                }
            });

            fileLogger.addHandler(fileHandler);
            plugin.getLogger().info("[MineBackup] 操作日志已初始化: " + logPath);
        } catch (IOException e) {
            plugin.getLogger().severe("[MineBackup] 初始化操作日志失败: " + e.getMessage());
            enabled = false;
        }
    }

    // ==================== 日志记录方法 ====================

    /**
     * 记录一般信息
     *
     * @param category 分类标签（如 RESTORE, BACKUP, HANDSHAKE, AUTO_BACKUP 等）
     * @param message  日志内容
     */
    public void info(String category, String message) {
        String formatted = "[" + category + "] " + message;
        plugin.getLogger().info(formatted);
        writeFile(Level.INFO, formatted);
    }

    /**
     * 记录警告信息
     */
    public void warn(String category, String message) {
        String formatted = "[" + category + "] " + message;
        plugin.getLogger().warning(formatted);
        writeFile(Level.WARNING, formatted);
    }

    /**
     * 记录错误信息
     */
    public void error(String category, String message) {
        String formatted = "[" + category + "] " + message;
        plugin.getLogger().severe(formatted);
        writeFile(Level.SEVERE, formatted);
    }

    /**
     * 仅在调试模式下输出的日志
     */
    public void debug(String category, String message) {
        if (Config.isDebug()) {
            String formatted = "[DEBUG] [" + category + "] " + message;
            plugin.getLogger().info(formatted);
            writeFile(Level.FINE, formatted);
        }
    }

    private void writeFile(Level level, String message) {
        if (enabled && fileLogger != null) {
            fileLogger.log(level, message);
        }
    }

    /**
     * 关闭文件日志（插件禁用时调用）
     */
    public void close() {
        if (fileHandler != null) {
            fileHandler.flush();
            fileHandler.close();
        }
    }

    /**
     * 重新初始化（配置 reload 后调用）
     */
    public void reinitialize() {
        close();
        this.enabled = Config.isFileLoggingEnabled();
        if (enabled) {
            initFileLogger();
        }
    }
}
