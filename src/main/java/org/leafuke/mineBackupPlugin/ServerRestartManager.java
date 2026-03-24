package org.leafuke.mineBackupPlugin;

import org.bukkit.Bukkit;
import org.leafuke.mineBackupPlugin.sidecar.RestartSidecarMain;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;

public final class ServerRestartManager {
    private static final String RESTART_FLAG_FILE = ".minebackup-restart";

    private ServerRestartManager() {
    }

    public static void prepareRestart(MineBackupPlugin plugin) {
        BackupLogger logger = plugin.getBackupLogger();

        if (!Config.isRestartEnabled()) {
            logger.info("RESTART", "Auto restart disabled, server will only shut down.");
            return;
        }

        String method = Config.getRestartMethod().toLowerCase();
        logger.info("RESTART", "Preparing server restart (method: " + method + ")");

        switch (method) {
            case "spigot" -> handleSpigotRestart(logger);
            case "script" -> handleScriptRestart(logger);
            case "sidecar" -> handleSidecarRestart(plugin, logger);
            case "none" -> logger.info("RESTART", "Restart method is none, server will only shut down.");
            default -> logger.warn("RESTART", "Unknown restart method: " + method + ", falling back to plain shutdown.");
        }
    }

    public static void cleanupRestartFlag(BackupLogger logger) {
        File flagFile = new File(RESTART_FLAG_FILE);
        if (flagFile.exists() && flagFile.delete() && logger != null) {
            logger.info("RESTART", "Cleared restart flag from previous restore restart.");
        }
    }

    public static boolean isPostRestoreRestart() {
        return new File(RESTART_FLAG_FILE).exists();
    }

    private static void handleSpigotRestart(BackupLogger logger) {
        writeRestartFlag(logger);
        try {
            logger.info("RESTART", "Calling Spigot.restart(); restart script must be configured in spigot.yml.");
            Bukkit.getServer().spigot().restart();
        } catch (Exception e) {
            logger.warn("RESTART", "Spigot.restart() failed: " + e.getMessage()
                    + ". Falling back to plain shutdown. Check spigot.yml restart-script.");
        }
    }

    private static void handleScriptRestart(BackupLogger logger) {
        writeRestartFlag(logger);
        logger.info("RESTART", "Restart method 'script' selected. External environment must relaunch the server using: "
                + Config.getRestartScriptPath());
    }

    private static void handleSidecarRestart(MineBackupPlugin plugin, BackupLogger logger) {
        writeRestartFlag(logger);

        Path dataDirectory = plugin.getDataFolder().toPath();
        RestartRelayStore.Session session;
        try {
            session = RestartRelayStore.createSession(
                    dataDirectory,
                    Config.getRestartScriptPath(),
                    Config.getSidecarRelayTimeoutSeconds()
            );
        } catch (IOException e) {
            logger.error("RESTART", "Failed to create sidecar relay session: " + e.getMessage());
            return;
        }

        HotRestoreState.relaySessionActive = true;
        HotRestoreState.sidecarReady = false;
        HotRestoreState.relaySessionDeadlineMillis = session.relayDeadlineMillis();
        HotRestoreState.lastRelaySequence = 0L;

        try {
            Process sidecarProcess = startSidecarProcess(plugin, session);
            logger.info("RESTART", "Sidecar process launched with pid " + sidecarProcess.pid());
        } catch (Exception e) {
            logger.error("RESTART", "Failed to launch sidecar process: " + e.getMessage());
            return;
        }

        long timeoutAt = System.currentTimeMillis() + Config.getSidecarStartTimeoutSeconds() * 1000L;
        while (System.currentTimeMillis() < timeoutAt) {
            if (RestartRelayStore.isSidecarReady(dataDirectory, session.id())) {
                HotRestoreState.sidecarReady = true;
                logger.info("RESTART", "Sidecar reported ready. Relay session id=" + session.id());
                return;
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        logger.warn("RESTART", "Sidecar did not report ready before timeout.");
    }

    private static Process startSidecarProcess(MineBackupPlugin plugin, RestartRelayStore.Session session)
            throws IOException, URISyntaxException {
        String javaBin = Path.of(System.getProperty("java.home"), "bin",
                isWindows() ? "java.exe" : "java").toString();
        String classpathEntry = Path.of(
                RestartSidecarMain.class.getProtectionDomain().getCodeSource().getLocation().toURI()
        ).toString();

        Path workDirectory = Path.of("").toAbsolutePath().normalize();
        ProcessBuilder builder = new ProcessBuilder(
                javaBin,
                "-cp",
                classpathEntry,
                RestartSidecarMain.class.getName(),
                "--data-dir",
                plugin.getDataFolder().getAbsolutePath(),
                "--work-dir",
                workDirectory.toString(),
                "--parent-pid",
                Long.toString(ProcessHandle.current().pid()),
                "--relay-timeout-seconds",
                Integer.toString(session.relayTimeoutSeconds()),
                "--session-id",
                session.id(),
                "--restart-script",
                session.restartScriptPath()
        );
        builder.directory(workDirectory.toFile());
        return builder.start();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static void writeRestartFlag(BackupLogger logger) {
        try {
            File flagFile = new File(RESTART_FLAG_FILE);
            try (FileWriter writer = new FileWriter(flagFile)) {
                writer.write("# MineBackup Restart Flag\n");
                writer.write("restart_requested=" + System.currentTimeMillis() + "\n");
                writer.write("reason=minebackup_restore\n");
                writer.write("timestamp=" + java.time.LocalDateTime.now() + "\n");
            }
            logger.debug("RESTART", "Wrote restart flag: " + flagFile.getAbsolutePath());
        } catch (IOException e) {
            logger.error("RESTART", "Failed to write restart flag: " + e.getMessage());
        }
    }
}
