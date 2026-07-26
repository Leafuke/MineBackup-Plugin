package com.leafuke.minebackup.plugin.message;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.leafuke.minebackup.plugin.config.PluginConfig;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class MessageService {
    private static final String FALLBACK_LANGUAGE = "en_us";
    private final JavaPlugin plugin;
    private final Map<String, Map<String, String>> languages = new HashMap<>();
    private volatile PluginConfig.Localization configuration;

    public MessageService(JavaPlugin plugin, PluginConfig.Localization configuration) throws IOException {
        this.plugin = plugin;
        load("en_us");
        load("zh_cn");
        configure(configuration);
    }

    public void configure(PluginConfig.Localization value) {
        configuration = java.util.Objects.requireNonNull(value, "value");
    }

    public void send(CommandSender sender, String key, Object... arguments) {
        sender.sendMessage(text(sender, key, arguments));
    }

    public String text(CommandSender sender, String key, Object... arguments) {
        PluginConfig.Localization snapshot = configuration;
        String language = sender instanceof Player player && snapshot.followPlayerLocale()
                ? normalize(player.getLocale())
                : snapshot.defaultLanguage();
        Map<String, String> selected = languages.getOrDefault(language, languages.get(FALLBACK_LANGUAGE));
        String template = selected.getOrDefault(key, languages.get(FALLBACK_LANGUAGE).getOrDefault(key, key));
        try {
            return arguments.length == 0 ? template : String.format(template, arguments);
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Invalid translation format for " + key);
            return template;
        }
    }

    private void load(String language) throws IOException {
        try (InputStream stream = plugin.getResource("lang/" + language + ".json")) {
            if (stream == null) {
                throw new IOException("Missing language resource: " + language);
            }
            Map<String, String> values = new Gson().fromJson(
                    new InputStreamReader(stream, StandardCharsets.UTF_8),
                    new TypeToken<Map<String, String>>() { }.getType());
            if (values == null) {
                throw new IOException("Empty language resource: " + language);
            }
            languages.put(language, Map.copyOf(values));
        }
    }

    private static String normalize(String locale) {
        String normalized = locale == null ? FALLBACK_LANGUAGE : locale.toLowerCase(Locale.ROOT).replace('-', '_');
        return normalized.startsWith("zh") ? "zh_cn" : "en_us";
    }
}
