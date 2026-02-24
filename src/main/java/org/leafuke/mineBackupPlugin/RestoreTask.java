package org.leafuke.mineBackupPlugin;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.leafuke.mineBackupPlugin.knotlink.OpenSocketQuerier;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 还原任务管理器
 * <p>
 * 实现 PrimeBackup 风格的 "确认 → 倒计时 → 执行" 三阶段还原流程。
 * <ul>
 *   <li><b>命令发起</b>: WAITING_CONFIRM → COUNTDOWN → EXECUTING</li>
 *   <li><b>远程发起</b>: COUNTDOWN → EXECUTING（或直接 EXECUTING）</li>
 * </ul>
 * <p>
 * 整个流程中，任何管理员都可以通过 {@code /mb abort} 在 WAITING_CONFIRM 或 COUNTDOWN
 * 阶段取消还原（EXECUTING 阶段不可取消）。
 *
 * @see Config 配置项：countdown-seconds, require-confirm 等
 */
public class RestoreTask {

    /**
     * 还原阶段枚举
     */
    public enum Phase {
        /** 无活动任务 */
        NONE("无"),
        /** 等待 /mb confirm 确认 */
        WAITING_CONFIRM("等待确认"),
        /** 倒计时中 */
        COUNTDOWN("倒计时"),
        /** 正在执行（不可取消） */
        EXECUTING("执行中");

        private final String displayName;

        Phase(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    // ==================== 单例管理 ====================

    /** 当前活动的还原任务（同一时间只能有一个） */
    private static final AtomicReference<RestoreTask> currentTask = new AtomicReference<>(null);

    public static RestoreTask getCurrentTask() {
        return currentTask.get();
    }

    public static boolean hasActiveTask() {
        RestoreTask task = currentTask.get();
        return task != null && task.phase != Phase.NONE;
    }

    // ==================== 字段 ====================

    private final MineBackupPlugin plugin;
    private final BackupLogger logger;
    private final LanguageManager lm;

    /** 还原命令（命令发起时非 null，如 "RESTORE 1 0 backup.zip"） */
    private final String restoreCommand;
    /** 发起者标识 */
    private final String initiator;
    /** 是否为远程发起（pre_hot_restore 事件） */
    private final boolean isRemote;

    private volatile Phase phase = Phase.NONE;
    private BukkitTask countdownTask;
    private BukkitTask confirmTimeoutTask;
    private volatile int remainingSeconds;
    private final AtomicBoolean aborted = new AtomicBoolean(false);
    private final long startTime;

    // ==================== 构造器 ====================

    /**
     * 创建命令发起的还原任务
     *
     * @param plugin         插件实例
     * @param restoreCommand 将发送给 MineBackup 主程序的命令
     * @param initiator      发起者名称
     */
    public RestoreTask(MineBackupPlugin plugin, String restoreCommand, String initiator) {
        this.plugin = plugin;
        this.logger = plugin.getBackupLogger();
        this.lm = plugin.getLanguageManager();
        this.restoreCommand = restoreCommand;
        this.initiator = initiator;
        this.isRemote = false;
        this.startTime = System.currentTimeMillis();
    }

    /**
     * 创建远程发起的还原任务（由 pre_hot_restore 事件触发）
     *
     * @param plugin 插件实例
     */
    public RestoreTask(MineBackupPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getBackupLogger();
        this.lm = plugin.getLanguageManager();
        this.restoreCommand = null;
        this.initiator = "MineBackup 主程序";
        this.isRemote = true;
        this.startTime = System.currentTimeMillis();
    }

    // ==================== Getter ====================

    public Phase getPhase() { return phase; }
    public String getInitiator() { return initiator; }
    public boolean isRemote() { return isRemote; }

    // ==================== 生命周期 ====================

    /**
     * 启动还原流水线
     *
     * @return true=成功启动; false=已有其他任务在运行
     */
    public boolean start() {
        if (!currentTask.compareAndSet(null, this)) {
            return false;
        }

        if (isRemote) {
            lm.broadcastMessage("minebackup.restore.remote_initiated");

            if (Config.isRemoteRestoreCountdown()) {
                int seconds = Config.getRemoteCountdownSeconds();
                logger.info("RESTORE", "远程还原已触发，启动 " + seconds + " 秒倒计时");
                startCountdown(seconds);
            } else {
                logger.info("RESTORE", "远程还原已触发，立即执行");
                performShutdown();
            }
        } else {
            if (Config.isRequireConfirm()) {
                phase = Phase.WAITING_CONFIRM;
                int timeout = Config.getConfirmTimeoutSeconds();

                logger.info("RESTORE", "还原由 [" + initiator + "] 发起，等待确认 (超时: " + timeout + "s)"
                        + " | 命令: " + restoreCommand);
                lm.broadcastMessage("minebackup.restore.confirm_prompt", String.valueOf(timeout));

                // 超时自动取消
                confirmTimeoutTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (phase == Phase.WAITING_CONFIRM) {
                        logger.warn("RESTORE", "还原确认超时 (" + timeout + "s)，操作已取消");
                        lm.broadcastMessage("minebackup.restore.confirm_timeout");
                        cleanup();
                    }
                }, timeout * 20L);
            } else {
                logger.info("RESTORE", "还原由 [" + initiator + "] 发起（跳过确认）| 命令: " + restoreCommand);
                startCountdown(Config.getCountdownSeconds());
            }
        }
        return true;
    }

