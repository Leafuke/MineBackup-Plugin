package org.leafuke.mineBackupPlugin;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

public final class LocalSaveCoordinator {
    private LocalSaveCoordinator() {
    }

    public static SaveResult save(MineBackupPlugin plugin, String category, String operationLabel) {
        BackupLogger logger = plugin.getBackupLogger();
        ensurePrimaryThread(operationLabel);

        long totalStart = System.currentTimeMillis();
        boolean partialFailure = false;

        long playerPhaseStart = System.currentTimeMillis();
        boolean serverPlayerFlushSucceeded = true;
        try {
            Bukkit.getServer().savePlayers();
        } catch (Exception e) {
            serverPlayerFlushSucceeded = false;
            partialFailure = true;
            logger.error(category, operationLabel + ": savePlayers() failed: " + e.getMessage());
        }

        int playerCount = 0;
        int savedPlayers = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            playerCount++;
            try {
                player.saveData();
                savedPlayers++;
            } catch (Exception e) {
                partialFailure = true;
                logger.error(category, operationLabel + ": failed to save player '" + player.getName()
                        + "': " + e.getMessage());
            }
        }
        long playerPhaseMillis = System.currentTimeMillis() - playerPhaseStart;

        long worldPhaseStart = System.currentTimeMillis();
        int worldCount = 0;
        int savedWorlds = 0;
        for (World world : Bukkit.getWorlds()) {
            worldCount++;
            try {
                world.save();
                savedWorlds++;
            } catch (Exception e) {
                partialFailure = true;
                logger.error(category, operationLabel + ": failed to save world '" + world.getName()
                        + "': " + e.getMessage());
            }
        }
        long worldPhaseMillis = System.currentTimeMillis() - worldPhaseStart;
        long totalMillis = System.currentTimeMillis() - totalStart;

        SaveResult result = new SaveResult(
                operationLabel,
                serverPlayerFlushSucceeded,
                playerCount,
                savedPlayers,
                worldCount,
                savedWorlds,
                playerPhaseMillis,
                worldPhaseMillis,
                totalMillis,
                partialFailure
        );

        logger.info(category, result.toLogMessage());
        return result;
    }

    private static void ensurePrimaryThread(String operationLabel) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException(operationLabel + " must run on the primary server thread");
        }
    }

    public static final class SaveResult {
        private final String operationLabel;
        private final boolean serverPlayerFlushSucceeded;
        private final int playerCount;
        private final int savedPlayers;
        private final int worldCount;
        private final int savedWorlds;
        private final long playerPhaseMillis;
        private final long worldPhaseMillis;
        private final long totalMillis;
        private final boolean partialFailure;

        private SaveResult(String operationLabel,
                           boolean serverPlayerFlushSucceeded,
                           int playerCount,
                           int savedPlayers,
                           int worldCount,
                           int savedWorlds,
                           long playerPhaseMillis,
                           long worldPhaseMillis,
                           long totalMillis,
                           boolean partialFailure) {
            this.operationLabel = operationLabel;
            this.serverPlayerFlushSucceeded = serverPlayerFlushSucceeded;
            this.playerCount = playerCount;
            this.savedPlayers = savedPlayers;
            this.worldCount = worldCount;
            this.savedWorlds = savedWorlds;
            this.playerPhaseMillis = playerPhaseMillis;
            this.worldPhaseMillis = worldPhaseMillis;
            this.totalMillis = totalMillis;
            this.partialFailure = partialFailure;
        }

        public boolean isPartialFailure() {
            return partialFailure;
        }

        public String toLogMessage() {
            return operationLabel + " completed: players=" + savedPlayers + "/" + playerCount
                    + ", worlds=" + savedWorlds + "/" + worldCount
                    + ", playerPhase=" + playerPhaseMillis + "ms"
                    + ", worldPhase=" + worldPhaseMillis + "ms"
                    + ", total=" + totalMillis + "ms"
                    + ", savePlayers=" + (serverPlayerFlushSucceeded ? "ok" : "failed")
                    + (partialFailure ? " (partial failure)" : "");
        }
    }
}
