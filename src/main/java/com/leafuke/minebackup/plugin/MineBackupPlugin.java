package com.leafuke.minebackup.plugin;

import com.leafuke.minebackup.plugin.command.MineBackupCommand;
import com.leafuke.minebackup.plugin.runtime.PluginRuntime;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

public final class MineBackupPlugin extends JavaPlugin {
    private PluginRuntime runtime;

    @Override
    public void onEnable() {
        try {
            runtime = new PluginRuntime(this);
            MineBackupCommand executor = new MineBackupCommand(runtime);
            PluginCommand command = getCommand("mb");
            if (command == null) {
                throw new IllegalStateException("plugin.yml did not register /mb");
            }
            command.setExecutor(executor);
            command.setTabCompleter(executor);
            runtime.start();
            getLogger().info("MineBackupPlugin " + getDescription().getVersion() + " enabled with KnotLink v2");
        } catch (Exception exception) {
            getLogger().log(Level.SEVERE, "MineBackupPlugin could not be enabled safely", exception);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        PluginRuntime current = runtime;
        runtime = null;
        if (current != null) {
            current.close();
        }
    }
}