    /**
     * 确认还原（/mb confirm）
     *
     * @return true=确认成功; false=当前不在等待确认阶段
     */
    public boolean confirm() {
        if (phase != Phase.WAITING_CONFIRM) return false;

        cancelTimer(confirmTimeoutTask);
        confirmTimeoutTask = null;

        logger.info("RESTORE", "还原已被确认");
        lm.broadcastMessage("minebackup.restore.confirmed");
        startCountdown(Config.getCountdownSeconds());
        return true;
    }

    /**
     * 取消还原（/mb abort 或超时）
     *
     * @param reason 取消原因
     * @return true=取消成功; false=当前不可取消
     */
    public boolean abort(String reason) {
        if (phase == Phase.EXECUTING || phase == Phase.NONE) return false;
        if (!aborted.compareAndSet(false, true)) return false;

        Phase oldPhase = phase;
        logger.info("RESTORE", "还原在 [" + oldPhase.getDisplayName() + "] 阶段被取消，原因: " + reason);

        cancelTimer(countdownTask);
        cancelTimer(confirmTimeoutTask);
        countdownTask = null;
        confirmTimeoutTask = null;

        cleanup();

        lm.broadcastMessage("minebackup.restore.aborted");
        return true;
    }

    // ==================== 内部流程 ====================

