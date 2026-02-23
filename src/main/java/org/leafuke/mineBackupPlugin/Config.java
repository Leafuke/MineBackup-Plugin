package org.leafuke.mineBackupPlugin;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.nio.file.Path;
import java.util.Properties;
import java.util.logging.Level;

/**
 * MineBackup 配置管理类
 * 用于存储和加载自动备份配置
 */
public class Config {
    private static final String CONFIG_FILE = "auto-backup.properties";
    private static int configId = -1;
    private static int worldIndex = -1;
    private static int internalTime = -1;

    /**
     * 从配置文件加载设置
     */
    public static void load(JavaPlugin plugin) {
        Path configPath = plugin.getDataFolder().toPath().resolve(CONFIG_FILE);
        File file = configPath.toFile();
        if (!file.exists()) return;

        try (FileInputStream fis = new FileInputStream(file)) {
            Properties props = new Properties();
            props.load(fis);
            configId = Integer.parseInt(props.getProperty("configId", "-1"));
            worldIndex = Integer.parseInt(props.getProperty("worldIndex", "-1"));
            internalTime = Integer.parseInt(props.getProperty("internalTime", "-1"));
            plugin.getLogger().info("[MineBackup] 配置加载成功: configId=" + configId
                    + ", worldIndex=" + worldIndex + ", internalTime=" + internalTime);
        } catch (IOException | NumberFormatException e) {
            plugin.getLogger().log(Level.SEVERE, "[MineBackup] 加载配置失败", e);
        }
    }

    /**
     * 保存配置到文件
     */
    public static void save(JavaPlugin plugin) {
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
        Path configPath = dataFolder.toPath().resolve(CONFIG_FILE);
        try (FileOutputStream fos = new FileOutputStream(configPath.toFile())) {
            Properties props = new Properties();
            props.setProperty("configId", String.valueOf(configId));
            props.setProperty("worldIndex", String.valueOf(worldIndex));
            props.setProperty("internalTime", String.valueOf(internalTime));
            props.store(fos, "MineBackup Auto Backup Config");
            plugin.getLogger().info("[MineBackup] 配置保存成功");
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "[MineBackup] 保存配置失败", e);
        }
    }

    /**
     * 设置自动备份参数
     */
    public static void setAutoBackup(JavaPlugin plugin, int cid, int wid, int time) {
        configId = cid;
        worldIndex = wid;
        internalTime = time;
        save(plugin);
    }

    /**
     * 清除自动备份配置
     */
    public static void clearAutoBackup(JavaPlugin plugin) {
        configId = -1;
        worldIndex = -1;
        internalTime = -1;
        save(plugin);
    }

    /**
     * 检查是否配置了自动备份
     */
    public static boolean hasAutoBackup() {
        return configId != -1 && worldIndex != -1 && internalTime != -1;
    }

    public static int getConfigId() { return configId; }
    public static int getWorldIndex() { return worldIndex; }
    public static int getInternalTime() { return internalTime; }
}
