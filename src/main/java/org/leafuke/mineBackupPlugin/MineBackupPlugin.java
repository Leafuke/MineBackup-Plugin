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
 * 负责初始化 KnotLink 连接，注册命令和处理来自 MineBackup 主程序的广播事件
 */
public final class MineBackupPlugin extends JavaPlugin {

    /** 插件版本号，用于 KnotLink 握手 */
    public static final String PLUGIN_VERSION = "1.0.0";

    // KnotLink 通信 ID
    public static final String BROADCAST_APP_ID = "0x00000020";
    public static final String BROADCAST_SIGNAL_ID = "0x00000020";
    public static final String QUERIER_APP_ID = "0x00000020";
    public static final String QUERIER_SOCKET_ID = "0x00000010";

    private SignalSubscriber knotLinkSubscriber;
    private static MineBackupPlugin instance;
    private LanguageManager languageManager;

    public static MineBackupPlugin getInstance() {
        return instance;
    }

    public LanguageManager getLanguageManager() {
        return languageManager;
    }

    @Override
    public void onEnable() {
        instance = this;
        languageManager = new LanguageManager(this);
        getLogger().info("[MineBackup] 正在初始化 Spigot 1.21 插件版本...");

        // 初始化 KnotLink 订阅器
        knotLinkSubscriber = new SignalSubscriber(BROADCAST_APP_ID, BROADCAST_SIGNAL_ID);
        knotLinkSubscriber.setSignalListener(this::handleBroadcastEvent);
        Thread subscriberThread = new Thread(knotLinkSubscriber::start, "minebackup-subscriber-init");
        subscriberThread.setDaemon(true);
        subscriberThread.start();

        // 加载自动备份配置
        Config.load(this);
        if (Config.hasAutoBackup()) {
            String cmd = String.format("AUTO_BACKUP %d %d %d",
                    Config.getConfigId(), Config.getWorldIndex(), Config.getInternalTime());
            OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, cmd);
            getLogger().info("[MineBackup] 从配置发送自动备份请求: " + cmd);
        }

        // 注册命令
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