    /**
     * 启动倒计时
     */
    private void startCountdown(int seconds) {
        phase = Phase.COUNTDOWN;
        remainingSeconds = seconds;

        logger.info("RESTORE", "还原倒计时开始: " + seconds + " 秒");
        lm.broadcastMessage("minebackup.restore.countdown_start", String.valueOf(seconds));

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

                // 最后 5 秒每秒广播，其余每 5 秒广播一次
                if (remainingSeconds <= 5 || remainingSeconds % 5 == 0) {
                    lm.broadcastMessage("minebackup.restore.countdown",
                            String.valueOf(remainingSeconds));
                }
                remainingSeconds--;
            }
        }.runTaskTimer(plugin, 20L, 20L); // 第一个 tick 延迟 1 秒后开始
    }

    /**
     * 倒计时结束后的处理
     */
    private void onCountdownComplete() {
        if (isRemote) {
            // 远程发起：直接执行关服流程
            performShutdown();
        } else {
            // 命令发起：向 MineBackup 发送还原命令，等待 pre_hot_restore 回调
            phase = Phase.EXECUTING;
            HotRestoreState.isRestoring = true;

            long elapsed = System.currentTimeMillis() - startTime;
            logger.info("RESTORE", "倒计时结束，发送还原命令: " + restoreCommand
                    + " (从发起到发命令: " + elapsed + "ms)");
            lm.broadcastMessage("minebackup.restore.executing");

            OpenSocketQuerier.query(MineBackupPlugin.QUERIER_APP_ID,
                    MineBackupPlugin.QUERIER_SOCKET_ID, restoreCommand);
            // 后续由 MineBackupPlugin.handlePreHotRestore() → task.performShutdown() 完成
        }
    }

    /**
     * 执行关服流程（保存世界 → 踢出玩家 → 通知主程序 → 重启/关闭）
     * <p>
     * 调用场景：
     * <ul>
     *   <li>远程发起的还原（倒计时结束后直接调用）</li>
     *   <li>命令发起的还原（收到 pre_hot_restore 后由主类调用）</li>
     * </ul>
     * <p>
     * <b>必须在主线程调用。</b>
     */
    public void performShutdown() {
        phase = Phase.EXECUTING;
        HotRestoreState.isRestoring = true;
        HotRestoreState.waitingForServerStopAck = true;

        lm.broadcastMessage("minebackup.restore.executing");

        // ---- 1. 保存所有世界 ----
        logger.info("RESTORE", "正在保存所有世界数据...");
        long saveStart = System.currentTimeMillis();
        boolean allSaved = true;
        for (World world : Bukkit.getWorlds()) {
            try {
                world.save();
                logger.debug("RESTORE", "世界 '" + world.getName() + "' 保存完成");
            } catch (Exception e) {
                logger.error("RESTORE", "保存世界 '" + world.getName() + "' 失败: " + e.getMessage());
                allSaved = false;
            }
        }
        long saveCost = System.currentTimeMillis() - saveStart;
        logger.info("RESTORE", "世界数据保存完成，耗时 " + saveCost + "ms"
                + (allSaved ? "" : " (部分失败)"));

        // ---- 2. 踢出所有玩家 ----
        int playerCount = Bukkit.getOnlinePlayers().size();
        logger.info("RESTORE", "正在踢出 " + playerCount + " 名在线玩家...");
        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                player.kickPlayer(lm.getTranslation(player, "minebackup.restore.kick"));
            } catch (Exception e) {
                logger.error("RESTORE", "踢出玩家 " + player.getName() + " 失败: " + e.getMessage());
            }
        }

        // ---- 3. 异步通知 MineBackup 主程序 ----
        new Thread(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            OpenSocketQuerier.query(MineBackupPlugin.QUERIER_APP_ID,
                    MineBackupPlugin.QUERIER_SOCKET_ID, "WORLD_SAVE_AND_EXIT_COMPLETE");
            logger.info("RESTORE", "已发送 WORLD_SAVE_AND_EXIT_COMPLETE 信号");
        }, "minebackup-restore-signal").start();

        // ---- 4. 记录总耗时并关闭服务器 ----
        long totalTime = System.currentTimeMillis() - startTime;
        logger.info("RESTORE", "还原流水线总耗时: " + totalTime + "ms "
                + "(发起者: " + initiator + ", 远程: " + isRemote + ")");

        cleanup();

        // 准备重启并关闭
        ServerRestartManager.prepareRestart(plugin);
        logger.info("RESTORE", "服务器正在关闭...");
        Bukkit.shutdown();
    }

    // ==================== 工具方法 ====================

    /**
     * 清理当前任务状态
     */
    private void cleanup() {
        phase = Phase.NONE;
        currentTask.set(null);
    }

    private void cancelTimer(BukkitTask task) {
        if (task != null) {
            try {
                task.cancel();
            } catch (Exception ignored) {
                // 可能已经自动结束
            }
        }
    }
}
