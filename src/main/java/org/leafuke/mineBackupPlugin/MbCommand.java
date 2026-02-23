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
 * 处理所有 /mb 子命令以及 Tab 补全
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
            "auto", "stop", "snap"
    );

    public MbCommand(MineBackupPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Messages.USAGE);
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "save" -> handleSave(sender);
            case "list_configs" -> handleListConfigs(sender);
            case "list_worlds" -> handleListWorlds(sender, args);
            case "list_backups" -> handleListBackups(sender, args);
            case "backup" -> handleBackup(sender, args);
            case "restore" -> handleRestore(sender, args);
            case "quicksave" -> handleQuicksave(sender, args);
            case "quickrestore" -> handleQuickrestore(sender, args);
            case "auto" -> handleAuto(sender, args);
            case "stop" -> handleStop(sender, args);
            case "snap" -> handleSnap(sender, args);
            default -> sender.sendMessage(Messages.USAGE);
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
        sender.sendMessage(Messages.LIST_CONFIGS_START);
        queryBackend("LIST_CONFIGS", response ->
                handleListConfigsResponse(sender, response));
    }

    /**
     * /mb list_worlds <config_id> - 列出配置中的世界
     */
    private void handleListWorlds(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§c用法: /mb list_worlds <配置ID>");
            return;
        }
        int configId;
        try {
            configId = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            sender.sendMessage("§c配置ID必须是整数。");
            return;
        }
        sender.sendMessage(Messages.format(Messages.LIST_WORLDS_START, String.valueOf(configId)));
        queryBackend(String.format("LIST_WORLDS %d", configId), response ->
                handleListWorldsResponse(sender, response, configId));
    }

    /**
     * /mb list_backups <config_id> <world_index> - 列出备份文件
     */
    private void handleListBackups(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§c用法: /mb list_backups <配置ID> <世界索引>");
            return;
        }
        int configId, worldIndex;
        try {
            configId = Integer.parseInt(args[1]);
            worldIndex = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage("§c参数必须是整数。");
            return;
        }
        sender.sendMessage(Messages.format(Messages.LIST_BACKUPS_START,
                String.valueOf(configId), String.valueOf(worldIndex)));
        queryBackend(String.format("LIST_BACKUPS %d %d", configId, worldIndex), response ->
                handleListBackupsResponse(sender, response, configId, worldIndex));
    }

    /**
     * /mb backup <config_id> <world_index> [comment] - 执行备份
     */
    private void handleBackup(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§c用法: /mb backup <配置ID> <世界索引> [备注]");
            return;
        }
        int configId, worldIndex;
        try {
            configId = Integer.parseInt(args[1]);
            worldIndex = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage("§c参数必须是整数。");
            return;
        }
        String cmd;
        if (args.length > 3) {
            String comment = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
            cmd = String.format("BACKUP %d %d %s", configId, worldIndex, comment);
        } else {
            cmd = String.format("BACKUP %d %d", configId, worldIndex);
        }
        executeRemoteCommand(sender, cmd);
    }

    /**
     * /mb restore <config_id> <world_index> <backup_file> - 执行还原
     */
    private void handleRestore(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage("§c用法: /mb restore <配置ID> <世界索引> <备份文件>");
            return;
        }
        int configId, worldIndex;
        try {
            configId = Integer.parseInt(args[1]);
            worldIndex = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage("§c参数必须是整数。");
            return;
        }
        String backupFile = args[3];
        String cmd = String.format("RESTORE %d %d %s", configId, worldIndex, backupFile);
        executeRemoteCommand(sender, cmd);
    }

    /**
     * /mb quicksave [comment] - 快速保存并备份当前世界
     */
    private void handleQuicksave(CommandSender sender, String[] args) {
        saveAllWorlds(sender);
        if (args.length > 1) {
            String comment = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
            executeRemoteCommand(sender, "BACKUP_CURRENT " + comment);
        } else {
            executeRemoteCommand(sender, "BACKUP_CURRENT");
        }
    }

    /**
     * /mb quickrestore [backup_file] - 快速还原当前世界
     */
    private void handleQuickrestore(CommandSender sender, String[] args) {
        if (args.length > 1) {
            String backupFile = args[1];
            executeRemoteCommand(sender, "RESTORE_CURRENT " + backupFile);
        } else {
            executeRemoteCommand(sender, "RESTORE_CURRENT_LATEST");
        }
    }

    /**
     * /mb auto <config_id> <world_index> <internal_time> - 启动自动备份
     */
    private void handleAuto(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage("§c用法: /mb auto <配置ID> <世界索引> <间隔秒数>");
            return;
        }
        int configId, worldIndex, internalTime;
        try {
            configId = Integer.parseInt(args[1]);
            worldIndex = Integer.parseInt(args[2]);
            internalTime = Integer.parseInt(args[3]);
        } catch (NumberFormatException e) {
            sender.sendMessage("§c参数必须是整数。");
            return;
        }
        Config.setAutoBackup(plugin, configId, worldIndex, internalTime);
        String cmd = String.format("AUTO_BACKUP %d %d %d", configId, worldIndex, internalTime);
        executeRemoteCommand(sender, cmd);
    }

    /**
     * /mb stop <config_id> <world_index> - 停止自动备份
     */
    private void handleStop(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§c用法: /mb stop <配置ID> <世界索引>");
            return;
        }
        int configId, worldIndex;
        try {
            configId = Integer.parseInt(args[1]);
            worldIndex = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage("§c参数必须是整数。");
            return;
        }
        Config.clearAutoBackup(plugin);
        String cmd = String.format("STOP_AUTO_BACKUP %d %d", configId, worldIndex);
        executeRemoteCommand(sender, cmd);
    }

    /**
     * /mb snap <config_id> <world_index> <backup_file> - WorldEdit 快照联动
     */
    private void handleSnap(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage("§c用法: /mb snap <配置ID> <世界索引> <备份文件>");
            return;
        }
        int configId, worldIndex;
        try {
            configId = Integer.parseInt(args[1]);
            worldIndex = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage("§c参数必须是整数。");
            return;
        }
        String backupFile = args[3];
        String cmd = String.format("ADD_TO_WE %d %d %s", configId, worldIndex, backupFile);
        sender.sendMessage(Messages.format(Messages.SNAP_SENT, cmd));
        queryBackend(cmd, response -> handleGenericResponse(sender, response, "snap"));
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
                // 不提供数字补全
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
        sender.sendMessage(Messages.SAVE_START);
        for (World world : Bukkit.getWorlds()) {
            try {
                world.save();
            } catch (Exception e) {
                plugin.getLogger().warning("[MineBackup] 保存世界 " + world.getName() + " 失败: " + e.getMessage());
            }
        }
        sender.sendMessage(Messages.SAVE_SUCCESS);
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
                plugin.getLogger().severe("与 MineBackup 主程序通信异常: " + ex.getMessage());
                return "ERROR:COMMUNICATION_FAILED";
            })
            .thenAccept(resp -> {
                try { callback.accept(resp); } catch (Exception e) {
                    plugin.getLogger().severe("处理后端响应时发生异常: " + e.getMessage());
                }
            });
    }

    /**
     * 执行远程命令（通用模式）
     */
    private void executeRemoteCommand(CommandSender sender, String command) {
        if (command == null || command.trim().isEmpty()) {
            sender.sendMessage(Messages.COMMAND_INVALID);
            return;
        }
        sender.sendMessage(Messages.format(Messages.COMMAND_SENT, command));
        String commandType = command.split(" ")[0].toLowerCase();
        queryBackend(command, response -> handleGenericResponse(sender, response, commandType));
    }

    /**
     * 通用响应处理器
     */
    private void handleGenericResponse(CommandSender sender, String response, String commandType) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (response != null && response.startsWith("ERROR:")) {
                sender.sendMessage(Messages.format(Messages.COMMAND_FAIL, Messages.localizeError(response)));
            } else {
                sender.sendMessage(Messages.format(Messages.GENERIC_RESPONSE,
                        response != null ? response : Messages.NO_RESPONSE));
            }
        });
    }

    /**
     * 处理 LIST_CONFIGS 响应
     */
    private void handleListConfigsResponse(CommandSender sender, String response) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (response == null || !response.startsWith("OK:")) {
                sender.sendMessage(Messages.format(Messages.LIST_CONFIGS_FAIL, Messages.localizeError(response)));
                return;
            }
            StringBuilder result = new StringBuilder(Messages.LIST_CONFIGS_TITLE);
            String data = response.substring(3);
            if (data.isEmpty()) {
                result.append(Messages.LIST_CONFIGS_EMPTY);
            } else {
                for (String config : data.split(";")) {
                    String[] parts = config.split(",", 2);
                    if (parts.length == 2) {
                        result.append(Messages.format(Messages.LIST_CONFIGS_ENTRY, parts[0], parts[1]));
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
                sender.sendMessage(Messages.format(Messages.LIST_WORLDS_FAIL, Messages.localizeError(response)));
                return;
            }
            StringBuilder result = new StringBuilder(
                    Messages.format(Messages.LIST_WORLDS_TITLE, String.valueOf(configId)));
            String data = response.substring(3);
            if (data.isEmpty()) {
                result.append(Messages.LIST_WORLDS_EMPTY);
            } else {
                String[] worlds = data.split(";");
                for (int i = 0; i < worlds.length; i++) {
                    result.append(Messages.format(Messages.LIST_WORLDS_ENTRY,
                            String.valueOf(i), worlds[i]));
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
                sender.sendMessage(Messages.format(Messages.LIST_BACKUPS_FAIL, Messages.localizeError(response)));
                return;
            }
            StringBuilder result = new StringBuilder(Messages.format(Messages.LIST_BACKUPS_TITLE,
                    String.valueOf(configId), String.valueOf(worldIndex)));
            String data = response.substring(3);
            if (data.isEmpty()) {
                result.append(Messages.LIST_BACKUPS_EMPTY);
            } else {
                for (String file : data.split(";")) {
                    if (!file.isEmpty()) {
                        result.append(Messages.format(Messages.LIST_BACKUPS_ENTRY, file));
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

        // 缓存过期则触发异步刷新
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
                            if (!file.isEmpty()) files.add(file);
                        }
                        currentBackupsCache = files;
                    }
                });
    }
}
