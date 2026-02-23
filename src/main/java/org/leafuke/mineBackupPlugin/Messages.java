package org.leafuke.mineBackupPlugin;

/**
 * 消息常量类
 * 包含所有向玩家展示的消息文本（使用 Minecraft §格式码）
 */
public final class Messages {
    private Messages() {}

    // ==================== 本地保存 ====================
    public static final String SAVE_START = "§e正在执行本地世界保存...";
    public static final String SAVE_SUCCESS = "§a本地世界保存成功。";

    // ==================== 远程保存 ====================
    public static final String REMOTE_SAVE_START = "§e正在执行远程保存指令...";
    public static final String REMOTE_SAVE_SUCCESS = "§a远程保存完成。";
    public static final String REMOTE_SAVE_FAIL = "§c远程保存失败！";

    // ==================== 配置列表 ====================
    public static final String LIST_CONFIGS_START = "§e正在从 MineBackup 查询配置...";
    public static final String LIST_CONFIGS_TITLE = "§a可用配置:";
    public static final String LIST_CONFIGS_ENTRY = "\n §7- §fID: §b%s§7, 名称: §e%s";
    public static final String LIST_CONFIGS_EMPTY = "\n§7(未找到可用配置)";
    public static final String LIST_CONFIGS_FAIL = "§c获取配置失败: §f%s";

    // ==================== 世界列表 ====================
    public static final String LIST_WORLDS_START = "§e正在查询配置 §b%s §e中的世界...";
    public static final String LIST_WORLDS_TITLE = "§a配置 §b%s §a中的世界:";
    public static final String LIST_WORLDS_ENTRY = "\n §7- §f索引: §b%s§7, 名称: §e%s";
    public static final String LIST_WORLDS_EMPTY = "\n§7(此配置中没有任何世界)";
    public static final String LIST_WORLDS_FAIL = "§c获取世界列表失败: §f%s";

    // ==================== 备份列表 ====================
    public static final String LIST_BACKUPS_START = "§e正在查询配置 §b%s§e, 世界 §b%s §e的备份...";
    public static final String LIST_BACKUPS_TITLE = "§a配置 §b%s§a, 世界 §b%s §a的备份:";
    public static final String LIST_BACKUPS_ENTRY = "\n §7- §b%s";
    public static final String LIST_BACKUPS_EMPTY = "\n§7(此世界没有任何备份)";
    public static final String LIST_BACKUPS_FAIL = "§c获取备份列表失败: §f%s";

    // ==================== 通用命令 ====================
    public static final String COMMAND_SENT = "§e向 MineBackup 发送指令: §f%s";
    public static final String COMMAND_FAIL = "§c指令执行失败: §f%s";
    public static final String COMMAND_INVALID = "§c无效指令。";
    public static final String COMMAND_MIGRATED = "§e指令已迁移为 /mb，请使用 /mb。";
    public static final String COMMUNICATION_FAILED = "通信失败。";
    public static final String NO_RESPONSE = "无响应。";
    public static final String UNKNOWN_WORLD = "未知世界";
    public static final String UNKNOWN_FILE = "未知文件";
    public static final String UNKNOWN_ERROR = "未知错误";

    // ==================== 通用响应 ====================
    public static final String GENERIC_RESPONSE = "§aMineBackup 响应: §f%s";

    // ==================== 快照 ====================
    public static final String SNAP_SENT = "§e发送指令以创建WE快照: §f%s";

