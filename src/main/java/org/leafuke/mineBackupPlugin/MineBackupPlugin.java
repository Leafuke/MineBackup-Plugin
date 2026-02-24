package org.leafuke.mineBackupPlugin;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.leafuke.mineBackupPlugin.knotlink.OpenSocketQuerier;
import org.leafuke.mineBackupPlugin.knotlink.SignalSubscriber;

import java.util.HashMap;
import java.util.Map;

/**
 * MineBackup Spigot 插件主入口类
 * <p>
 * 负责：
 * <ul>
 *   <li>初始化 KnotLink 连接、注册命令</li>
 *   <li>处理来自 MineBackup 主程序的广播事件</li>
 *   <li>管理还原倒计时、自动重启等高级功能</li>
 *   <li>操作审计日志</li>
 * </ul>
 */
public final class MineBackupPlugin extends JavaPlugin {

    /** 插件版本号，用于 KnotLink 握手 */
    public static final String PLUGIN_VERSION = "1.1.0";

    // KnotLink 通信 ID
    public static final String BROADCAST_APP_ID = "0x00000020";
    public static final String BROADCAST_SIGNAL_ID = "0x00000020";
    public static final String QUERIER_APP_ID = "0x00000020";
    public static final String QUERIER_SOCKET_ID = "0x00000010";

    private SignalSubscriber knotLinkSubscriber;
    private static MineBackupPlugin instance;
    private LanguageManager languageManager;
    private BackupLogger backupLogger;

    public static MineBackupPlugin getInstance() {
        return instance;
    }

    public LanguageManager getLanguageManager() {
        return languageManager;
    }

    public BackupLogger getBackupLogger() {
        return backupLogger;
    }

    @Override
    public void onEnable() {
        instance = this;
        long startTime = System.currentTimeMillis();

        // ---- 1. 加载配置和语言 ----
        Config.load(this);
        languageManager = new LanguageManager(this);
        backupLogger = new BackupLogger(this);

        backupLogger.info("SYSTEM", "=== MineBackup Spigot Plugin v" + PLUGIN_VERSION + " 正在启动 ===");
        backupLogger.info("SYSTEM", "Minecraft 服务器: " + Bukkit.getVersion());
        backupLogger.info("SYSTEM", "Bukkit API: " + Bukkit.getBukkitVersion());

        // ---- 2. 检查是否为还原后重启 ----
        if (ServerRestartManager.isPostRestoreRestart()) {
            backupLogger.info("SYSTEM", "检测到服务器从还原后重启");
            ServerRestartManager.cleanupRestartFlag(backupLogger);
            HotRestoreState.reset();

            // 延迟广播，等待玩家连接
            Bukkit.getScheduler().runTaskLater(this, () -> {
                languageManager.broadcastMessage("minebackup.post_restore.detected");
            }, 100L); // 5 秒延迟
        }

        // ---- 3. 初始化 KnotLink 订阅器 ----
        knotLinkSubscriber = new SignalSubscriber(BROADCAST_APP_ID, BROADCAST_SIGNAL_ID);
        knotLinkSubscriber.setSignalListener(this::handleBroadcastEvent);
        Thread subscriberThread = new Thread(knotLinkSubscriber::start, "minebackup-subscriber-init");
        subscriberThread.setDaemon(true);
        subscriberThread.start();
        backupLogger.info("SYSTEM", "KnotLink 订阅器已启动 (appID=" + BROADCAST_APP_ID
                + ", signalID=" + BROADCAST_SIGNAL_ID + ")");

        // ---- 4. 加载自动备份配置 ----
        if (Config.hasAutoBackup()) {
            String cmd = String.format("AUTO_BACKUP %d %d %d",
                    Config.getConfigId(), Config.getWorldIndex(), Config.getInternalTime());
            OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, cmd);
            backupLogger.info("AUTO_BACKUP", "从配置发送自动备份请求: " + cmd);
        }

        // ---- 5. 注册命令 ----
        MbCommand mbCommand = new MbCommand(this);
        var mbCmd = getCommand("mb");
        if (mbCmd != null) {
            mbCmd.setExecutor(mbCommand);
            mbCmd.setTabCompleter(mbCommand);
        }

        var legacyCmd = getCommand("minebackup");
        if (legacyCmd != null) {
            legacyCmd.setExecutor((sender, command, label, args) -> {
                languageManager.sendMessage(sender, "minebackup.command.migrated");
                return true;
            });
        }

