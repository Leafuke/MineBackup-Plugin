package org.leafuke.mineBackupPlugin;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.util.StringUtil;
import org.leafuke.mineBackupPlugin.knotlink.OpenSocketQuerier;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MineBackup 命令处理器
 * <p>
 * 处理所有 /mb 子命令以及 Tab 补全。
 * <p>
 * 新增子命令:
 * <ul>
 *   <li>{@code /mb confirm} — 确认待处理的还原操作</li>
 *   <li>{@code /mb abort}   — 取消正在进行的还原倒计时</li>
 *   <li>{@code /mb status}  — 显示插件状态摘要</li>
 *   <li>{@code /mb reload}  — 重新加载配置文件</li>
 * </ul>
 */
public class MbCommand implements CommandExecutor, TabCompleter {

    private final MineBackupPlugin plugin;

    // KnotLink 通信 ID
    private static final String QUERIER_APP_ID = MineBackupPlugin.QUERIER_APP_ID;
    private static final String QUERIER_SOCKET_ID = MineBackupPlugin.QUERIER_SOCKET_ID;

    // 备份文件名缓存（用于 Tab 补全）
    private static final Map<String, List<String>> backupFilesCache = new ConcurrentHashMap<>();
    private static final Map<String, Long> backupFilesCacheTime = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 10000L; // 10秒缓存

    // 当前世界备份文件缓存（用于 quickrestore Tab 补全）
    private static volatile List<String> currentBackupsCache = null;
    private static volatile long currentBackupsCacheTime = 0L;
    private static final long CURRENT_BACKUPS_CACHE_TTL_MS = 5000L;

    // 子命令列表
    private static final List<String> SUBCOMMANDS = Arrays.asList(
            "save", "list_configs", "list_worlds", "list_backups",
            "backup", "restore", "quicksave", "quickrestore",
            "auto", "stop", "snap",
            "confirm", "abort", "status", "reload"
    );

