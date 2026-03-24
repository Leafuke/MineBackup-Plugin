package org.leafuke.mineBackupPlugin;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.leafuke.mineBackupPlugin.knotlink.OpenSocketQuerier;
import org.leafuke.mineBackupPlugin.knotlink.SignalSubscriber;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class MineBackupPlugin extends JavaPlugin {
    public static final String PLUGIN_VERSION = "2.0.0";
    public static final String BROADCAST_APP_ID = "0x00000020";
    public static final String BROADCAST_SIGNAL_ID = "0x00000020";
    public static final String QUERIER_APP_ID = "0x00000020";
    public static final String QUERIER_SOCKET_ID = "0x00000010";

    private static MineBackupPlugin instance;

    private SignalSubscriber knotLinkSubscriber;
    private LanguageManager languageManager;
    private BackupLogger backupLogger;
    private BukkitTask relayPollTask;
    private RestartRelayStore.Session relaySession;
    private volatile String lastHandshakeBroadcastVersion;

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

        Config.load(this);
        OpenSocketQuerier.initializeExecutor();
        languageManager = new LanguageManager(this);
        backupLogger = new BackupLogger(this);

        backupLogger.info("SYSTEM", "=== MineBackup Spigot Plugin v" + PLUGIN_VERSION + " starting ===");
        backupLogger.info("SYSTEM", "Minecraft server: " + Bukkit.getVersion());
        backupLogger.info("SYSTEM", "Bukkit API: " + Bukkit.getBukkitVersion());

        boolean postRestoreRestart = ServerRestartManager.isPostRestoreRestart();
        if (postRestoreRestart) {
            backupLogger.info("SYSTEM", "Detected post-restore restart.");
            ServerRestartManager.cleanupRestartFlag(backupLogger);
        }

        HotRestoreState.reset();
        startRelayPollingIfNeeded();
        startKnotLinkSubscriber();
        restoreAutoBackupIfNeeded();
        registerCommands();

        if (postRestoreRestart) {
            Bukkit.getScheduler().runTaskLater(this, () ->
                    languageManager.broadcastMessage("minebackup.post_restore.detected"), 100L);
        }

        long elapsed = System.currentTimeMillis() - startTime;
        backupLogger.info("SYSTEM", "Plugin initialized in " + elapsed + "ms");
    }

    @Override
    public void onDisable() {
        if (relayPollTask != null) {
            relayPollTask.cancel();
            relayPollTask = null;
        }

        if (knotLinkSubscriber != null) {
            knotLinkSubscriber.stop();
            knotLinkSubscriber = null;
            backupLogger.info("SYSTEM", "KnotLink subscriber stopped.");
        }

        OpenSocketQuerier.shutdownExecutor();

        if (backupLogger != null) {
            backupLogger.info("SYSTEM", "=== MineBackup plugin disabled ===");
            backupLogger.close();
        }
    }

    private void startKnotLinkSubscriber() {
        knotLinkSubscriber = new SignalSubscriber(BROADCAST_APP_ID, BROADCAST_SIGNAL_ID);
        knotLinkSubscriber.setSignalListener(this::handleBroadcastEvent);

        Thread subscriberThread = new Thread(knotLinkSubscriber::start, "minebackup-subscriber");
        subscriberThread.setDaemon(true);
        subscriberThread.start();
        backupLogger.info("SYSTEM", "KnotLink subscriber started (appID=" + BROADCAST_APP_ID
                + ", signalID=" + BROADCAST_SIGNAL_ID + ")");
    }

    private void restoreAutoBackupIfNeeded() {
        if (Config.hasAutoBackup()) {
            String command = String.format("AUTO_BACKUP %d %d %d",
                    Config.getConfigId(), Config.getWorldIndex(), Config.getInternalTime());
            OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, command);
            backupLogger.info("AUTO_BACKUP", "Sent auto backup request from config: " + command);
        }
    }

    private void registerCommands() {
        MbCommand mbCommand = new MbCommand(this);
        var mb = getCommand("mb");
        if (mb != null) {
            mb.setExecutor(mbCommand);
            mb.setTabCompleter(mbCommand);
        }

        var legacy = getCommand("minebackup");
        if (legacy != null) {
            legacy.setExecutor((sender, command, label, args) -> {
                languageManager.sendMessage(sender, "minebackup.command.migrated");
                return true;
            });
        }
    }

    private void startRelayPollingIfNeeded() {
        relaySession = RestartRelayStore.readSession(getDataFolder().toPath());
        if (relaySession == null) {
            HotRestoreState.resetRelay();
            RestartRelayStore.cleanup(getDataFolder().toPath());
            return;
        }

        long now = System.currentTimeMillis();
        if (now > relaySession.relayDeadlineMillis()) {
            backupLogger.info("RESTART", "Found expired relay session, cleaning it up.");
            HotRestoreState.resetRelay();
            RestartRelayStore.cleanup(getDataFolder().toPath());
            relaySession = null;
            return;
        }

        HotRestoreState.relaySessionActive = true;
        HotRestoreState.sidecarReady = RestartRelayStore.isSidecarReady(getDataFolder().toPath(), relaySession.id());
        HotRestoreState.relaySessionDeadlineMillis = relaySession.relayDeadlineMillis();
        HotRestoreState.lastRelaySequence = 0L;

        relayPollTask = Bukkit.getScheduler().runTaskTimer(this, this::pollRelayEvents, 20L, 20L);
        backupLogger.info("RESTART", "Relay session active, waiting for sidecar events. sessionId=" + relaySession.id());
    }

    private void pollRelayEvents() {
        if (relaySession == null) {
            finishRelaySession("relay session missing");
            return;
        }

        HotRestoreState.sidecarReady = RestartRelayStore.isSidecarReady(getDataFolder().toPath(), relaySession.id());

        List<RestartRelayStore.RelayEvent> events = RestartRelayStore.readRelayEvents(getDataFolder().toPath());
        for (RestartRelayStore.RelayEvent event : events) {
            if (event.sequence() <= HotRestoreState.lastRelaySequence) {
                continue;
            }

            HotRestoreState.lastRelaySequence = event.sequence();
            backupLogger.info("RESTART", "Replaying sidecar relay event #" + event.sequence() + ": " + event.payload());
            handleBroadcastEvent(event.payload());
        }

        if (System.currentTimeMillis() > relaySession.relayDeadlineMillis()) {
            finishRelaySession("relay timeout reached");
        }
    }

    private void finishRelaySession(String reason) {
        if (!HotRestoreState.relaySessionActive) {
            return;
        }

        backupLogger.info("RESTART", "Closing relay session: " + reason);
        HotRestoreState.resetRelay();
        RestartRelayStore.cleanup(getDataFolder().toPath());
        relaySession = null;
        if (relayPollTask != null) {
            relayPollTask.cancel();
            relayPollTask = null;
        }
    }

    private void handleBroadcastEvent(String payload) {
        if (!isEnabled()) {
            return;
        }

        backupLogger.debug("EVENT", "Received raw broadcast: " + payload);

        if ("minebackup save".equals(payload)) {
            handleRemoteSave();
            return;
        }

        Map<String, String> eventData = parsePayload(payload);
        String eventType = eventData.get("event");
        if (eventType == null) {
            backupLogger.debug("EVENT", "Ignoring broadcast without event field: " + payload);
            return;
        }

        backupLogger.info("EVENT", "Received event: " + eventType + " | data=" + eventData);
        switch (eventType) {
            case "handshake" -> handleHandshake(eventData);
            case "pre_hot_backup" -> handlePreHotBackup(eventData);
            case "pre_hot_restore" -> handlePreHotRestore(eventData);
            case "restore_finished", "restore_success" -> handleRestoreFinished(eventData, eventType);
            case "rejoin_world" -> handleRejoinWorld(eventData);
            case "game_session_start" -> backupLogger.info("SESSION", "Game session started, world=" + eventData.get("world"));
            default -> Bukkit.getScheduler().runTask(this, () -> broadcastEvent(eventType, eventData));
        }
    }

    private void handleRemoteSave() {
        Bukkit.getScheduler().runTask(this, () -> {
            backupLogger.info("SAVE", "Received remote save command.");
            languageManager.broadcastMessage("minebackup.remote_save.start");

            long saveStart = System.currentTimeMillis();
            boolean success = true;
            int worldCount = 0;
            for (World world : Bukkit.getWorlds()) {
                try {
                    world.save();
                    worldCount++;
                } catch (Exception e) {
                    backupLogger.error("SAVE", "Failed to save world '" + world.getName() + "': " + e.getMessage());
                    success = false;
                }
            }

            long cost = System.currentTimeMillis() - saveStart;
            backupLogger.info("SAVE", "Remote save completed for " + worldCount + " world(s) in " + cost
                    + "ms" + (success ? "" : " (partial failure)"));
            languageManager.broadcastMessage(success
                    ? "minebackup.remote_save.success"
                    : "minebackup.remote_save.fail");
        });
    }

    private void handleHandshake(Map<String, String> eventData) {
        String mainVersion = eventData.get("version");
        String minPluginVersion = eventData.get("min_mod_version");

        HotRestoreState.mainProgramVersion = mainVersion;
        HotRestoreState.handshakeCompleted = true;
        HotRestoreState.requiredMinModVersion = minPluginVersion;
        HotRestoreState.versionCompatible = isVersionCompatible(PLUGIN_VERSION, minPluginVersion);

        OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, "HANDSHAKE_RESPONSE " + PLUGIN_VERSION);
        backupLogger.info("HANDSHAKE", "Sent HANDSHAKE_RESPONSE with plugin version " + PLUGIN_VERSION);

        Bukkit.getScheduler().runTask(this, () -> {
            if (!HotRestoreState.versionCompatible) {
                languageManager.broadcastMessage("minebackup.handshake.version_incompatible",
                        PLUGIN_VERSION, minPluginVersion != null ? minPluginVersion : "?");
                backupLogger.warn("HANDSHAKE", "Version incompatible: plugin=" + PLUGIN_VERSION
                        + ", required=" + minPluginVersion);
                return;
            }

            String displayVersion = mainVersion != null ? mainVersion : "?";
            if (!displayVersion.equals(lastHandshakeBroadcastVersion)) {
                lastHandshakeBroadcastVersion = displayVersion;
                languageManager.broadcastMessage("minebackup.handshake.success", displayVersion);
            }
        });
    }

    private void handlePreHotBackup(Map<String, String> eventData) {
        Bukkit.getScheduler().runTask(this, () -> {
            String worldName = Bukkit.getWorlds().isEmpty() ? "unknown" : Bukkit.getWorlds().get(0).getName();
            backupLogger.info("BACKUP", "Received hot backup request.");
            languageManager.broadcastMessage("minebackup.broadcast.hot_backup_request", worldName);

            boolean allSaved = true;
            long saveStart = System.currentTimeMillis();
            for (World world : Bukkit.getWorlds()) {
                try {
                    world.save();
                } catch (Exception e) {
                    backupLogger.error("BACKUP", "Failed to save world '" + world.getName() + "': " + e.getMessage());
                    allSaved = false;
                }
            }

            long cost = System.currentTimeMillis() - saveStart;
            backupLogger.info("BACKUP", "Hot backup pre-save completed in " + cost + "ms"
                    + (allSaved ? "" : " (partial failure)"));
            if (!allSaved) {
                languageManager.broadcastMessage("minebackup.broadcast.hot_backup_warn", worldName);
            }

            languageManager.broadcastMessage("minebackup.broadcast.hot_backup_complete");
            OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, "WORLD_SAVED");
            backupLogger.info("BACKUP", "Sent WORLD_SAVED notification.");
        });
    }

    private void handlePreHotRestore(Map<String, String> eventData) {
        backupLogger.info("RESTORE", "Received pre_hot_restore event: " + eventData);

        Bukkit.getScheduler().runTask(this, () -> {
            RestoreTask task = RestoreTask.getCurrentTask();
            if (task != null && task.getPhase() == RestoreTask.Phase.EXECUTING && !task.isRemote()) {
                backupLogger.info("RESTORE", "Local restore acknowledged by backend, proceeding to shutdown.");
                task.performShutdown();
                return;
            }

            if (task != null && task.getPhase() != RestoreTask.Phase.NONE) {
                backupLogger.warn("RESTORE", "Remote restore overrides local task in phase " + task.getPhase().getDisplayName());
                task.abort("remote_override");
            }
            startRemoteRestoreTask();
        });
    }

    private void startRemoteRestoreTask() {
        RestoreTask remoteTask = new RestoreTask(this);
        if (!remoteTask.start()) {
            backupLogger.error("RESTORE", "Failed to start remote restore task, using direct shutdown fallback.");
            directShutdownForRestore();
        }
    }

    private void directShutdownForRestore() {
        HotRestoreState.isRestoring = true;
        HotRestoreState.waitingForServerStopAck = true;
        languageManager.broadcastMessage("minebackup.restore.executing");

        for (World world : Bukkit.getWorlds()) {
            try {
                world.save();
            } catch (Exception ignored) {
            }
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                player.kickPlayer(languageManager.getTranslation(player, "minebackup.restore.kick"));
            } catch (Exception ignored) {
            }
        }

        String response = OpenSocketQuerier.queryBlocking(
                QUERIER_APP_ID, QUERIER_SOCKET_ID, "WORLD_SAVE_AND_EXIT_COMPLETE", 500, 1000);
        backupLogger.info("RESTORE", "Fallback shutdown sent WORLD_SAVE_AND_EXIT_COMPLETE: " + response);

        ServerRestartManager.prepareRestart(this);
        Bukkit.shutdown();
    }

    private void handleRestoreFinished(Map<String, String> eventData, String eventType) {
        String status = "restore_success".equals(eventType)
                ? "success"
                : eventData.getOrDefault("status", "success");

        backupLogger.info("RESTORE", "Restore finished event: type=" + eventType + ", status=" + status);
        if (!"success".equalsIgnoreCase(status)) {
            HotRestoreState.reset();
            finishRelaySession("restore finished with non-success status");
            return;
        }

        Bukkit.getScheduler().runTask(this, () -> {
            languageManager.broadcastMessage("minebackup.restore.success");
            HotRestoreState.isRestoring = false;
            HotRestoreState.waitingForServerStopAck = false;
        });
    }

    private void handleRejoinWorld(Map<String, String> eventData) {
        backupLogger.info("RESTORE", "Received rejoin_world event: " + eventData);
        Bukkit.getScheduler().runTask(this, () -> {
            HotRestoreState.reset();
            languageManager.broadcastMessage("minebackup.restore.rejoin_ready");
            finishRelaySession("rejoin_world received");
        });
    }

    private void broadcastEvent(String eventType, Map<String, String> eventData) {
        backupLogger.info("EVENT", "Broadcasting event '" + eventType + "' to online players.");
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
                                         String eventType,
                                         Map<String, String> eventData) {
        return switch (eventType) {
            case "backup_started" -> languageManager.getTranslation(sender, "minebackup.broadcast.backup_started",
                    Messages.getWorldDisplay(sender, eventData));
            case "restore_started" -> languageManager.getTranslation(sender, "minebackup.broadcast.restore_started",
                    Messages.getWorldDisplay(sender, eventData));
            case "backup_success" -> {
                backupLogger.info("BACKUP", "Backup success: world='" + eventData.get("world")
                        + "', file='" + eventData.get("file") + "'");
                yield languageManager.getTranslation(sender, "minebackup.broadcast.backup_success",
                        Messages.getWorldDisplay(sender, eventData),
                        Messages.getFileDisplay(sender, eventData));
            }
            case "backup_failed" -> {
                backupLogger.error("BACKUP", "Backup failed: world='" + eventData.get("world")
                        + "', error='" + eventData.get("error") + "'");
                yield languageManager.getTranslation(sender, "minebackup.broadcast.backup_failed",
                        Messages.getWorldDisplay(sender, eventData),
                        Messages.getErrorDisplay(sender, eventData));
            }
            case "game_session_end" -> languageManager.getTranslation(sender, "minebackup.broadcast.session_end",
                    Messages.getWorldDisplay(sender, eventData));
            case "auto_backup_started" -> languageManager.getTranslation(sender, "minebackup.broadcast.auto_backup_started",
                    Messages.getWorldDisplay(sender, eventData));
            case "we_snapshot_completed" -> languageManager.getTranslation(sender, "minebackup.broadcast.we_snapshot",
                    Messages.getWorldDisplay(sender, eventData),
                    Messages.getFileDisplay(sender, eventData));
            default -> null;
        };
    }

    private Map<String, String> parsePayload(String payload) {
        Map<String, String> dataMap = new HashMap<>();
        if (payload == null || payload.isEmpty()) {
            return dataMap;
        }
        for (String pair : payload.split(";")) {
            String[] keyValue = pair.split("=", 2);
            if (keyValue.length == 2) {
                dataMap.put(keyValue[0].trim(), keyValue[1].trim());
            }
        }
        return dataMap;
    }

    public static boolean isVersionCompatible(String current, String required) {
        if (required == null || required.isBlank()) {
            return true;
        }
        if (current == null || current.isBlank()) {
            return false;
        }

        try {
            int[] currentParts = parseVersionParts(current);
            int[] requiredParts = parseVersionParts(required);
            for (int i = 0; i < 3; i++) {
                if (currentParts[i] > requiredParts[i]) {
                    return true;
                }
                if (currentParts[i] < requiredParts[i]) {
                    return false;
                }
            }
            return true;
        } catch (Exception ignored) {
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
