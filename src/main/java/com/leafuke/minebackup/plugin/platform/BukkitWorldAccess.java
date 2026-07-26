package com.leafuke.minebackup.plugin.platform;

import com.leafuke.minebackup.plugin.runtime.WorldAccess;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

public final class BukkitWorldAccess implements WorldAccess {
    private final JavaPlugin plugin;

    public BukkitWorldAccess(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void executeMain(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    @Override
    public void savePlayers() {
        Bukkit.savePlayers();
    }

    @Override
    public List<ManagedWorld> loadedWorlds() {
        return Bukkit.getWorlds().stream().map(BukkitWorld::new).map(ManagedWorld.class::cast).toList();
    }

    /**
     * 逐接收者生成文本，而不是先用命令发起者的语言渲染一次再群发。
     * 这样同服中英文客户端以及控制台都能得到自己的语言版本。
     */
    public void broadcast(Function<CommandSender, String> message) {
        executeMain(() -> {
            Bukkit.getConsoleSender().sendMessage(message.apply(Bukkit.getConsoleSender()));
            Bukkit.getOnlinePlayers().forEach(player -> player.sendMessage(message.apply(player)));
        });
    }

    public void disconnectPlayersAndShutdown(Function<Player, String> message) {
        executeMain(() -> {
            Bukkit.getOnlinePlayers().forEach(player -> player.kickPlayer(message.apply(player)));
            Bukkit.shutdown();
        });
    }

    private record BukkitWorld(World delegate) implements ManagedWorld {
        @Override public UUID id() { return delegate.getUID(); }
        @Override public String name() { return delegate.getName(); }
        @Override public Path directory() { return delegate.getWorldFolder().toPath().toAbsolutePath().normalize(); }
        @Override public void save() { delegate.save(); }
        @Override public boolean autoSave() { return delegate.isAutoSave(); }
        @Override public void autoSave(boolean enabled) { delegate.setAutoSave(enabled); }
    }
}