    public MbCommand(MineBackupPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            plugin.getLanguageManager().sendMessage(sender, "minebackup.usage");
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "save"           -> handleSave(sender);
            case "list_configs"   -> handleListConfigs(sender);
            case "list_worlds"    -> handleListWorlds(sender, args);
            case "list_backups"   -> handleListBackups(sender, args);
            case "backup"         -> handleBackup(sender, args);
            case "restore"        -> handleRestore(sender, args);
            case "quicksave"      -> handleQuicksave(sender, args);
            case "quickrestore"   -> handleQuickrestore(sender, args);
            case "auto"           -> handleAuto(sender, args);
            case "stop"           -> handleStop(sender, args);
            case "snap"           -> handleSnap(sender, args);
            case "confirm"        -> handleConfirm(sender);
            case "abort"          -> handleAbort(sender);
            case "status"         -> handleStatus(sender);
            case "reload"         -> handleReload(sender);
            default -> plugin.getLanguageManager().sendMessage(sender, "minebackup.usage");
        }
        return true;
    }

    // ==================== 子命令处理 ====================

    /**
     * /mb save - 保存所有世界
     */
    private void handleSave(CommandSender sender) {
        saveAllWorlds(sender);
    }

    /**
     * /mb list_configs - 查询配置列表
     */
    private void handleListConfigs(CommandSender sender) {
        plugin.getLanguageManager().sendMessage(sender, "minebackup.list_configs.start");
        plugin.getBackupLogger().debug("COMMAND", sender.getName() + " 执行 list_configs");
        queryBackend("LIST_CONFIGS", response ->
                handleListConfigsResponse(sender, response));
    }

    /**
     * /mb list_worlds <config_id> - 列出配置中的世界
     */
    private void handleListWorlds(CommandSender sender, String[] args) {
        if (args.length < 2) {
            plugin.getLanguageManager().sendMessage(sender, "minebackup.usage.list_worlds");
            return;
        }
        int configId;
        try {
            configId = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            plugin.getLanguageManager().sendMessage(sender, "minebackup.error.invalid_number");
            return;
        }
        plugin.getLanguageManager().sendMessage(sender, "minebackup.list_worlds.start", String.valueOf(configId));
        queryBackend(String.format("LIST_WORLDS %d", configId), response ->
                handleListWorldsResponse(sender, response, configId));
    }

    /**
     * /mb list_backups <config_id> <world_index> - 列出备份文件
     */
    private void handleListBackups(CommandSender sender, String[] args) {
        if (args.length < 3) {
            plugin.getLanguageManager().sendMessage(sender, "minebackup.usage.list_backups");
            return;
        }
        int configId, worldIndex;
        try {
            configId = Integer.parseInt(args[1]);
            worldIndex = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            plugin.getLanguageManager().sendMessage(sender, "minebackup.error.invalid_number");
            return;
        }
        plugin.getLanguageManager().sendMessage(sender, "minebackup.list_backups.start",
                String.valueOf(configId), String.valueOf(worldIndex));
        queryBackend(String.format("LIST_BACKUPS %d %d", configId, worldIndex), response ->
                handleListBackupsResponse(sender, response, configId, worldIndex));
    }

    /**
     * /mb backup <config_id> <world_index> [comment] - 执行备份
     */
    private void handleBackup(CommandSender sender, String[] args) {
        if (args.length < 3) {
            plugin.getLanguageManager().sendMessage(sender, "minebackup.usage.backup");
            return;
        }
        int configId, worldIndex;
        try {
            configId = Integer.parseInt(args[1]);
            worldIndex = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            plugin.getLanguageManager().sendMessage(sender, "minebackup.error.invalid_number");
            return;
        }
        String cmd;
        if (args.length > 3) {
            String comment = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
            cmd = String.format("BACKUP %d %d %s", configId, worldIndex, comment);
        } else {
            cmd = String.format("BACKUP %d %d", configId, worldIndex);
        }

        plugin.getBackupLogger().info("BACKUP", sender.getName() + " 发起备份: " + cmd);
        executeRemoteCommand(sender, cmd);
    }

    /**
     * /mb restore <config_id> <world_index> <backup_file> - 执行还原（带确认+倒计时）
     */
    private void handleRestore(CommandSender sender, String[] args) {
        if (args.length < 4) {
            plugin.getLanguageManager().sendMessage(sender, "minebackup.usage.restore");
            return;
        }
        int configId, worldIndex;
        try {
            configId = Integer.parseInt(args[1]);
            worldIndex = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            plugin.getLanguageManager().sendMessage(sender, "minebackup.error.invalid_number");
            return;
        }
        String backupFile = args[3];
        String cmd = String.format("RESTORE %d %d %s", configId, worldIndex, backupFile);

        // 使用 RestoreTask 流水线（确认 → 倒计时 → 执行）
        startRestoreWithPipeline(sender, cmd);
    }

    /**
     * /mb quicksave [comment] - 快速保存并备份当前世界
     */
    private void handleQuicksave(CommandSender sender, String[] args) {
        saveAllWorlds(sender);
        String cmd;
        if (args.length > 1) {
            String comment = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
            cmd = "BACKUP_CURRENT " + comment;
        } else {
            cmd = "BACKUP_CURRENT";
        }
        plugin.getBackupLogger().info("BACKUP", sender.getName() + " 发起快速保存: " + cmd);
        executeRemoteCommand(sender, cmd);
    }

    /**
     * /mb quickrestore [backup_file] - 快速还原当前世界（带确认+倒计时）
     */
    private void handleQuickrestore(CommandSender sender, String[] args) {
        String cmd;
        if (args.length > 1) {
            cmd = "RESTORE_CURRENT " + args[1];
        } else {
            cmd = "RESTORE_CURRENT_LATEST";
        }

        // 使用 RestoreTask 流水线
        startRestoreWithPipeline(sender, cmd);
    }

    /**
     * /mb auto <config_id> <world_index> <internal_time> - 启动自动备份
     */
    private void handleAuto(CommandSender sender, String[] args) {
        if (args.length < 4) {
            plugin.getLanguageManager().sendMessage(sender, "minebackup.usage.auto");
            return;
        }
        int configId, worldIndex, internalTime;
        try {
            configId = Integer.parseInt(args[1]);
            worldIndex = Integer.parseInt(args[2]);
            internalTime = Integer.parseInt(args[3]);
        } catch (NumberFormatException e) {
            plugin.getLanguageManager().sendMessage(sender, "minebackup.error.invalid_number");
            return;
        }
        Config.setAutoBackup(plugin, configId, worldIndex, internalTime);
        String cmd = String.format("AUTO_BACKUP %d %d %d", configId, worldIndex, internalTime);
        plugin.getBackupLogger().info("AUTO_BACKUP", sender.getName() + " 设置自动备份: " + cmd);
        executeRemoteCommand(sender, cmd);
    }

    /**
     * /mb stop <config_id> <world_index> - 停止自动备份
     */
    private void handleStop(CommandSender sender, String[] args) {
        if (args.length < 3) {
            plugin.getLanguageManager().sendMessage(sender, "minebackup.usage.stop");
            return;
        }
        int configId, worldIndex;
        try {
            configId = Integer.parseInt(args[1]);
            worldIndex = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            plugin.getLanguageManager().sendMessage(sender, "minebackup.error.invalid_number");
            return;
        }
        Config.clearAutoBackup(plugin);
        String cmd = String.format("STOP_AUTO_BACKUP %d %d", configId, worldIndex);
        plugin.getBackupLogger().info("AUTO_BACKUP", sender.getName() + " 停止自动备份: " + cmd);
        executeRemoteCommand(sender, cmd);
    }

    /**
     * /mb snap <config_id> <world_index> <backup_file> - WorldEdit 快照联动
     */
    private void handleSnap(CommandSender sender, String[] args) {
        if (args.length < 4) {
            plugin.getLanguageManager().sendMessage(sender, "minebackup.usage.snap");
            return;
        }
        int configId, worldIndex;
        try {
            configId = Integer.parseInt(args[1]);
            worldIndex = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            plugin.getLanguageManager().sendMessage(sender, "minebackup.error.invalid_number");
            return;
        }
        String backupFile = args[3];
        String cmd = String.format("ADD_TO_WE %d %d %s", configId, worldIndex, backupFile);
        plugin.getLanguageManager().sendMessage(sender, "minebackup.snap.sent", cmd);
        queryBackend(cmd, response -> handleGenericResponse(sender, response, "snap"));
    }

    /**
     * /mb confirm - 确认待处理的还原操作
     */
    private void handleConfirm(CommandSender sender) {
        RestoreTask task = RestoreTask.getCurrentTask();
        if (task != null && task.confirm()) {
            plugin.getLanguageManager().sendMessage(sender, "minebackup.confirm.success");
            plugin.getBackupLogger().info("RESTORE", sender.getName() + " 确认了还原操作");
        } else {
            plugin.getLanguageManager().sendMessage(sender, "minebackup.confirm.no_task");
        }
    }

    /**
     * /mb abort - 取消正在进行的还原操作
     */
    private void handleAbort(CommandSender sender) {
        RestoreTask task = RestoreTask.getCurrentTask();
        if (task != null && task.abort(sender.getName())) {
            plugin.getLanguageManager().sendMessage(sender, "minebackup.abort.success");
            plugin.getBackupLogger().info("RESTORE", sender.getName() + " 取消了还原操作");
        } else {
            plugin.getLanguageManager().sendMessage(sender, "minebackup.abort.no_task");
        }
    }

    /**
     * /mb status - 显示插件状态信息
     */
    private void handleStatus(CommandSender sender) {
        LanguageManager lm = plugin.getLanguageManager();
        StringBuilder sb = new StringBuilder();

        // 标题
        sb.append(lm.getTranslation(sender, "minebackup.status.title")).append("\n");

        // 插件版本
        sb.append(lm.getTranslation(sender, "minebackup.status.version",
                MineBackupPlugin.PLUGIN_VERSION)).append("\n");

        // KnotLink 连接状态
        String connStatus = HotRestoreState.handshakeCompleted
                ? lm.getTranslation(sender, "minebackup.status.connected")
                : lm.getTranslation(sender, "minebackup.status.not_connected");
        sb.append(lm.getTranslation(sender, "minebackup.status.connection", connStatus)).append("\n");

        // 主程序版本
        if (HotRestoreState.handshakeCompleted && HotRestoreState.mainProgramVersion != null) {
            sb.append(lm.getTranslation(sender, "minebackup.status.main_version",
                    HotRestoreState.mainProgramVersion)).append("\n");

            // 兼容性
            String compat = HotRestoreState.versionCompatible
                    ? lm.getTranslation(sender, "minebackup.status.compatible")
                    : lm.getTranslation(sender, "minebackup.status.incompatible");
            sb.append(lm.getTranslation(sender, "minebackup.status.compatibility", compat)).append("\n");
        }

        // 还原状态
        RestoreTask task = RestoreTask.getCurrentTask();
        String restoreStatus;
        if (task != null && task.getPhase() != RestoreTask.Phase.NONE) {
            restoreStatus = lm.getTranslation(sender, "minebackup.status.restore_active",
                    task.getPhase().getDisplayName(), task.getInitiator());
        } else {
            restoreStatus = lm.getTranslation(sender, "minebackup.status.restore_none");
        }
        sb.append(lm.getTranslation(sender, "minebackup.status.restore_state", restoreStatus)).append("\n");

        // 自动备份
        String autoStatus;
        if (Config.hasAutoBackup()) {
            autoStatus = lm.getTranslation(sender, "minebackup.status.auto_backup_on",
                    String.valueOf(Config.getConfigId()),
                    String.valueOf(Config.getWorldIndex()),
                    String.valueOf(Config.getInternalTime()));
        } else {
            autoStatus = lm.getTranslation(sender, "minebackup.status.auto_backup_off");
        }
        sb.append(lm.getTranslation(sender, "minebackup.status.auto_backup", autoStatus)).append("\n");

        // 调试模式
        String debugStatus = Config.isDebug()
                ? lm.getTranslation(sender, "minebackup.status.debug_on")
                : lm.getTranslation(sender, "minebackup.status.debug_off");
        sb.append(lm.getTranslation(sender, "minebackup.status.debug", debugStatus));

        sender.sendMessage(sb.toString());
    }

    /**
     * /mb reload - 重新加载配置
     */
    private void handleReload(CommandSender sender) {
        try {
            Config.reload(plugin);
            plugin.getBackupLogger().reinitialize();
            plugin.getLanguageManager().sendMessage(sender, "minebackup.reload.success");
            plugin.getBackupLogger().info("SYSTEM", sender.getName() + " 重新加载了配置");
        } catch (Exception e) {
            plugin.getLanguageManager().sendMessage(sender, "minebackup.reload.fail");
            plugin.getBackupLogger().error("SYSTEM", "重新加载配置失败: " + e.getMessage());
        }
    }

    // ==================== 还原流水线 ====================

    /**
     * 启动 "确认 → 倒计时 → 执行" 还原流水线
     */
    private void startRestoreWithPipeline(CommandSender sender, String restoreCommand) {
        if (RestoreTask.hasActiveTask()) {
            plugin.getLanguageManager().sendMessage(sender, "minebackup.restore.already_running");
            return;
        }

        RestoreTask task = new RestoreTask(plugin, restoreCommand, sender.getName());
        if (!task.start()) {
            plugin.getLanguageManager().sendMessage(sender, "minebackup.restore.already_running");
        }
    }

    // ==================== Tab 补全 ====================

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            // 补全子命令
            StringUtil.copyPartialMatches(args[0], SUBCOMMANDS, completions);
            Collections.sort(completions);
            return completions;
        }

        if (args.length < 2) return completions;

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "list_worlds", "list_configs" -> {
                // list_worlds 需要 config_id，list_configs 无参数
            }
            case "list_backups", "backup", "auto", "stop" -> {
                // 这些命令的参数都是数字，不提供补全
            }
            case "restore", "snap" -> {
                // args: restore <config_id> <world_index> <backup_file>
                if (args.length == 4) {
                    try {
                        int configId = Integer.parseInt(args[1]);
                        int worldIndex = Integer.parseInt(args[2]);
                        List<String> files = getBackupFiles(configId, worldIndex);
                        StringUtil.copyPartialMatches(args[3], files, completions);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            case "quickrestore" -> {
                // quickrestore [backup_file]
                if (args.length == 2) {
                    List<String> files = getCurrentBackupFiles();
                    StringUtil.copyPartialMatches(args[1], files, completions);
                }
            }
        }

        Collections.sort(completions);
        return completions;
    }

    // ==================== 辅助方法 ====================

    /**
     * 保存所有世界
     */
    private void saveAllWorlds(CommandSender sender) {
        plugin.getLanguageManager().sendMessage(sender, "minebackup.save.start");

        long saveStart = System.currentTimeMillis();
        int worldCount = 0;
        boolean allSuccess = true;

        for (World world : Bukkit.getWorlds()) {
            try {
                world.save();
                worldCount++;
            } catch (Exception e) {
                plugin.getBackupLogger().error("SAVE", "保存世界 '"
                        + world.getName() + "' 失败: " + e.getMessage());
                allSuccess = false;
            }
        }

        long saveCost = System.currentTimeMillis() - saveStart;
        plugin.getBackupLogger().info("SAVE", sender.getName() + " 保存了 " + worldCount
                + " 个世界，耗时 " + saveCost + "ms" + (allSuccess ? "" : " (部分失败)"));
        plugin.getLanguageManager().sendMessage(sender, "minebackup.save.success");
    }

    /**
     * 向 MineBackup 主程序发送查询请求
     */
    private void queryBackend(String command, java.util.function.Consumer<String> callback) {
        CompletableFuture<String> future = OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, command);
        if (future == null) {
            try { callback.accept(null); } catch (Exception ignored) {}
            return;
        }
        future
            .exceptionally(ex -> {
                plugin.getBackupLogger().error("COMM", "与 MineBackup 主程序通信异常: " + ex.getMessage());
                return "ERROR:COMMUNICATION_FAILED";
            })
            .thenAccept(resp -> {
                try { callback.accept(resp); } catch (Exception e) {
                    plugin.getBackupLogger().error("COMM", "处理后端响应时发生异常: " + e.getMessage());
                }
            });
    }

    /**
     * 执行远程命令（通用模式）
     */
    private void executeRemoteCommand(CommandSender sender, String command) {
        if (command == null || command.trim().isEmpty()) {
            plugin.getLanguageManager().sendMessage(sender, "minebackup.command.invalid");
            return;
        }
        plugin.getLanguageManager().sendMessage(sender, "minebackup.command.sent", command);
        String commandType = command.split(" ")[0].toLowerCase();
        queryBackend(command, response -> handleGenericResponse(sender, response, commandType));
    }

    /**
     * 通用响应处理器
     */
    private void handleGenericResponse(CommandSender sender, String response, String commandType) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (response != null && response.startsWith("ERROR:")) {
                plugin.getLanguageManager().sendMessage(sender, "minebackup.command.fail",
                        Messages.localizeError(sender, response));
                plugin.getBackupLogger().warn("COMMAND", "命令 '" + commandType
                        + "' 失败: " + response);
            } else {
                plugin.getLanguageManager().sendMessage(sender, "minebackup.generic.response",
                        response != null ? response
                                : plugin.getLanguageManager().getTranslation(sender, "minebackup.no_response"));
            }
        });
    }

    /**
     * 处理 LIST_CONFIGS 响应
     */
    private void handleListConfigsResponse(CommandSender sender, String response) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (response == null || !response.startsWith("OK:")) {
                plugin.getLanguageManager().sendMessage(sender, "minebackup.list_configs.fail",
                        Messages.localizeError(sender, response));
                return;
            }
            StringBuilder result = new StringBuilder(
                    plugin.getLanguageManager().getTranslation(sender, "minebackup.list_configs.title"));
            String data = response.substring(3);
            if (data.isEmpty()) {
                result.append(plugin.getLanguageManager().getTranslation(sender, "minebackup.list_configs.empty"));
            } else {
                for (String config : data.split(";")) {
                    String[] parts = config.split(",", 2);
                    if (parts.length == 2) {
                        result.append(plugin.getLanguageManager().getTranslation(sender,
                                "minebackup.list_configs.entry", parts[0], parts[1]));
                    }
                }
            }
            sender.sendMessage(result.toString());
        });
    }

    /**
     * 处理 LIST_WORLDS 响应
     */
    private void handleListWorldsResponse(CommandSender sender, String response, int configId) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (response == null || !response.startsWith("OK:")) {
                plugin.getLanguageManager().sendMessage(sender, "minebackup.list_worlds.fail",
                        Messages.localizeError(sender, response));
                return;
            }
            StringBuilder result = new StringBuilder(
                    plugin.getLanguageManager().getTranslation(sender, "minebackup.list_worlds.title",
                            String.valueOf(configId)));
            String data = response.substring(3);
            if (data.isEmpty()) {
                result.append(plugin.getLanguageManager().getTranslation(sender, "minebackup.list_worlds.empty"));
            } else {
                String[] worlds = data.split(";");
                for (int i = 0; i < worlds.length; i++) {
                    result.append(plugin.getLanguageManager().getTranslation(sender,
                            "minebackup.list_worlds.entry", String.valueOf(i), worlds[i]));
                }
            }
            sender.sendMessage(result.toString());
        });
    }

    /**
     * 处理 LIST_BACKUPS 响应
     */
    private void handleListBackupsResponse(CommandSender sender, String response,
                                           int configId, int worldIndex) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (response == null || !response.startsWith("OK:")) {
                plugin.getLanguageManager().sendMessage(sender, "minebackup.list_backups.fail",
                        Messages.localizeError(sender, response));
                return;
            }
            StringBuilder result = new StringBuilder(
                    plugin.getLanguageManager().getTranslation(sender, "minebackup.list_backups.title",
                            String.valueOf(configId), String.valueOf(worldIndex)));
            String data = response.substring(3);
            if (data.isEmpty()) {
                result.append(plugin.getLanguageManager().getTranslation(sender, "minebackup.list_backups.empty"));
            } else {
                for (String file : data.split(";")) {
                    if (!file.isEmpty()) {
                        result.append(plugin.getLanguageManager().getTranslation(sender,
                                "minebackup.list_backups.entry", file));
                    }
                }
            }
            sender.sendMessage(result.toString());
        });
    }

    // ==================== 备份文件缓存（用于 Tab 补全） ====================

    /**
     * 获取指定配置/世界的备份文件列表（带缓存）
     */
    private List<String> getBackupFiles(int configId, int worldIndex) {
        String key = configId + ":" + worldIndex;
        long now = System.currentTimeMillis();
        Long lastTime = backupFilesCacheTime.get(key);

        if (lastTime == null || now - lastTime > CACHE_TTL_MS) {
            refreshBackupFilesAsync(configId, worldIndex, key);
        }

        List<String> cached = backupFilesCache.get(key);
        return cached != null ? cached : Collections.emptyList();
    }

    /**
     * 异步刷新备份文件缓存
     */
    private void refreshBackupFilesAsync(int configId, int worldIndex, String key) {
        backupFilesCacheTime.put(key, System.currentTimeMillis());
        String command = String.format("LIST_BACKUPS %d %d", configId, worldIndex);
        OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, command)
                .thenAccept(response -> {
                    if (response != null && response.startsWith("OK:")) {
                        String data = response.substring(3);
                        List<String> files = new ArrayList<>();
                        for (String file : data.split(";")) {
                            if (!file.isEmpty()) files.add(file);
                        }
                        backupFilesCache.put(key, files);
                    }
                });
    }

    /**
     * 获取当前世界的备份文件列表（带缓存和节流）
     */
    private List<String> getCurrentBackupFiles() {
        long now = System.currentTimeMillis();
        if (now - currentBackupsCacheTime > CURRENT_BACKUPS_CACHE_TTL_MS) {
            refreshCurrentBackupsAsync();
        }
        return currentBackupsCache != null ? currentBackupsCache : Collections.emptyList();
    }

    /**
     * 异步刷新当前世界备份缓存
     */
    private void refreshCurrentBackupsAsync() {
        currentBackupsCacheTime = System.currentTimeMillis();
        OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, "LIST_BACKUPS_CURRENT")
                .thenAccept(response -> {
                    if (response != null && response.startsWith("OK:")) {
                        String data = response.substring(3);
                        List<String> files = new ArrayList<>();
                        for (String file : data.split(";")) {
                            if (!file.isEmpty()) files.add("'" + file + "'");
                            // 引号包裹很重要！
                        }
                        currentBackupsCache = files;
                    }
                });
    }
}
