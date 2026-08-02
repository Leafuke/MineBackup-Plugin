package com.leafuke.minebackup.plugin.runtime;

import com.leafuke.minebackup.plugin.command.SuggestionCache;
import com.leafuke.minebackup.plugin.config.ConfigStore;
import com.leafuke.minebackup.plugin.config.PluginConfig;
import com.leafuke.minebackup.plugin.dedicated.DedicatedRestoreManager;
import com.leafuke.minebackup.plugin.dedicated.DedicatedRestoreSession;
import com.leafuke.minebackup.plugin.knotlink.KnotLinkClient;
import com.leafuke.minebackup.plugin.knotlink.protocol.KnotLinkRequest;
import com.leafuke.minebackup.plugin.knotlink.protocol.KnotLinkResponse;
import com.leafuke.minebackup.plugin.logging.AuditLog;
import com.leafuke.minebackup.plugin.message.MessageService;
import com.leafuke.minebackup.plugin.platform.BukkitWorldAccess;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public final class PluginRuntime implements AutoCloseable {
    private static final String MINIMUM_MAIN_VERSION = "1.16.0";
    private static final long HANDSHAKE_TTL_NANOS = Duration.ofSeconds(5).toNanos();

    private final JavaPlugin plugin;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2,
            task -> daemon(task, "minebackup-scheduler"));
    private final ExecutorService ioExecutor = Executors.newFixedThreadPool(2,
            task -> daemon(task, "minebackup-io"));
    private final ConfigStore configStore;
    private final MessageService messages;
    private final BukkitWorldAccess platform;
    private final OperationCoordinator operations;
    private final WorldSaveController worldSave;
    private final KnotLinkClient knotLink;
    private final DedicatedRestoreManager dedicatedRestore;
    private final AutoBackupScheduler autoBackup;
    private final SuggestionCache suggestions = new SuggestionCache();
    private final Map<UUID, CompletableFuture<Void>> completions = new ConcurrentHashMap<>();
    private final Object handshakeLock = new Object();
    private final Object restoreAnnouncementLock = new Object();
    private final List<ScheduledFuture<?>> restoreAnnouncements = new ArrayList<>();

    private volatile PluginConfig config;
    private volatile AuditLog audit;
    private volatile PendingHandshake pendingHandshake;
    private volatile String mainVersion = "unknown";

    public PluginRuntime(JavaPlugin plugin) throws IOException {
        this.plugin = plugin;
        configStore = new ConfigStore(plugin.getDataFolder().toPath(), plugin.getLogger());
        config = configStore.load();
        messages = new MessageService(plugin, config.localization());
        platform = new BukkitWorldAccess(plugin);
        operations = new OperationCoordinator(scheduler);
        worldSave = new WorldSaveController(platform, scheduler,
                Duration.ofSeconds(config.backup().freezeTimeoutSeconds()));
        knotLink = new KnotLinkClient(plugin.getLogger());
        dedicatedRestore = new DedicatedRestoreManager(
                plugin.getDataFolder().toPath().resolve("restart"), ioExecutor, plugin.getLogger());
        audit = new AuditLog(plugin.getDataFolder().toPath(), plugin.getLogger(), config.logging());
        autoBackup = new AutoBackupScheduler(scheduler,
                () -> beginCurrentBackup(null, "[auto]", OperationCoordinator.Origin.AUTO));
    }

    public void start() {
        dedicatedRestore.loadLastResult();
        knotLink.startSubscriber(this::handleSignal);
        if (config.autoBackup().enabled()) {
            autoBackup.start(config.autoBackup().intervalMinutes());
        }
        refreshCurrentBackups();
    }

    public MessageService messages() { return messages; }
    public SuggestionCache suggestions() { return suggestions; }
    public PluginConfig config() { return config; }

    public void save(CommandSender sender) {
        Optional<OperationCoordinator.Operation> started = operations.begin(
                OperationCoordinator.Type.SAVE, origin(sender), sender.getName(), "all-loaded-worlds");
        if (started.isEmpty()) {
            messages.send(sender, "busy");
            return;
        }
        OperationCoordinator.Operation operation = started.orElseThrow();
        audit.operation(operation, "started", "local save");
        messages.send(sender, "save_started");
        worldSave.saveOnly().whenComplete((paths, error) -> platform.executeMain(() -> {
            if (error == null) {
                finish(operation, OperationCoordinator.Outcome.SUCCESS, "saved " + paths.size() + " worlds");
                messages.send(sender, "save_success", paths.size());
            } else {
                finish(operation, OperationCoordinator.Outcome.FAILED, message(error));
                messages.send(sender, "save_failed", message(error));
            }
        }));
    }

    public void backupCurrent(CommandSender sender, String comment) {
        CompletableFuture<Void> completion = beginCurrentBackup(sender, comment, origin(sender));
        if (completion == null) {
            messages.send(sender, "busy");
        }
    }

    private CompletableFuture<Void> beginCurrentBackup(
            CommandSender sender,
            String comment,
            OperationCoordinator.Origin origin) {
        Optional<OperationCoordinator.Operation> started = operations.begin(
                OperationCoordinator.Type.CURRENT_BACKUP, origin,
                sender == null ? "scheduler" : sender.getName(), "current-world");
        if (started.isEmpty()) {
            return sender == null ? CompletableFuture.completedFuture(null) : null;
        }
        OperationCoordinator.Operation operation = started.orElseThrow();
        CompletableFuture<Void> completion = register(operation);
        audit.operation(operation, "started", "current world backup");
        if (sender != null) {
            messages.send(sender, "backup_submitted", operation.id());
        } else {
            broadcast("auto_backup_submitted");
        }
        KnotLinkRequest request = KnotLinkRequest.command("BACKUP").conversation(operation.id())
                .field("current_save", true);
        if (comment != null && !comment.isBlank()) {
            request.field("comment", comment);
        }
        submitMutation(operation, request);
        armTimeout(operation, config.backup().freezeTimeoutSeconds());
        return completion;
    }

    public void targetBackup(CommandSender sender, String configId, String folder, String comment) {
        Optional<OperationCoordinator.Operation> started = operations.begin(
                OperationCoordinator.Type.TARGET_BACKUP, origin(sender), sender.getName(), configId + "/" + folder);
        if (started.isEmpty()) {
            messages.send(sender, "busy");
            return;
        }
        OperationCoordinator.Operation operation = started.orElseThrow();
        register(operation);
        audit.operation(operation, "started", "target backup");
        KnotLinkRequest request = KnotLinkRequest.command("BACKUP").conversation(operation.id())
                .field("config_id", configId).field("folder", folder);
        if (!comment.isBlank()) {
            request.field("comment", comment);
        }
        messages.send(sender, "backup_submitted", operation.id());
        submitMutation(operation, request);
        armTimeout(operation, 300);
    }

    public void restore(CommandSender sender, String file) {
        DedicatedRestoreManager.Availability availability = dedicatedRestore.availability(
                config.dedicatedRestore(), Path.of("").toAbsolutePath());
        if (!availability.available()) {
            messages.send(sender, "restore_unavailable", availability.reason());
            return;
        }
        Optional<OperationCoordinator.Operation> started = operations.beginRestoreCountdown(
                origin(sender), sender.getName(), file.isBlank() ? "latest" : file,
                config.restore().countdownSeconds(), id -> submitRestore(id, file));
        if (started.isEmpty()) {
            messages.send(sender, "busy");
            return;
        }
        OperationCoordinator.Operation operation = started.orElseThrow();
        register(operation);
        audit.operation(operation, "countdown", "restore preflight passed");
        announceRestoreCountdown(operation, file, config.restore().countdownSeconds());
        if (config.restore().countdownSeconds() == 0) {
            operations.confirmRestore();
        }
    }

    public void confirmRestore(CommandSender sender) {
        if (operations.confirmRestore()) {
            messages.send(sender, "restore_confirmed");
        } else {
            messages.send(sender, "no_pending_restore");
        }
    }

    public void cancelRestore(CommandSender sender) {
        if (operations.cancelPendingRestore()) {
            clearRestoreAnnouncements();
            OperationCoordinator.OperationResult result = operations.lastResult().orElseThrow();
            completeWaiter(result.operation().id());
            audit.operation(result.operation(), "cancelled", result.detail());
            broadcast("restore_cancelled");
        } else {
            messages.send(sender, "no_pending_restore");
        }
    }

    private void submitRestore(UUID id, String file) {
        OperationCoordinator.Operation operation = operations.active().filter(value -> value.id().equals(id)).orElse(null);
        if (operation == null) {
            return;
        }
        clearRestoreAnnouncements();
        broadcast("restore_submitted");
        audit.operation(operation, "submitted", "restore submitted to FolderRewind");
        KnotLinkRequest request = KnotLinkRequest.command("RESTORE").conversation(id)
                .field("current_save", true);
        if (!file.isBlank()) {
            request.field("file", file);
        }
        submitMutation(operation, request);
        armTimeout(operation, config.dedicatedRestore().operationTimeoutSeconds());
    }

    private void submitMutation(OperationCoordinator.Operation operation, KnotLinkRequest request) {
        knotLink.query(request).whenComplete((response, error) -> {
            if (error != null || !response.isOk()) {
                String detail = error == null ? response.displayMessage() : message(error);
                failOperation(operation, detail);
            } else {
                operations.transition(operation.id(), OperationCoordinator.Phase.WAITING_BACKEND);
            }
        });
    }

    public void listConfigs(CommandSender sender) {
        queryList(sender, "catalog_configs", "list_configs_title", new Object[0],
                KnotLinkRequest.command("LIST_CONFIGS").conversation(), values -> {
            List<String> ids = values.stream().map(value -> value.split(",", 2)[0].trim()).toList();
            suggestions.put("configs", ids);
        });
    }

    public void listFolders(CommandSender sender, String configId) {
        queryList(sender, "catalog_folders", "list_folders_title", new Object[]{configId},
                KnotLinkRequest.command("LIST_FOLDERS").conversation()
                .field("config_id", configId),
                values -> suggestions.put("folders:" + configId, values));
    }

    public void listBackups(CommandSender sender, String configId, String folder) {
        queryList(sender, "catalog_backups", "list_backups_title", new Object[]{configId, folder},
                KnotLinkRequest.command("LIST_BACKUPS").conversation()
                .field("config_id", configId).field("folder", folder),
                values -> suggestions.put("backups:" + configId + ":" + folder, values));
    }

    private void refreshCurrentBackups() {
        knotLink.query(KnotLinkRequest.command("LIST_BACKUPS").conversation().field("current_save", true))
                .thenAccept(response -> {
                    if (response.isOk()) {
                        suggestions.put("backups:current", splitList(response.data()));
                    }
                }).exceptionally(error -> null);
    }

    private void queryList(
            CommandSender sender,
            String catalogKey,
            String titleKey,
            Object[] titleArguments,
            KnotLinkRequest request,
            java.util.function.Consumer<List<String>> cache) {
        messages.send(sender, "query_started", messages.text(sender, catalogKey));
        knotLink.query(request).whenComplete((response, error) -> platform.executeMain(() -> {
            if (error != null || !response.isOk()) {
                messages.send(sender, "request_failed",
                        error == null ? response.displayMessage() : message(error));
                return;
            }
            List<String> values = splitList(response.data());
            cache.accept(values);
            if (values.isEmpty()) {
                messages.send(sender, "list_empty");
            } else {
                messages.send(sender, titleKey, titleArguments);
                values.forEach(value -> messages.send(sender, "list_entry", value));
            }
        }));
    }

    public void startAutoBackup(CommandSender sender, int minutes) {
        CompletableFuture.runAsync(() -> {
            try {
                config = configStore.setAutoBackupInterval(minutes);
                autoBackup.start(minutes);
                platform.executeMain(() -> messages.send(sender, "auto_started", minutes));
            } catch (Exception exception) {
                platform.executeMain(() -> messages.send(sender, "request_failed", message(exception)));
            }
        }, ioExecutor);
    }

    public void stopAutoBackup(CommandSender sender) {
        CompletableFuture.runAsync(() -> {
            try {
                config = configStore.setAutoBackupInterval(0);
                autoBackup.stop();
                platform.executeMain(() -> messages.send(sender, "auto_stopped"));
            } catch (Exception exception) {
                platform.executeMain(() -> messages.send(sender, "request_failed", message(exception)));
            }
        }, ioExecutor);
    }

    public void reload(CommandSender sender) {
        CompletableFuture.runAsync(() -> {
            try {
                PluginConfig loaded = configStore.load();
                audit.reconfigure(loaded.logging());
                messages.configure(loaded.localization());
                config = loaded;
                worldSave.setFreezeTimeout(Duration.ofSeconds(loaded.backup().freezeTimeoutSeconds()));
                suggestions.clear();
                if (loaded.autoBackup().enabled()) {
                    autoBackup.start(loaded.autoBackup().intervalMinutes());
                } else {
                    autoBackup.stop();
                }
                refreshCurrentBackups();
                platform.executeMain(() -> messages.send(sender, "reload_success"));
            } catch (Exception exception) {
                plugin.getLogger().log(Level.SEVERE, "Failed to reload MineBackup configuration", exception);
                platform.executeMain(() -> messages.send(sender, "request_failed", message(exception)));
            }
        }, ioExecutor);
    }

    public List<String> status(CommandSender sender) {
        List<String> lines = new ArrayList<>();
        KnotLinkClient.Status link = knotLink.status();
        lines.add(messages.text(sender, "status_title"));
        lines.add(messages.text(sender, "status_version", version()));
        lines.add(messages.text(sender, "status_knotlink", messages.text(sender,
                link.connected() ? "state_connected" : "state_reconnecting")));
        lines.add(messages.text(sender, "status_main", "unknown".equals(mainVersion)
                ? messages.text(sender, "state_unknown") : mainVersion));
        lines.add(messages.text(sender, "status_operation", operations.active()
                .map(value -> messages.text(sender, "status_operation_active",
                        messages.text(sender, operationKey(value.type())),
                        messages.text(sender, phaseKey(value.phase())), value.id()))
                .orElseGet(() -> messages.text(sender, "state_idle"))));
        lines.add(messages.text(sender, "status_autosave", messages.text(sender,
                worldSave.status().frozen() ? "state_frozen" : "state_normal")));
        AutoBackupScheduler.Status auto = autoBackup.status();
        lines.add(messages.text(sender, "status_auto", auto.enabled()
                ? messages.text(sender, "status_auto_enabled", auto.intervalMinutes(),
                        auto.nextRun().map(Instant::toString).orElse("?"))
                : messages.text(sender, "state_disabled")));
        Optional<DedicatedRestoreSession> last = dedicatedRestore.lastResult();
        lines.add(messages.text(sender, "status_sidecar", last
                .map(value -> value.state() + (value.detail().isBlank() ? "" : ": " + value.detail()))
                .orElseGet(() -> messages.text(sender, "state_none"))));
        return List.copyOf(lines);
    }

    private void handleSignal(Map<String, String> fields) {
        String event = fields.get("event");
        if (event == null) {
            return;
        }
        switch (event) {
            case "handshake" -> handleHandshake(fields);
            case "pre_hot_backup" -> handlePreHotBackup(fields);
            case "pre_hot_restore" -> handlePreHotRestore(fields);
            case "backup_success" -> completeBackup(fields, OperationCoordinator.Outcome.SUCCESS,
                    fields.getOrDefault("file", "backup created"));
            case "backup_failed" -> completeBackup(fields, OperationCoordinator.Outcome.FAILED,
                    first(fields, "error", "message"));
            case "command_completed" -> {
                if ("BACKUP".equalsIgnoreCase(fields.get("command"))) {
                    completeBackup(fields, "no_changes".equalsIgnoreCase(fields.get("result"))
                            ? OperationCoordinator.Outcome.NO_CHANGES : OperationCoordinator.Outcome.SUCCESS,
                            first(fields, "file", "result"));
                }
            }
            case "command_failed" -> handleCommandFailed(fields);
            case "restore_cancelled" -> completeRestoreFailure(fields, first(fields, "reason", "message"));
            case "restore_finished" -> {
                if (!"success".equalsIgnoreCase(fields.get("status"))) {
                    completeRestoreFailure(fields, first(fields, "reason", "error"));
                }
            }
            default -> {
            }
        }
    }

    private void handleHandshake(Map<String, String> fields) {
        // 握手只保留 5 秒且绑定 operation/world；旧信号或其他会话不能推进当前操作。
        String action = fields.get("action");
        String world = fields.get("world");
        String backendVersion = fields.get("version");
        String minimumPlugin = fields.get("min_mod_version");
        if (action == null || world == null || world.isBlank() || backendVersion == null
                || minimumPlugin == null || !(action.equalsIgnoreCase("backup") || action.equalsIgnoreCase("restore"))) {
            plugin.getLogger().warning("Rejected incomplete KnotLink handshake");
            return;
        }
        if (!VersionNumber.isAtLeast(backendVersion, MINIMUM_MAIN_VERSION)
                || !VersionNumber.isAtLeast(version(), minimumPlugin)) {
            plugin.getLogger().warning("Rejected incompatible FolderRewind handshake");
            return;
        }
        boolean restore = action.equalsIgnoreCase("restore");
        if (restore && !dedicatedRestore.availability(
                config.dedicatedRestore(), Path.of("").toAbsolutePath()).available()) {
            plugin.getLogger().warning("Rejected restore handshake because Sidecar preflight failed");
            return;
        }

        OperationCoordinator.Operation active = operations.active().orElse(null);
        if (active == null) {
            UUID id = uuid(fields.get("request_id"));
            active = operations.adopt(id,
                    restore ? OperationCoordinator.Type.RESTORE : OperationCoordinator.Type.CURRENT_BACKUP,
                    world).orElse(null);
            if (active != null) {
                register(active);
                audit.operation(active, "adopted", "remote FolderRewind operation");
            }
        }
        if (active == null
                || (restore && active.type() != OperationCoordinator.Type.RESTORE)
                || (!restore && active.type() != OperationCoordinator.Type.CURRENT_BACKUP)) {
            plugin.getLogger().warning("Rejected FolderRewind handshake while another operation is active");
            return;
        }
        synchronized (handshakeLock) {
            if (pendingHandshake != null && pendingHandshake.expiresAtNanos() > System.nanoTime()) {
                plugin.getLogger().warning("Rejected duplicate KnotLink handshake");
                return;
            }
            pendingHandshake = new PendingHandshake(action.toLowerCase(Locale.ROOT), world,
                    active.id(), System.nanoTime() + HANDSHAKE_TTL_NANOS);
        }
        mainVersion = backendVersion;
        broadcast("handshake_connected", backendVersion);
        UUID operationId = active.id();
        knotLink.query(KnotLinkRequest.command("HANDSHAKE_RESPONSE").conversation()
                        .field("mod_version", version()))
                .whenComplete((response, error) -> {
                    if (error != null || !response.isOk()) {
                        clearHandshake(operationId);
                        failOperation(activeOperation(operationId), error == null
                                ? response.displayMessage() : message(error));
                    }
                });
    }

    private void handlePreHotBackup(Map<String, String> fields) {
        PendingHandshake handshake = consumeHandshake("backup", fields.get("world"));
        OperationCoordinator.Operation operation = handshake == null ? null : activeOperation(handshake.operationId());
        if (operation == null || operation.type() != OperationCoordinator.Type.CURRENT_BACKUP) {
            plugin.getLogger().warning("Rejected pre_hot_backup without a fresh matching handshake");
            return;
        }
        operations.transition(operation.id(), OperationCoordinator.Phase.SAVING);
        broadcast("backup_preparing");
        worldSave.saveAndFreeze(operation.id()).whenComplete((paths, error) -> {
            if (error != null) {
                failOperation(operation, message(error));
                return;
            }
            knotLink.query(KnotLinkRequest.command("WORLD_SAVED").conversation(operation.id()))
                    .whenComplete((response, queryError) -> {
                        if (queryError != null || !response.isOk()) {
                            failOperation(operation, queryError == null
                                    ? response.displayMessage() : message(queryError));
                        } else {
                            operations.transition(operation.id(), OperationCoordinator.Phase.WAITING_BACKEND);
                        }
                    });
        });
    }

    private void handlePreHotRestore(Map<String, String> fields) {
        PendingHandshake handshake = consumeHandshake("restore", fields.get("world"));
        OperationCoordinator.Operation operation = handshake == null ? null : activeOperation(handshake.operationId());
        if (operation == null || operation.type() != OperationCoordinator.Type.RESTORE) {
            plugin.getLogger().warning("Rejected pre_hot_restore without a fresh matching handshake");
            return;
        }
        operations.transition(operation.id(), OperationCoordinator.Phase.SAVING);
        broadcast("restore_preparing");
        worldSave.saveOnly().whenComplete((paths, saveError) -> {
            if (saveError != null) {
                failOperation(operation, message(saveError));
                return;
            }
            dedicatedRestore.prepareAsync(config.dedicatedRestore(), Path.of("").toAbsolutePath(), paths,
                    handshake.world(), operation.id(), operation.origin().name())
                    .whenComplete((handoff, handoffError) -> {
                        if (handoffError != null || !handoff.accepted()) {
                            failOperation(operation, handoffError == null ? handoff.reason() : message(handoffError));
                            return;
                        }
                        operations.transition(operation.id(), OperationCoordinator.Phase.SIDECAR_HANDOFF);
                        finish(operation, OperationCoordinator.Outcome.HANDOFF_ACCEPTED, "sidecar ready");
                        broadcast("restore_handoff");
                        platform.disconnectPlayersAndShutdown(player -> messages.text(player, "restore_kick"));
                    });
        });
    }

    private void completeBackup(
            Map<String, String> fields,
            OperationCoordinator.Outcome outcome,
            String detail) {
        OperationCoordinator.Operation operation = operations.active().orElse(null);
        if (operation == null || (operation.type() != OperationCoordinator.Type.CURRENT_BACKUP
                && operation.type() != OperationCoordinator.Type.TARGET_BACKUP) || !matches(operation, fields)) {
            return;
        }
        resumeAutosave(operation.id());
        finish(operation, outcome, detail);
        if (outcome == OperationCoordinator.Outcome.FAILED) {
            broadcast("backup_failed", detail);
        } else if (outcome == OperationCoordinator.Outcome.NO_CHANGES) {
            broadcast("backup_no_changes");
        } else {
            broadcast("backup_success", detail);
        }
        refreshCurrentBackups();
    }

    private void handleCommandFailed(Map<String, String> fields) {
        OperationCoordinator.Operation operation = operations.active().orElse(null);
        if (operation == null || !matches(operation, fields)) {
            return;
        }
        String command = fields.get("command");
        if ((operation.type() == OperationCoordinator.Type.RESTORE && "RESTORE".equalsIgnoreCase(command))
                || ((operation.type() == OperationCoordinator.Type.CURRENT_BACKUP
                || operation.type() == OperationCoordinator.Type.TARGET_BACKUP)
                && "BACKUP".equalsIgnoreCase(command))) {
            failOperation(operation, first(fields, "error", "message"));
        }
    }

    private void completeRestoreFailure(Map<String, String> fields, String detail) {
        OperationCoordinator.Operation operation = operations.active().orElse(null);
        if (operation != null && operation.type() == OperationCoordinator.Type.RESTORE && matches(operation, fields)) {
            failOperation(operation, detail);
        }
    }

    private void armTimeout(OperationCoordinator.Operation operation, int seconds) {
        scheduler.schedule(() -> {
            if (operations.active().filter(value -> value.id().equals(operation.id())).isPresent()) {
                resumeAutosave(operation.id());
                finish(operation, OperationCoordinator.Outcome.FAILED, "operation timed out");
                String outerKey = operation.type() == OperationCoordinator.Type.RESTORE
                        ? "restore_failed" : "operation_failed";
                platform.broadcast(receiver -> messages.text(receiver, outerKey,
                        messages.text(receiver, "operation_timeout")));
            }
        }, seconds, TimeUnit.SECONDS);
    }

    private CompletableFuture<Void> register(OperationCoordinator.Operation operation) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        completions.put(operation.id(), future);
        return future;
    }

    private void finish(
            OperationCoordinator.Operation operation,
            OperationCoordinator.Outcome outcome,
            String detail) {
        if (operation != null && operations.complete(operation.id(), outcome, detail)) {
            if (operation.type() == OperationCoordinator.Type.RESTORE) {
                clearRestoreAnnouncements();
            }
            audit.operation(operation, "finished:" + outcome, detail);
            completeWaiter(operation.id());
        }
    }

    private void failOperation(OperationCoordinator.Operation operation, String detail) {
        if (operation == null) {
            return;
        }
        resumeAutosave(operation.id());
        finish(operation, OperationCoordinator.Outcome.FAILED, detail);
        broadcast(operation.type() == OperationCoordinator.Type.RESTORE
                ? "restore_failed" : "operation_failed", detail);
    }

    private void completeWaiter(UUID id) {
        CompletableFuture<Void> future = completions.remove(id);
        if (future != null) {
            future.complete(null);
        }
    }

    private OperationCoordinator.Operation activeOperation(UUID id) {
        return operations.active().filter(value -> value.id().equals(id)).orElse(null);
    }

    private void announceRestoreCountdown(
            OperationCoordinator.Operation operation,
            String file,
            int seconds) {
        clearRestoreAnnouncements();
        if (seconds <= 0) {
            return;
        }
        platform.broadcast(receiver -> messages.text(receiver, "restore_countdown_started", seconds,
                file.isBlank() ? messages.text(receiver, "state_latest_backup") : file));
        broadcast("restore_controls");
        // 长倒计时仅播报关键节点，最后五秒逐秒提示，避免刷屏。
        for (int remaining : List.of(60, 30, 10, 5, 4, 3, 2, 1)) {
            int delay = seconds - remaining;
            if (delay <= 0) {
                continue;
            }
            ScheduledFuture<?> future = scheduler.schedule(() -> {
                boolean pending = operations.active()
                        .filter(value -> value.id().equals(operation.id()))
                        .filter(value -> value.phase() == OperationCoordinator.Phase.COUNTDOWN)
                        .isPresent();
                if (pending) {
                    broadcast("restore_countdown_tick", remaining);
                }
            }, delay, TimeUnit.SECONDS);
            synchronized (restoreAnnouncementLock) {
                restoreAnnouncements.add(future);
            }
        }
    }

    private void clearRestoreAnnouncements() {
        synchronized (restoreAnnouncementLock) {
            restoreAnnouncements.forEach(future -> future.cancel(false));
            restoreAnnouncements.clear();
        }
    }

    private void resumeAutosave(UUID operationId) {
        platform.executeMain(() -> {
            if (worldSave.unfreeze(operationId)) {
                broadcast("autosave_resumed");
            }
        });
    }

    private void broadcast(String key, Object... arguments) {
        platform.broadcast(receiver -> messages.text(receiver, key, arguments));
    }

    private static String operationKey(OperationCoordinator.Type type) {
        return switch (type) {
            case SAVE -> "operation_save";
            case CURRENT_BACKUP -> "operation_current_backup";
            case TARGET_BACKUP -> "operation_target_backup";
            case RESTORE -> "operation_restore";
        };
    }

    private static String phaseKey(OperationCoordinator.Phase phase) {
        return switch (phase) {
            case COUNTDOWN -> "phase_countdown";
            case SUBMITTED -> "phase_submitted";
            case SAVING -> "phase_saving";
            case WAITING_BACKEND -> "phase_waiting_backend";
            case SIDECAR_HANDOFF -> "phase_sidecar_handoff";
        };
    }

    private PendingHandshake consumeHandshake(String action, String world) {
        synchronized (handshakeLock) {
            // 无论匹配与否都一次性消费，拒绝重放同一 pre_hot_* 信号。
            PendingHandshake current = pendingHandshake;
            pendingHandshake = null;
            if (current == null || System.nanoTime() > current.expiresAtNanos()
                    || !current.action().equals(action) || !current.world().equals(world)) {
                return null;
            }
            return current;
        }
    }

    private void clearHandshake(UUID operationId) {
        synchronized (handshakeLock) {
            if (pendingHandshake != null && pendingHandshake.operationId().equals(operationId)) {
                pendingHandshake = null;
            }
        }
    }

    private static boolean matches(OperationCoordinator.Operation operation, Map<String, String> fields) {
        String request = fields.get("request_id");
        return request == null || request.isBlank() || operation.id().toString().equalsIgnoreCase(request);
    }

    private static UUID uuid(String value) {
        try {
            return UUID.fromString(value == null ? "" : value);
        } catch (IllegalArgumentException exception) {
            return UUID.randomUUID();
        }
    }

    private String version() {
        return plugin.getDescription().getVersion();
    }

    private static OperationCoordinator.Origin origin(CommandSender sender) {
        return sender instanceof Player ? OperationCoordinator.Origin.PLAYER : OperationCoordinator.Origin.CONSOLE;
    }

    private static List<String> splitList(String data) {
        if (data == null || data.isBlank()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String value : data.split(";", -1)) {
            String trimmed = value.trim();
            if (!trimmed.isEmpty() && !trimmed.contains("\u0000")) {
                values.add(trimmed);
            }
        }
        return List.copyOf(values);
    }

    private static String first(Map<String, String> fields, String first, String second) {
        String value = fields.get(first);
        if (value == null || value.isBlank()) {
            value = fields.get(second);
        }
        return value == null || value.isBlank() ? "unknown" : value;
    }

    private static String message(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static Thread daemon(Runnable task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        return thread;
    }

    @Override
    public void close() {
        clearRestoreAnnouncements();
        autoBackup.close();
        worldSave.close();
        knotLink.close();
        audit.close();
        scheduler.shutdownNow();
        ioExecutor.shutdownNow();
        completions.values().forEach(future -> future.complete(null));
        completions.clear();
    }

    private record PendingHandshake(String action, String world, UUID operationId, long expiresAtNanos) {
    }
}