        getLogger().info("[MineBackup] 插件初始化完成。");
    }

    @Override
    public void onDisable() {
        if (knotLinkSubscriber != null) {
            knotLinkSubscriber.stop();
            knotLinkSubscriber = null;
        }
        getLogger().info("[MineBackup] 插件已禁用。");
    }

    // ==================== 广播事件处理 ====================

    /**
     * 处理从 MineBackup 主程序接收到的广播事件
     * 注意：此方法在 KnotLink 读取线程上调用，Bukkit API 操作需调度到主线程
     */
    private void handleBroadcastEvent(String payload) {
        if (!isEnabled()) return;

        // 处理远程保存命令
        if ("minebackup save".equals(payload)) {
            Bukkit.getScheduler().runTask(this, () -> {
                getLogger().info("[MineBackup] 收到远程保存命令，正在执行...");
                languageManager.broadcastMessage("minebackup.remote_save.start");
                boolean success = true;
                for (World world : Bukkit.getWorlds()) {
                    try {
                        world.save();
                    } catch (Exception e) {
                        getLogger().warning("[MineBackup] 保存世界 " + world.getName() + " 失败: " + e.getMessage());
                        success = false;
                    }
                }
                languageManager.broadcastMessage(success ? "minebackup.remote_save.success" : "minebackup.remote_save.fail");
            });
            return;
        }

        getLogger().info("[MineBackup] 收到广播事件: " + payload);
        Map<String, String> eventData = parsePayload(payload);
        String eventType = eventData.get("event");
        if (eventType == null) return;

        switch (eventType) {
            case "handshake" -> handleHandshake(eventData);
            case "pre_hot_backup" -> handlePreHotBackup(eventData);
            case "pre_hot_restore" -> handlePreHotRestore(eventData);
            case "restore_finished", "restore_success" -> handleRestoreFinished(eventData, eventType);
            case "game_session_start" -> {
                String world = eventData.get("world");
                getLogger().info("[MineBackup] 检测到游戏会话开始，世界: " + world);
            }
            default -> {
                Bukkit.getScheduler().runTask(this, () -> broadcastEvent(eventType, eventData));
            }
        }
    }

    /**
     * 处理握手事件
     */
    private void handleHandshake(Map<String, String> eventData) {
        String mainVersion = eventData.get("version");
        String minModVersion = eventData.get("min_mod_version");

        getLogger().info("[MineBackup] 收到握手请求: 主程序v" + mainVersion
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
        getLogger().info("[MineBackup] 已发送 HANDSHAKE_RESPONSE，插件版本: " + PLUGIN_VERSION);

        // 广播版本兼容性信息
        Bukkit.getScheduler().runTask(this, () -> {
            if (!compatible) {
                languageManager.broadcastMessage("minebackup.handshake.version_incompatible",
                        PLUGIN_VERSION, minModVersion != null ? minModVersion : "?");
                getLogger().warning("[MineBackup] 插件版本 " + PLUGIN_VERSION
                        + " 不满足最低要求 " + minModVersion);
            } else {
                languageManager.broadcastMessage("minebackup.handshake.success",
                        mainVersion != null ? mainVersion : "?");
            }
        });
    }

    /**
     * 处理热备份事件
     * 在热备份前保存所有世界数据，然后通知主程序
     */
    private void handlePreHotBackup(Map<String, String> eventData) {
        Bukkit.getScheduler().runTask(this, () -> {
            getLogger().info("[MineBackup] 收到热备份请求，执行即时保存");

            // 获取主世界名称作为显示
            String worldName = Bukkit.getWorlds().isEmpty() ? "unknown"
                    : Bukkit.getWorlds().get(0).getName();

            languageManager.broadcastMessage("minebackup.broadcast.hot_backup_request", worldName);

            // 保存所有世界
            boolean allSaved = true;
            for (World world : Bukkit.getWorlds()) {
                try {
                    world.save();
                } catch (Exception e) {
                    getLogger().warning("[MineBackup] 保存世界 " + world.getName() + " 失败: " + e.getMessage());
                    allSaved = false;
                }
            }

            if (!allSaved) {
                getLogger().warning("[MineBackup] 部分数据保存失败，世界: " + worldName);
                languageManager.broadcastMessage("minebackup.broadcast.hot_backup_warn", worldName);
            }

            getLogger().info("[MineBackup] 世界数据保存完成");
            languageManager.broadcastMessage("minebackup.broadcast.hot_backup_complete");

            // 通知主程序世界保存已完成
            OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, "WORLD_SAVED");
            getLogger().info("[MineBackup] 已发送 WORLD_SAVED 通知");
        });
    }

    /**
     * 处理热还原事件
     * Spigot 服务器版本：踢出所有玩家 → 保存世界 → 通知主程序 → 关闭服务器
     * 服务器需要外部机制（如脚本/面板）来自动重启
     */
    private void handlePreHotRestore(Map<String, String> eventData) {
        getLogger().info("[MineBackup] 收到热还原准备请求");

        Bukkit.getScheduler().runTask(this, () -> {
            languageManager.broadcastMessage("minebackup.restore.preparing");

            // 标记还原状态
            HotRestoreState.isRestoring = true;
            HotRestoreState.waitingForServerStopAck = true;

            getLogger().info("[MineBackup] 检测到专用服务器，踢出所有玩家并停止服务器");

            // 1. 保存所有世界数据
            getLogger().info("[MineBackup] 保存世界数据...");
            for (World world : Bukkit.getWorlds()) {
                try {
                    world.save();
                } catch (Exception e) {
                    getLogger().warning("[MineBackup] 保存世界 " + world.getName() + " 时出现异常: " + e.getMessage());
                }
            }

            // 2. 踢出所有玩家
            for (Player player : Bukkit.getOnlinePlayers()) {
                try {
                    player.kickPlayer(languageManager.getTranslation(player, "minebackup.restore.kick"));
                } catch (Exception e) {
                    getLogger().warning("[MineBackup] 踢出玩家 " + player.getName() + " 时出现异常: " + e.getMessage());
                }
            }

            // 3. 在异步线程中延迟通知 MineBackup 主程序
            new Thread(() -> {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, "WORLD_SAVE_AND_EXIT_COMPLETE");
                getLogger().info("[MineBackup] 已发送 WORLD_SAVE_AND_EXIT_COMPLETE (专用服务器)");
            }, "minebackup-restore-signal").start();

            // 4. 关闭服务器（MineBackup 主程序将替换世界文件，服务器需通过外部脚本重启）
            getLogger().info("[MineBackup] 正在关闭服务器...");
            Bukkit.shutdown();
        });
    }

    /**
     * 处理还原完成事件
     * 在 Spigot 环境下，服务器一般已在热还原前关闭；
     * 如果服务器仍在运行（例如非热还原场景），则广播成功消息
     */
    private void handleRestoreFinished(Map<String, String> eventData, String eventType) {
        String status = "restore_success".equals(eventType)
                ? "success" : eventData.getOrDefault("status", "success");

        if ("success".equals(status)) {
            Bukkit.getScheduler().runTask(this, () -> {
                languageManager.broadcastMessage("minebackup.restore.success");
                HotRestoreState.reset();
            });
            getLogger().info("[MineBackup] 还原成功");
        } else {
            getLogger().warning("[MineBackup] 主程序报告还原失败，status=" + status);
            HotRestoreState.reset();
        }
    }

    /**
     * 根据事件类型广播消息
     */
    private void broadcastEvent(String eventType, Map<String, String> eventData) {
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

    private String buildMessageForSender(org.bukkit.command.CommandSender sender, String eventType, Map<String, String> eventData) {
        return switch (eventType) {
            case "backup_started" ->
                    languageManager.getTranslation(sender, "minebackup.broadcast.backup_started", Messages.getWorldDisplay(sender, eventData));
            case "restore_started" ->
                    languageManager.getTranslation(sender, "minebackup.broadcast.restore_started", Messages.getWorldDisplay(sender, eventData));
            case "backup_success" ->
                    languageManager.getTranslation(sender, "minebackup.broadcast.backup_success",
                            Messages.getWorldDisplay(sender, eventData), Messages.getFileDisplay(sender, eventData));
            case "backup_failed" ->
                    languageManager.getTranslation(sender, "minebackup.broadcast.backup_failed",
                            Messages.getWorldDisplay(sender, eventData), Messages.getErrorDisplay(sender, eventData));
            case "game_session_end" ->
                    languageManager.getTranslation(sender, "minebackup.broadcast.session_end", Messages.getWorldDisplay(sender, eventData));
            case "auto_backup_started" ->
                    languageManager.getTranslation(sender, "minebackup.broadcast.auto_backup_started", Messages.getWorldDisplay(sender, eventData));
            case "we_snapshot_completed" ->
                    languageManager.getTranslation(sender, "minebackup.broadcast.we_snapshot",
                            Messages.getWorldDisplay(sender, eventData), Messages.getFileDisplay(sender, eventData));
            default -> null;
        };
    }

    // ==================== 工具方法 ====================

    /**
     * 解析事件负载数据
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
            getPlugin(MineBackupPlugin.class).getLogger().warning(
                    "版本号解析失败: current=" + current + ", required=" + required);
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