    // ==================== 广播消息 ====================
    public static final String BROADCAST_BACKUP_STARTED = "§6[MineBackup] §e正在为世界 §f'%s' §e创建备份...";
    public static final String BROADCAST_RESTORE_STARTED = "§6[MineBackup] §e正在从备份还原世界 §f'%s'§e...";
    public static final String BROADCAST_BACKUP_SUCCESS = "§a[MineBackup] §2备份完成! §e世界 §f'%s' §a已保存为 §f%s";
    public static final String BROADCAST_BACKUP_FAILED = "§c[MineBackup] §4备份失败! §e世界 §f'%s'§c。原因: §f%s";
    public static final String BROADCAST_HOT_BACKUP_REQUEST = "§6[MineBackup] §e收到热备请求，正在为 §f'%s' §e保存最新数据...";
    public static final String BROADCAST_HOT_BACKUP_WARN = "§c[MineBackup] §4警告: 未能保存 §f'%s' §c的部分数据，备份可能不完整！";
    public static final String BROADCAST_HOT_BACKUP_COMPLETE = "§a[MineBackup] §e世界数据已保存，备份程序已开始运行。";
    public static final String BROADCAST_SESSION_END = "§7[MineBackup] §f'%s' §7的游戏会话已结束，可能已在后台触发备份。";
    public static final String BROADCAST_AUTO_BACKUP_STARTED = "§6[MineBackup] §e世界 §f'%s' §e的自动备份任务已启动。";
    public static final String BROADCAST_WE_SNAPSHOT = "§a[MineBackup] §f'%s' §a的快照已成功创建并被WE识别。";

    // ==================== 热还原 ====================
    public static final String RESTORE_PREPARING = "§6[MineBackup] §e收到热还原请求，正在准备世界...";
    public static final String RESTORE_KICK = "§6[MineBackup] §e正在为您还原存档，服务器即将关闭，请稍后重新连接...";
    public static final String RESTORE_SUCCESS = "§a[MineBackup] §e还原完成！";

    // ==================== 握手 ====================
    public static final String HANDSHAKE_SUCCESS = "§a[MineBackup] §e已连接到 MineBackup 主程序 §fv%s§e。";
    public static final String HANDSHAKE_VERSION_INCOMPATIBLE =
            "§c[MineBackup] §4警告: 插件版本 §f%s §4低于主程序要求的最低版本 §f%s§4，部分功能可能无法正常工作！";

    // ==================== 帮助 ====================
    public static final String USAGE = "§e用法: /mb <子命令>\n" +
            "§7可用子命令:\n" +
            " §b/mb save §7- 保存所有世界\n" +
            " §b/mb list_configs §7- 列出备份配置\n" +
            " §b/mb list_worlds <配置ID> §7- 列出配置中的世界\n" +
            " §b/mb list_backups <配置ID> <世界索引> §7- 列出备份文件\n" +
            " §b/mb backup <配置ID> <世界索引> [备注] §7- 执行备份\n" +
            " §b/mb restore <配置ID> <世界索引> <备份文件> §7- 执行还原\n" +
            " §b/mb quicksave [备注] §7- 快速保存并备份当前世界\n" +
            " §b/mb quickrestore [备份文件] §7- 快速还原当前世界\n" +
            " §b/mb auto <配置ID> <世界索引> <间隔秒数> §7- 启动自动备份\n" +
            " §b/mb stop <配置ID> <世界索引> §7- 停止自动备份\n" +
            " §b/mb snap <配置ID> <世界索引> <备份文件> §7- WorldEdit快照联动";

    /**
     * 格式化消息
     */
    public static String format(String template, Object... args) {
        return String.format(template, args);
    }

    /**
     * 将错误响应转换为本地化的可读文本
     */
    public static String localizeError(String response) {
        if (response == null) return NO_RESPONSE;
        if (response.startsWith("ERROR:")) {
            String error = response.substring(6);
            return switch (error) {
                case "COMMUNICATION_FAILED" -> COMMUNICATION_FAILED;
                case "NO_RESPONSE" -> NO_RESPONSE;
                default -> error;
            };
        }
        return response;
    }

    /**
     * 获取事件数据中的世界名称，缺失时返回默认值
     */
    public static String getWorldDisplay(java.util.Map<String, String> eventData) {
        String world = eventData.get("world");
        return (world == null || world.isBlank()) ? UNKNOWN_WORLD : world;
    }

    /**
     * 获取事件数据中的文件名，缺失时返回默认值
     */
    public static String getFileDisplay(java.util.Map<String, String> eventData) {
        String file = eventData.get("file");
        return (file == null || file.isBlank()) ? UNKNOWN_FILE : file;
    }

    /**
     * 获取事件数据中的错误信息，缺失时返回默认值
     */
    public static String getErrorDisplay(java.util.Map<String, String> eventData) {
        String error = eventData.get("error");
        return (error == null || error.isBlank()) ? UNKNOWN_ERROR : error;
    }
}
