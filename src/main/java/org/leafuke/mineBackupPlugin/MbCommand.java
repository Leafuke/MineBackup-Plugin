package org.leafuke.mineBackupPlugin;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.util.StringUtil;
import org.leafuke.mineBackupPlugin.knotlink.OpenSocketQuerier;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class MbCommand implements CommandExecutor, TabCompleter {
    private static final String QUERIER_APP_ID = MineBackupPlugin.QUERIER_APP_ID;
    private static final String QUERIER_SOCKET_ID = MineBackupPlugin.QUERIER_SOCKET_ID;
    private static final long CACHE_TTL_MS = 10_000L;
    private static final long CURRENT_BACKUPS_CACHE_TTL_MS = 5_000L;
    private static final int TAB_COMPLETE_CONNECT_TIMEOUT_MS = 300;
    private static final int TAB_COMPLETE_READ_TIMEOUT_MS = 700;

    private static final List<String> SUBCOMMANDS = Arrays.asList(
            "help", "save", "list_configs", "list_worlds", "list_backups",
            "backup", "restore", "quickbackup", "quicksave", "quickrestore",
            "auto", "stop", "snap", "confirm", "abort", "status", "reload"
    );

    private final MineBackupPlugin plugin;
    private final Map<String, List<String>> backupFilesCache = new ConcurrentHashMap<>();
    private final Map<String, Long> backupFilesCacheTime = new ConcurrentHashMap<>();

    private volatile List<String> currentBackupsCache = List.of();
    private volatile long currentBackupsCacheTime = 0L;

    public MbCommand(MineBackupPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(CommandHelpRegistry.buildRootHelp(sender, plugin.getLanguageManager()));
            return true;
        }

        String subcommand = args[0].toLowerCase();
        switch (subcommand) {
            case "help" -> handleHelp(sender, args);
            case "save" -> handleSave(sender);
            case "list_configs" -> handleListConfigs(sender);
            case "list_worlds" -> handleListWorlds(sender, args);
            case "list_backups" -> handleListBackups(sender, args);
            case "backup" -> handleBackup(sender, args);
            case "restore" -> handleRestore(sender, args);
            case "quickbackup", "quicksave" -> handleQuickBackup(sender, args);
            case "quickrestore" -> handleQuickrestore(sender, args);
            case "auto" -> handleAuto(sender, args);
            case "stop" -> handleStop(sender, args);
            case "snap" -> handleSnap(sender, args);
            case "confirm" -> handleConfirm(sender);
            case "abort" -> handleAbort(sender);
            case "status" -> handleStatus(sender);
            case "reload" -> handleReload(sender);
            default -> sender.sendMessage(CommandHelpRegistry.buildRootHelp(sender, plugin.getLanguageManager()));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            StringUtil.copyPartialMatches(args[0], SUBCOMMANDS, completions);
            Collections.sort(completions);
            return completions;
        }

        if (args.length < 2) {
            return completions;
        }

        String subcommand = args[0].toLowerCase();
        switch (subcommand) {
            case "help" -> {
                if (args.length == 2) {
                    StringUtil.copyPartialMatches(args[1], CommandHelpRegistry.suggestCommands(args[1]), completions);
                }
            }
            case "restore", "snap" -> {
                if (args.length >= 4) {
                    Integer configId = parseInteger(args[1]);
                    Integer worldIndex = parseInteger(args[2]);
                    if (configId != null && worldIndex != null) {
                        String current = joinArgsFrom(args, 3);
                        StringUtil.copyPartialMatches(current, getBackupFiles(configId, worldIndex), completions);
                    }
                }
            }
            case "quickrestore" -> {
                if (args.length >= 2) {
                    String current = joinArgsFrom(args, 1);
                    StringUtil.copyPartialMatches(current, getCurrentBackupFiles(), completions);
                }
            }
            default -> {
            }
        }

        Collections.sort(completions);
        return completions;
    }

    private void handleHelp(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(CommandHelpRegistry.buildRootHelp(sender, plugin.getLanguageManager()));
            return;
        }
        sender.sendMessage(CommandHelpRegistry.buildCommandHelp(sender, plugin.getLanguageManager(), args[1]));
    }

    private void handleSave(CommandSender sender) {
        saveAllWorlds(sender);
    }

    private void handleListConfigs(CommandSender sender) {
        plugin.getLanguageManager().sendMessage(sender, "minebackup.list_configs.start");
        plugin.getBackupLogger().debug("COMMAND", sender.getName() + " executed list_configs");
        queryBackend("LIST_CONFIGS", response -> handleListConfigsResponse(sender, response));
    }

    private void handleListWorlds(CommandSender sender, String[] args) {
        if (args.length < 2) {
            plugin.getLanguageManager().sendMessage(sender, "minebackup.usage.list_worlds");
            return;
        }

        Integer configId = parseInteger(args[1]);
        if (configId == null) {
            plugin.getLanguageManager().sendMessage(sender, "minebackup.error.invalid_number");
            return;
        }

        plugin.getLanguageManager().sendMessage(sender, "minebackup.list_worlds.start", String.valueOf(configId));
        queryBackend("LIST_WORLDS " + configId, response -> handleListWorldsResponse(sender, response, configId));
    }

    private void handleListBackups(CommandSender sender, String[] args) {
        if (args.length < 3) {
            plugin.getLanguageManager().sendMessage(sender, "minebackup.usage.list_backups");
            return;
        }

        Integer configId = parseInteger(args[1]);
        Integer worldIndex = parseInteger(args[2]);
        if (configId == null || worldIndex == null) {
            plugin.getLanguageManager().sendMessage(sender, "minebackup.error.invalid_number");
            return;
        }

        plugin.getLanguageManager().sendMessage(sender, "minebackup.list_backups.start",
                String.valueOf(configId), String.valueOf(worldIndex));
        queryBackend("LIST_BACKUPS " + configId + " " + worldIndex,
                response -> handleListBackupsResponse(sender, response, configId, worldIndex));
    }

    private void handleBackup(CommandSender sender, String[] args) {
        if (args.length < 3) {
            plugin.getLanguageManager().sendMessage(sender, "minebackup.usage.backup");
            return;
        }

        Integer configId = parseInteger(args[1]);
        Integer worldIndex = parseInteger(args[2]);
        if (configId == null || worldIndex == null) {
            plugin.getLanguageManager().sendMessage(sender, "minebackup.error.invalid_number");
            return;
        }

        String command = args.length > 3
                ? "BACKUP " + configId + " " + worldIndex + " " + joinArgsFrom(args, 3)
                : "BACKUP " + configId + " " + worldIndex;

        plugin.getBackupLogger().info("BACKUP", sender.getName() + " requested backup: " + command);
        executeRemoteCommand(sender, command);
    }

    private void handleRestore(CommandSender sender, String[] args) {
        if (args.length < 4) {
            plugin.getLanguageManager().sendMessage(sender, "minebackup.usage.restore");
            return;
        }

        Integer configId = parseInteger(args[1]);
        Integer worldIndex = parseInteger(args[2]);
        if (configId == null || worldIndex == null) {
            plugin.getLanguageManager().sendMessage(sender, "minebackup.error.invalid_number");
            return;
        }

        String backupFile = joinArgsFrom(args, 3);
        startRestoreWithPipeline(sender, "RESTORE " + configId + " " + worldIndex + " " + backupFile);
    }

    private void handleQuickBackup(CommandSender sender, String[] args) {
        saveAllWorlds(sender);
        String command = args.length > 1 ? "BACKUP_CURRENT " + joinArgsFrom(args, 1) : "BACKUP_CURRENT";
        plugin.getBackupLogger().info("BACKUP", sender.getName() + " requested quick backup: " + command);
        executeRemoteCommand(sender, command);
    }

    private void handleQuickrestore(CommandSender sender, String[] args) {
        String command = args.length > 1 ? "RESTORE_CURRENT " + joinArgsFrom(args, 1) : "RESTORE_CURRENT_LATEST";
        startRestoreWithPipeline(sender, command);
    }

    private void handleAuto(CommandSender sender, String[] args) {
        if (args.length < 4) {
            plugin.getLanguageManager().sendMessage(sender, "minebackup.usage.auto");
            return;
        }

        Integer configId = parseInteger(args[1]);
        Integer worldIndex = parseInteger(args[2]);
        Integer intervalSeconds = parseInteger(args[3]);
        if (configId == null || worldIndex == null || intervalSeconds == null) {
            plugin.getLanguageManager().sendMessage(sender, "minebackup.error.invalid_number");
            return;
        }

        Config.setAutoBackup(plugin, configId, worldIndex, intervalSeconds);
        String command = "AUTO_BACKUP " + configId + " " + worldIndex + " " + intervalSeconds;
        plugin.getBackupLogger().info("AUTO_BACKUP", sender.getName() + " set auto backup: " + command);
        executeRemoteCommand(sender, command);
    }

    private void handleStop(CommandSender sender, String[] args) {
        if (args.length < 3) {
            plugin.getLanguageManager().sendMessage(sender, "minebackup.usage.stop");
            return;
        }

        Integer configId = parseInteger(args[1]);
        Integer worldIndex = parseInteger(args[2]);
        if (configId == null || worldIndex == null) {
            plugin.getLanguageManager().sendMessage(sender, "minebackup.error.invalid_number");
            return;
        }

        Config.clearAutoBackup(plugin);
        String command = "STOP_AUTO_BACKUP " + configId + " " + worldIndex;
        plugin.getBackupLogger().info("AUTO_BACKUP", sender.getName() + " stopped auto backup: " + command);
        executeRemoteCommand(sender, command);
    }

    private void handleSnap(CommandSender sender, String[] args) {
        if (args.length < 4) {
            plugin.getLanguageManager().sendMessage(sender, "minebackup.usage.snap");
            return;
        }

        Integer configId = parseInteger(args[1]);
        Integer worldIndex = parseInteger(args[2]);
        if (configId == null || worldIndex == null) {
            plugin.getLanguageManager().sendMessage(sender, "minebackup.error.invalid_number");
            return;
        }

        String backupFile = joinArgsFrom(args, 3);
        String command = "ADD_TO_WE " + configId + " " + worldIndex + " " + backupFile;
        plugin.getLanguageManager().sendMessage(sender, "minebackup.snap.sent", command);
        queryBackend(command, response -> handleGenericResponse(sender, response, "snap"));
    }

    private void handleConfirm(CommandSender sender) {
        RestoreTask task = RestoreTask.getCurrentTask();
        if (task != null && task.confirm()) {
            plugin.getLanguageManager().sendMessage(sender, "minebackup.confirm.success");
            plugin.getBackupLogger().info("RESTORE", sender.getName() + " confirmed restore.");
        } else {
            plugin.getLanguageManager().sendMessage(sender, "minebackup.confirm.no_task");
        }
    }

    private void handleAbort(CommandSender sender) {
        RestoreTask task = RestoreTask.getCurrentTask();
        if (task != null && task.abort(sender.getName())) {
            plugin.getLanguageManager().sendMessage(sender, "minebackup.abort.success");
            plugin.getBackupLogger().info("RESTORE", sender.getName() + " aborted restore.");
        } else {
            plugin.getLanguageManager().sendMessage(sender, "minebackup.abort.no_task");
        }
    }

    private void handleStatus(CommandSender sender) {
        LanguageManager languageManager = plugin.getLanguageManager();
        StringBuilder builder = new StringBuilder();

        builder.append(languageManager.getTranslation(sender, "minebackup.status.title")).append("\n");
        builder.append(languageManager.getTranslation(sender, "minebackup.status.version",
                MineBackupPlugin.PLUGIN_VERSION)).append("\n");

        String connectionStatus = HotRestoreState.handshakeCompleted
                ? languageManager.getTranslation(sender, "minebackup.status.connected")
                : languageManager.getTranslation(sender, "minebackup.status.not_connected");
        builder.append(languageManager.getTranslation(sender, "minebackup.status.connection", connectionStatus)).append("\n");

        if (HotRestoreState.handshakeCompleted && HotRestoreState.mainProgramVersion != null) {
            builder.append(languageManager.getTranslation(sender, "minebackup.status.main_version",
                    HotRestoreState.mainProgramVersion)).append("\n");
            String compatibility = HotRestoreState.versionCompatible
                    ? languageManager.getTranslation(sender, "minebackup.status.compatible")
                    : languageManager.getTranslation(sender, "minebackup.status.incompatible");
            builder.append(languageManager.getTranslation(sender, "minebackup.status.compatibility", compatibility)).append("\n");
        }

        RestoreTask task = RestoreTask.getCurrentTask();
        String restoreStatus = task != null && task.getPhase() != RestoreTask.Phase.NONE
                ? languageManager.getTranslation(sender, "minebackup.status.restore_active",
                task.getPhase().getDisplayName(), task.getInitiator())
                : languageManager.getTranslation(sender, "minebackup.status.restore_none");
        builder.append(languageManager.getTranslation(sender, "minebackup.status.restore_state", restoreStatus)).append("\n");

        String autoStatus = Config.hasAutoBackup()
                ? languageManager.getTranslation(sender, "minebackup.status.auto_backup_on",
                String.valueOf(Config.getConfigId()),
                String.valueOf(Config.getWorldIndex()),
                String.valueOf(Config.getInternalTime()))
                : languageManager.getTranslation(sender, "minebackup.status.auto_backup_off");
        builder.append(languageManager.getTranslation(sender, "minebackup.status.auto_backup", autoStatus)).append("\n");

        String relayStatus;
        if (!HotRestoreState.relaySessionActive) {
            relayStatus = languageManager.getTranslation(sender, "minebackup.status.restart_relay_idle");
        } else if (!HotRestoreState.sidecarReady) {
            relayStatus = languageManager.getTranslation(sender, "minebackup.status.restart_relay_starting");
        } else {
            relayStatus = languageManager.getTranslation(sender, "minebackup.status.restart_relay_active");
        }
        builder.append(languageManager.getTranslation(sender, "minebackup.status.restart_relay", relayStatus)).append("\n");

        String debugStatus = Config.isDebug()
                ? languageManager.getTranslation(sender, "minebackup.status.debug_on")
                : languageManager.getTranslation(sender, "minebackup.status.debug_off");
        builder.append(languageManager.getTranslation(sender, "minebackup.status.debug", debugStatus));
        sender.sendMessage(builder.toString());
    }

    private void handleReload(CommandSender sender) {
        try {
            Config.reload(plugin);
            plugin.getBackupLogger().reinitialize();
            plugin.getLanguageManager().sendMessage(sender, "minebackup.reload.success");
            plugin.getBackupLogger().info("SYSTEM", sender.getName() + " reloaded configuration.");
        } catch (Exception e) {
            plugin.getLanguageManager().sendMessage(sender, "minebackup.reload.fail");
            plugin.getBackupLogger().error("SYSTEM", "Failed to reload configuration: " + e.getMessage());
        }
    }

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
                plugin.getBackupLogger().error("SAVE", "Failed to save world '" + world.getName() + "': " + e.getMessage());
                allSuccess = false;
            }
        }

        long cost = System.currentTimeMillis() - saveStart;
        plugin.getBackupLogger().info("SAVE", sender.getName() + " saved " + worldCount
                + " world(s) in " + cost + "ms" + (allSuccess ? "" : " (partial failure)"));
        plugin.getLanguageManager().sendMessage(sender, "minebackup.save.success");
    }

    private void queryBackend(String command, java.util.function.Consumer<String> callback) {
        CompletableFuture<String> future = OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, command);
        future.exceptionally(ex -> {
            plugin.getBackupLogger().error("COMM", "Communication with MineBackup failed: " + ex.getMessage());
            return "ERROR:COMMUNICATION_FAILED";
        }).thenAccept(response -> {
            try {
                callback.accept(response);
            } catch (Exception e) {
                plugin.getBackupLogger().error("COMM", "Failed to process backend response: " + e.getMessage());
            }
        });
    }

    private void executeRemoteCommand(CommandSender sender, String command) {
        if (command == null || command.isBlank()) {
            plugin.getLanguageManager().sendMessage(sender, "minebackup.command.invalid");
            return;
        }
        plugin.getLanguageManager().sendMessage(sender, "minebackup.command.sent", command);
        String commandType = command.split(" ")[0].toLowerCase();
        queryBackend(command, response -> handleGenericResponse(sender, response, commandType));
    }

    private void handleGenericResponse(CommandSender sender, String response, String commandType) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (response != null && response.startsWith("ERROR:")) {
                plugin.getLanguageManager().sendMessage(sender, "minebackup.command.fail",
                        Messages.localizeError(sender, response));
                plugin.getBackupLogger().warn("COMMAND", "Command '" + commandType + "' failed: " + response);
            } else {
                plugin.getLanguageManager().sendMessage(sender, "minebackup.generic.response",
                        response != null ? response
                                : plugin.getLanguageManager().getTranslation(sender, "minebackup.no_response"));
            }
        });
    }

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

    private void handleListBackupsResponse(CommandSender sender, String response, int configId, int worldIndex) {
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

    private List<String> getBackupFiles(int configId, int worldIndex) {
        String cacheKey = configId + ":" + worldIndex;
        long now = System.currentTimeMillis();
        List<String> cached = backupFilesCache.get(cacheKey);
        Long cachedAt = backupFilesCacheTime.get(cacheKey);
        if (cached != null && cachedAt != null && now - cachedAt <= CACHE_TTL_MS) {
            return cached;
        }

        List<String> refreshed = queryBackupFilesNow("LIST_BACKUPS " + configId + " " + worldIndex);
        if (!refreshed.isEmpty()) {
            backupFilesCache.put(cacheKey, refreshed);
            backupFilesCacheTime.put(cacheKey, now);
            return refreshed;
        }
        return cached != null ? cached : List.of();
    }

    private List<String> getCurrentBackupFiles() {
        long now = System.currentTimeMillis();
        if (!currentBackupsCache.isEmpty() && now - currentBackupsCacheTime <= CURRENT_BACKUPS_CACHE_TTL_MS) {
            return currentBackupsCache;
        }

        List<String> refreshed = queryBackupFilesNow("LIST_BACKUPS_CURRENT");
        if (!refreshed.isEmpty()) {
            currentBackupsCache = refreshed;
            currentBackupsCacheTime = now;
            return refreshed;
        }
        return currentBackupsCache;
    }

    private List<String> queryBackupFilesNow(String command) {
        String response = OpenSocketQuerier.queryBlocking(
                QUERIER_APP_ID,
                QUERIER_SOCKET_ID,
                command,
                TAB_COMPLETE_CONNECT_TIMEOUT_MS,
                TAB_COMPLETE_READ_TIMEOUT_MS
        );

        if (response == null || !response.startsWith("OK:")) {
            return List.of();
        }

        List<String> files = new ArrayList<>();
        String data = response.substring(3);
        if (data.isEmpty()) {
            return files;
        }

        for (String file : data.split(";")) {
            if (!file.isEmpty()) {
                files.add(file);
            }
        }
        return files;
    }

    private static Integer parseInteger(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String joinArgsFrom(String[] args, int startIndex) {
        return String.join(" ", Arrays.copyOfRange(args, startIndex, args.length));
    }
}
