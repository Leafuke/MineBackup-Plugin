package org.leafuke.mineBackupPlugin;

import org.bukkit.command.CommandSender;

/**
 * 消息常量类
 * 包含所有向玩家展示的消息文本（使用 Minecraft §格式码）
 */
public final class Messages {
    private Messages() {}

    /**
     * 将错误响应转换为本地化的可读文本
     */
    public static String localizeError(CommandSender sender, String response) {
        LanguageManager lm = MineBackupPlugin.getInstance().getLanguageManager();
        if (response == null) return lm.getTranslation(sender, "minebackup.no_response");
        if (response.startsWith("ERROR:")) {
            String error = response.substring(6);
            return switch (error) {
                case "COMMUNICATION_FAILED" -> lm.getTranslation(sender, "minebackup.communication.failed");
                case "NO_RESPONSE" -> lm.getTranslation(sender, "minebackup.no_response");
                default -> error;
            };
        }
        return response;
    }

    /**
     * 获取事件数据中的世界名称，缺失时返回默认值
     */
    public static String getWorldDisplay(CommandSender sender, java.util.Map<String, String> eventData) {
        String world = eventData.get("world");
        return (world == null || world.isBlank()) ? MineBackupPlugin.getInstance().getLanguageManager().getTranslation(sender, "minebackup.unknown_world") : world;
    }

    /**
     * 获取事件数据中的文件名，缺失时返回默认值
     */
    public static String getFileDisplay(CommandSender sender, java.util.Map<String, String> eventData) {
        String file = eventData.get("file");
        return (file == null || file.isBlank()) ? MineBackupPlugin.getInstance().getLanguageManager().getTranslation(sender, "minebackup.unknown_file") : file;
    }

    /**
     * 获取事件数据中的错误信息，缺失时返回默认值
     */
    public static String getErrorDisplay(CommandSender sender, java.util.Map<String, String> eventData) {
        String error = eventData.get("error");
        return (error == null || error.isBlank()) ? MineBackupPlugin.getInstance().getLanguageManager().getTranslation(sender, "minebackup.unknown_error") : error;
    }
}
