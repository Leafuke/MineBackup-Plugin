package org.leafuke.mineBackupPlugin;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.leafuke.mineBackupPlugin.knotlink.OpenSocketQuerier;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class RestoreTask {
    private static final int SHUTDOWN_ACK_CONNECT_TIMEOUT_MS = 500;
    private static final int SHUTDOWN_ACK_READ_TIMEOUT_MS = 1000;

    public enum Phase {
        NONE("Idle"),
        WAITING_CONFIRM("Waiting Confirm"),
        COUNTDOWN("Countdown"),
        EXECUTING("Executing");

        private final String displayName;

        Phase(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    private static final AtomicReference<RestoreTask> CURRENT_TASK = new AtomicReference<>(null);

    private final MineBackupPlugin plugin;
    private final BackupLogger logger;
    private final LanguageManager languageManager;
    private final String restoreCommand;
    private final String initiator;
    private final boolean remote;
    private final long startTimeMillis;

    private volatile Phase phase = Phase.NONE;
    private BukkitTask countdownTask;
    private BukkitTask confirmTimeoutTask;
    private volatile int remainingSeconds;
    private final AtomicBoolean aborted = new AtomicBoolean(false);

    public RestoreTask(MineBackupPlugin plugin, String restoreCommand, String initiator) {
        this.plugin = plugin;
        this.logger = plugin.getBackupLogger();
        this.languageManager = plugin.getLanguageManager();
        this.restoreCommand = restoreCommand;
        this.initiator = initiator;
        this.remote = false;
        this.startTimeMillis = System.currentTimeMillis();
    }

    public RestoreTask(MineBackupPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getBackupLogger();
        this.languageManager = plugin.getLanguageManager();
        this.restoreCommand = null;
        this.initiator = "MineBackup Main Program";
        this.remote = true;
        this.startTimeMillis = System.currentTimeMillis();
    }

    public static RestoreTask getCurrentTask() {
        return CURRENT_TASK.get();
    }

    public static boolean hasActiveTask() {
        RestoreTask task = CURRENT_TASK.get();
        return task != null && task.phase != Phase.NONE;
    }

    public Phase getPhase() {
        return phase;
    }

    public String getInitiator() {
        return initiator;
    }

    public boolean isRemote() {
        return remote;
    }

    public boolean start() {
        if (!CURRENT_TASK.compareAndSet(null, this)) {
            return false;
        }

        if (remote) {
            languageManager.broadcastMessage("minebackup.restore.remote_initiated");
            if (Config.isRemoteRestoreCountdown()) {
                int seconds = Config.getRemoteCountdownSeconds();
                logger.info("RESTORE", "Remote restore triggered, starting " + seconds + " second countdown.");
                startCountdown(seconds);
            } else {
                logger.info("RESTORE", "Remote restore triggered, executing immediately.");
                performShutdown();
            }
            return true;
        }

        if (Config.isRequireConfirm()) {
            phase = Phase.WAITING_CONFIRM;
            int timeout = Config.getConfirmTimeoutSeconds();
            logger.info("RESTORE", "Restore requested by [" + initiator + "], waiting for confirmation. command="
                    + restoreCommand + ", timeout=" + timeout + "s");
            languageManager.broadcastMessage("minebackup.restore.confirm_prompt", String.valueOf(timeout));
            confirmTimeoutTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (phase == Phase.WAITING_CONFIRM) {
                    logger.warn("RESTORE", "Restore confirmation timed out.");
                    languageManager.broadcastMessage("minebackup.restore.confirm_timeout");
                    cleanup();
                }
            }, timeout * 20L);
            return true;
        }

        logger.info("RESTORE", "Restore requested by [" + initiator + "] without confirmation. command=" + restoreCommand);
        startCountdown(Config.getCountdownSeconds());
        return true;
    }

    public boolean confirm() {
        if (phase != Phase.WAITING_CONFIRM) {
            return false;
        }

        cancelTimer(confirmTimeoutTask);
        confirmTimeoutTask = null;
        logger.info("RESTORE", "Restore confirmed.");
        languageManager.broadcastMessage("minebackup.restore.confirmed");
        startCountdown(Config.getCountdownSeconds());
        return true;
    }

    public boolean abort(String reason) {
        if (phase == Phase.EXECUTING || phase == Phase.NONE) {
            return false;
        }
        if (!aborted.compareAndSet(false, true)) {
            return false;
        }

        logger.info("RESTORE", "Restore aborted during [" + phase.getDisplayName() + "], reason: " + reason);
        cancelTimer(countdownTask);
        cancelTimer(confirmTimeoutTask);
        countdownTask = null;
        confirmTimeoutTask = null;
        cleanup();
        languageManager.broadcastMessage("minebackup.restore.aborted");
        return true;
    }

    public void performShutdown() {
        phase = Phase.EXECUTING;
        HotRestoreState.isRestoring = true;
        HotRestoreState.waitingForServerStopAck = true;

        languageManager.broadcastMessage("minebackup.restore.executing");

        LocalSaveCoordinator.SaveResult saveResult =
                LocalSaveCoordinator.save(plugin, "RESTORE", "Restore shutdown pre-save");
        if (saveResult.isPartialFailure()) {
            logger.warn("RESTORE", "Restore shutdown pre-save finished with partial failure.");
        }

        int playerCount = Bukkit.getOnlinePlayers().size();
        logger.info("RESTORE", "Disconnecting " + playerCount + " player(s) before restore.");
        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                player.kickPlayer(languageManager.getTranslation(player, "minebackup.restore.kick"));
            } catch (Exception e) {
                logger.error("RESTORE", "Failed to disconnect player " + player.getName() + ": " + e.getMessage());
            }
        }

        sendShutdownAck();

        long totalTime = System.currentTimeMillis() - startTimeMillis;
        logger.info("RESTORE", "Restore shutdown pipeline completed in " + totalTime
                + "ms (initiator=" + initiator + ", remote=" + remote + ")");

        cleanup();
        ServerRestartManager.prepareRestart(plugin);
        logger.info("RESTORE", "Server shutting down for restore.");
        Bukkit.shutdown();
    }

    private void startCountdown(int seconds) {
        phase = Phase.COUNTDOWN;
        remainingSeconds = seconds;

        logger.info("RESTORE", "Restore countdown started: " + seconds + "s");
        languageManager.broadcastMessage("minebackup.restore.countdown_start", String.valueOf(seconds));

        countdownTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (aborted.get()) {
                    cancel();
                    return;
                }

                if (remainingSeconds <= 0) {
                    cancel();
                    onCountdownComplete();
                    return;
                }

                if (remainingSeconds <= 5 || remainingSeconds % 5 == 0) {
                    languageManager.broadcastMessage("minebackup.restore.countdown",
                            String.valueOf(remainingSeconds));
                }
                remainingSeconds--;
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    private void onCountdownComplete() {
        if (remote) {
            performShutdown();
            return;
        }

        phase = Phase.EXECUTING;
        HotRestoreState.isRestoring = true;
        logger.info("RESTORE", "Countdown finished, sending restore command: " + restoreCommand);
        languageManager.broadcastMessage("minebackup.restore.executing");
        OpenSocketQuerier.query(MineBackupPlugin.QUERIER_APP_ID, MineBackupPlugin.QUERIER_SOCKET_ID, restoreCommand);
    }

    private void sendShutdownAck() {
        String response = OpenSocketQuerier.queryBlocking(
                MineBackupPlugin.QUERIER_APP_ID,
                MineBackupPlugin.QUERIER_SOCKET_ID,
                "WORLD_SAVE_AND_EXIT_COMPLETE",
                SHUTDOWN_ACK_CONNECT_TIMEOUT_MS,
                SHUTDOWN_ACK_READ_TIMEOUT_MS
        );

        if (response == null || response.startsWith("ERROR:")) {
            logger.warn("RESTORE", "WORLD_SAVE_AND_EXIT_COMPLETE acknowledgement may not have arrived: " + response);
        } else {
            logger.info("RESTORE", "WORLD_SAVE_AND_EXIT_COMPLETE acknowledged with: " + response);
        }
    }

    private void cleanup() {
        phase = Phase.NONE;
        CURRENT_TASK.set(null);
    }

    private void cancelTimer(BukkitTask task) {
        if (task != null) {
            try {
                task.cancel();
            } catch (Exception ignored) {
            }
        }
    }
}