        long elapsed = System.currentTimeMillis() - startTime;
        backupLogger.info("SYSTEM", "插件初始化完成，耗时 " + elapsed + "ms");
    }

    @Override
    public void onDisable() {
        // 关闭 KnotLink
        if (knotLinkSubscriber != null) {
            knotLinkSubscriber.stop();
            knotLinkSubscriber = null;
            backupLogger.info("SYSTEM", "KnotLink 订阅器已关闭");
        }

        // 关闭日志
        if (backupLogger != null) {
            backupLogger.info("SYSTEM", "=== MineBackup 插件已禁用 ===");
            backupLogger.close();
        }
    }

    // ==================== 广播事件处理 ====================

    /**
     * 处理从 MineBackup 主程序接收到的广播事件
     * <p>
     * 注意：此方法在 KnotLink 读取线程上调用，Bukkit API 操作需调度到主线程
     */
    private void handleBroadcastEvent(String payload) {
        if (!isEnabled()) return;

        backupLogger.debug("EVENT", "收到原始广播: " + payload);

        // 处理远程保存命令（特殊格式，非键值对）
        if ("minebackup save".equals(payload)) {
            handleRemoteSave();
            return;
        }

        Map<String, String> eventData = parsePayload(payload);
        String eventType = eventData.get("event");
        if (eventType == null) {
            backupLogger.debug("EVENT", "忽略无 event 字段的广播: " + payload);
            return;
        }

        backupLogger.info("EVENT", "收到事件: " + eventType + " | 数据: " + eventData);

        switch (eventType) {
            case "handshake" -> handleHandshake(eventData);
            case "pre_hot_backup" -> handlePreHotBackup(eventData);
            case "pre_hot_restore" -> handlePreHotRestore(eventData);
            case "restore_finished", "restore_success" -> handleRestoreFinished(eventData, eventType);
            case "game_session_start" -> {
                String world = eventData.get("world");
                backupLogger.info("SESSION", "游戏会话开始，世界: " + world);
            }
            default -> {
                Bukkit.getScheduler().runTask(this, () -> broadcastEvent(eventType, eventData));
            }
        }
    }

    /**
     * 处理远程保存命令
     */
    private void handleRemoteSave() {
        Bukkit.getScheduler().runTask(this, () -> {
            backupLogger.info("SAVE", "收到远程保存命令，正在执行...");
            languageManager.broadcastMessage("minebackup.remote_save.start");

            long saveStart = System.currentTimeMillis();
            boolean success = true;
            int worldCount = 0;

            for (World world : Bukkit.getWorlds()) {
                try {
                    world.save();
                    worldCount++;
                } catch (Exception e) {
                    backupLogger.error("SAVE", "保存世界 '" + world.getName() + "' 失败: " + e.getMessage());
                    success = false;
                }
            }

            long saveCost = System.currentTimeMillis() - saveStart;
            backupLogger.info("SAVE", "远程保存完成: " + worldCount + " 个世界，耗时 " + saveCost + "ms"
                    + (success ? "" : " (部分失败)"));
            languageManager.broadcastMessage(success
                    ? "minebackup.remote_save.success"
                    : "minebackup.remote_save.fail");
        });
    }

    /**
     * 处理握手事件
     */
    private void handleHandshake(Map<String, String> eventData) {
        String mainVersion = eventData.get("version");
        String minModVersion = eventData.get("min_mod_version");

        backupLogger.info("HANDSHAKE", "收到握手: 主程序 v" + mainVersion
                + ", min_mod_version=" + minModVersion);

        // 存储握手信息
        HotRestoreState.mainProgramVersion = mainVersion;
        HotRestoreState.handshakeCompleted = true;
        HotRestoreState.requiredMinModVersion = minModVersion;

        // 检查版本兼容性
        boolean compatible = isVersionCompatible(PLUGIN_VERSION, minModVersion);
        HotRestoreState.versionCompatible = compatible;

        // 回复握手响应
        OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID,
                "HANDSHAKE_RESPONSE " + PLUGIN_VERSION);
        backupLogger.info("HANDSHAKE", "已发送 HANDSHAKE_RESPONSE，插件版本: " + PLUGIN_VERSION
                + "，兼容性: " + (compatible ? "通过" : "不通过"));

        // 广播版本兼容性信息
        Bukkit.getScheduler().runTask(this, () -> {
            if (!compatible) {
                languageManager.broadcastMessage("minebackup.handshake.version_incompatible",
                        PLUGIN_VERSION, minModVersion != null ? minModVersion : "?");
                backupLogger.warn("HANDSHAKE", "版本不兼容: 插件 v" + PLUGIN_VERSION
                        + " < 要求 v" + minModVersion);
            } else {
                languageManager.broadcastMessage("minebackup.handshake.success",
                        mainVersion != null ? mainVersion : "?");
            }
        });
    }

    /**
     * 处理热备份事件
     * <p>
     * 在热备份前保存所有世界数据，然后通知主程序
     */
    private void handlePreHotBackup(Map<String, String> eventData) {
        Bukkit.getScheduler().runTask(this, () -> {
            String worldName = Bukkit.getWorlds().isEmpty() ? "unknown"
                    : Bukkit.getWorlds().get(0).getName();

            backupLogger.info("BACKUP", "收到热备份请求，正在保存所有世界数据...");
            languageManager.broadcastMessage("minebackup.broadcast.hot_backup_request", worldName);

            long saveStart = System.currentTimeMillis();
            boolean allSaved = true;

            for (World world : Bukkit.getWorlds()) {
                try {
                    world.save();
                    backupLogger.debug("BACKUP", "世界 '" + world.getName() + "' 保存完成");
                } catch (Exception e) {
                    backupLogger.error("BACKUP", "保存世界 '" + world.getName() + "' 失败: " + e.getMessage());
                    allSaved = false;
                }
            }

            long saveCost = System.currentTimeMillis() - saveStart;
            backupLogger.info("BACKUP", "世界数据保存完成，耗时 " + saveCost + "ms"
                    + (allSaved ? "" : " (部分失败)"));

            if (!allSaved) {
                languageManager.broadcastMessage("minebackup.broadcast.hot_backup_warn", worldName);
            }

            languageManager.broadcastMessage("minebackup.broadcast.hot_backup_complete");

            // 通知主程序世界保存已完成
            OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, "WORLD_SAVED");
            backupLogger.info("BACKUP", "已发送 WORLD_SAVED 通知");
        });
    }

    /**
     * 处理热还原事件
     * <p>
     * 根据当前是否已有 RestoreTask 来决定处理方式：
     * <ul>
     *   <li>有活动的命令发起任务（EXECUTING 阶段）→ 直接执行关服流程</li>
     *   <li>有活动的任务在其他阶段 → 取消后转为远程还原流程</li>
     *   <li>无活动任务 → 创建新的远程还原任务（含倒计时）</li>
     * </ul>
     */
    private void handlePreHotRestore(Map<String, String> eventData) {
        backupLogger.info("RESTORE", "收到 pre_hot_restore 事件: " + eventData);

        Bukkit.getScheduler().runTask(this, () -> {
            RestoreTask task = RestoreTask.getCurrentTask();

            if (task != null && task.getPhase() == RestoreTask.Phase.EXECUTING && !task.isRemote()) {
                // 命令发起的还原已通过倒计时并发送了 RESTORE 命令，现在收到主程序的确认
                backupLogger.info("RESTORE", "命令发起的还原收到 pre_hot_restore 确认，执行关服流程");
                task.performShutdown();

            } else if (task != null && task.getPhase() != RestoreTask.Phase.NONE) {
                // 有其他阶段的任务，取消后转为远程还原
                backupLogger.warn("RESTORE", "收到远程 pre_hot_restore，取消正在进行的本地任务 (阶段: "
                        + task.getPhase().getDisplayName() + ")");
                task.abort("remote_override");
                startRemoteRestoreTask();

            } else {
                // 无活动任务 — 纯远程发起的还原
                startRemoteRestoreTask();
            }
        });
    }

    /**
     * 创建并启动远程还原任务
     */
    private void startRemoteRestoreTask() {
        RestoreTask remoteTask = new RestoreTask(this);
        if (!remoteTask.start()) {
            // 兜底：直接执行关服（不应到达此分支）
            backupLogger.error("RESTORE", "无法启动远程还原任务，执行直接关服");
            directShutdownForRestore();
        }
    }

    /**
     * 兜底的直接关服流程（通常不会进入）
     */
    private void directShutdownForRestore() {
        HotRestoreState.isRestoring = true;
        HotRestoreState.waitingForServerStopAck = true;

        languageManager.broadcastMessage("minebackup.restore.executing");

        for (World world : Bukkit.getWorlds()) {
            try { world.save(); } catch (Exception ignored) {}
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                player.kickPlayer(languageManager.getTranslation(player, "minebackup.restore.kick"));
            } catch (Exception ignored) {}
        }

        new Thread(() -> {
            try { Thread.sleep(500); } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, "WORLD_SAVE_AND_EXIT_COMPLETE");
        }, "minebackup-restore-signal").start();

        ServerRestartManager.prepareRestart(this);
        Bukkit.shutdown();
    }

    /**
     * 处理还原完成事件
     */
    private void handleRestoreFinished(Map<String, String> eventData, String eventType) {
        String status = "restore_success".equals(eventType)
                ? "success" : eventData.getOrDefault("status", "success");

        backupLogger.info("RESTORE", "还原完成事件: type=" + eventType + ", status=" + status);

        if ("success".equals(status)) {
            Bukkit.getScheduler().runTask(this, () -> {
                languageManager.broadcastMessage("minebackup.restore.success");
                HotRestoreState.reset();
            });
        } else {
            backupLogger.warn("RESTORE", "主程序报告还原失败, status=" + status);
            HotRestoreState.reset();
        }
    }

    /**
     * 根据事件类型广播消息给所有在线玩家和控制台
     */
    private void broadcastEvent(String eventType, Map<String, String> eventData) {
        // 记录事件日志
        String category = eventType.toUpperCase().replace("_", " ");
        backupLogger.info("EVENT", "广播事件 '" + eventType + "' 给所有玩家");

        for (Player player : Bukkit.getOnlinePlayers()) {
            String message = buildMessageForSender(player, eventType, eventData);
            if (message != null) {
                player.sendMessage(message);
            }
        }
        String consoleMessage = buildMessageForSender(Bukkit.getConsoleSender(), eventType, eventData);
        if (consoleMessage != null) {
            Bukkit.getConsoleSender().sendMessage(consoleMessage);
        }
    }

    private String buildMessageForSender(org.bukkit.command.CommandSender sender,
                                         String eventType, Map<String, String> eventData) {
        return switch (eventType) {
            case "backup_started" ->
                    languageManager.getTranslation(sender, "minebackup.broadcast.backup_started",
                            Messages.getWorldDisplay(sender, eventData));
            case "restore_started" ->
                    languageManager.getTranslation(sender, "minebackup.broadcast.restore_started",
                            Messages.getWorldDisplay(sender, eventData));
            case "backup_success" -> {
                backupLogger.info("BACKUP", "备份成功: 世界='" + eventData.get("world")
                        + "', 文件='" + eventData.get("file") + "'");
                yield languageManager.getTranslation(sender, "minebackup.broadcast.backup_success",
                        Messages.getWorldDisplay(sender, eventData),
                        Messages.getFileDisplay(sender, eventData));
            }
            case "backup_failed" -> {
                backupLogger.error("BACKUP", "备份失败: 世界='" + eventData.get("world")
                        + "', 错误='" + eventData.get("error") + "'");
                yield languageManager.getTranslation(sender, "minebackup.broadcast.backup_failed",
                        Messages.getWorldDisplay(sender, eventData),
                        Messages.getErrorDisplay(sender, eventData));
            }
            case "game_session_end" -> {
                backupLogger.info("SESSION", "游戏会话结束: 世界='" + eventData.get("world") + "'");
                yield languageManager.getTranslation(sender, "minebackup.broadcast.session_end",
                        Messages.getWorldDisplay(sender, eventData));
            }
            case "auto_backup_started" -> {
                backupLogger.info("AUTO_BACKUP", "自动备份任务已启动: 世界='" + eventData.get("world") + "'");
                yield languageManager.getTranslation(sender, "minebackup.broadcast.auto_backup_started",
                        Messages.getWorldDisplay(sender, eventData));
            }
            case "we_snapshot_completed" ->
                    languageManager.getTranslation(sender, "minebackup.broadcast.we_snapshot",
                            Messages.getWorldDisplay(sender, eventData),
                            Messages.getFileDisplay(sender, eventData));
            default -> null;
        };
    }

    // ==================== 工具方法 ====================

    /**
     * 解析事件负载数据
     *
     * @param payload 格式为 "key1=value1;key2=value2" 的字符串
     */
    private Map<String, String> parsePayload(String payload) {
        Map<String, String> dataMap = new HashMap<>();
        if (payload == null || payload.isEmpty()) return dataMap;
        for (String pair : payload.split(";")) {
            String[] keyValue = pair.split("=", 2);
            if (keyValue.length == 2) {
                dataMap.put(keyValue[0].trim(), keyValue[1].trim());
            }
        }
        return dataMap;
    }

    /**
     * 版本号比较工具：检查当前版本是否满足最低要求
     * <p>
     * 格式为 major.minor.patch（如 "1.0.0"）
     */
    public static boolean isVersionCompatible(String current, String required) {
        if (required == null || required.isBlank()) return true;
        if (current == null || current.isBlank()) return false;
        try {
            int[] c = parseVersionParts(current);
            int[] r = parseVersionParts(required);
            for (int i = 0; i < 3; i++) {
                if (c[i] > r[i]) return true;
                if (c[i] < r[i]) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static int[] parseVersionParts(String version) {
        String[] parts = version.split("\\.");
        int[] result = new int[3];
        for (int i = 0; i < Math.min(parts.length, 3); i++) {
            result[i] = Integer.parseInt(parts[i].trim());
        }
        return result;
    }
}
