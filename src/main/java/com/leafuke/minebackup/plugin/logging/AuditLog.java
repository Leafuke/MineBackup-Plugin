package com.leafuke.minebackup.plugin.logging;

import com.leafuke.minebackup.plugin.config.PluginConfig;
import com.leafuke.minebackup.plugin.runtime.OperationCoordinator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

public final class AuditLog implements AutoCloseable {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault());

    private final Path directory;
    private final Logger console;
    private final Logger fileLogger = Logger.getLogger("MineBackup-Audit-" + UUID.randomUUID());
    private FileHandler handler;

    public AuditLog(Path dataDirectory, Logger console, PluginConfig.Logging config) throws IOException {
        this.directory = dataDirectory.resolve("logs");
        this.console = console;
        fileLogger.setUseParentHandlers(false);
        reconfigure(config);
    }

    public synchronized void reconfigure(PluginConfig.Logging config) throws IOException {
        closeHandler();
        if (!config.enabled()) {
            return;
        }
        Files.createDirectories(directory);
        handler = new FileHandler(directory.resolve("operations-%g.log").toString(),
                Math.multiplyExact(config.maxSizeMib(), 1024 * 1024), config.retainedFiles(), true);
        handler.setEncoding("UTF-8");
        handler.setFormatter(new Formatter() {
            @Override
            public String format(LogRecord record) {
                return "[" + TIME.format(Instant.ofEpochMilli(record.getMillis())) + "] ["
                        + record.getLevel().getName() + "] " + record.getMessage() + System.lineSeparator();
            }
        });
        fileLogger.addHandler(handler);
    }

    public void operation(OperationCoordinator.Operation operation, String event, String detail) {
        String line = "event=" + safe(event)
                + " request_id=" + operation.id()
                + " type=" + operation.type()
                + " origin=" + operation.origin()
                + " actor=" + safe(operation.actor())
                + " target=" + safe(operation.target())
                + " detail=" + safe(detail);
        console.info("[AUDIT] " + line);
        fileLogger.log(Level.INFO, line);
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
    }

    @Override
    public synchronized void close() {
        closeHandler();
    }

    private void closeHandler() {
        if (handler != null) {
            fileLogger.removeHandler(handler);
            handler.flush();
            handler.close();
            handler = null;
        }
    }
}
