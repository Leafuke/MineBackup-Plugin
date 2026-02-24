package org.leafuke.mineBackupPlugin;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class LanguageManager {
    private final MineBackupPlugin plugin;
    private final Map<String, Map<String, String>> languages = new HashMap<>();
    private final String defaultLang = "en_us";

    public LanguageManager(MineBackupPlugin plugin) {
        this.plugin = plugin;
        loadLanguage("en_us");
        loadLanguage("zh_cn");
    }

    private void loadLanguage(String langCode) {
        try (InputStream is = plugin.getResource("lang/" + langCode + ".json")) {
            if (is != null) {
                InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8);
                Map<String, String> langMap = new Gson().fromJson(reader, new TypeToken<Map<String, String>>(){}.getType());
                languages.put(langCode, langMap);
                plugin.getLogger().info("Loaded language: " + langCode);
            } else {
                plugin.getLogger().warning("Language file not found: " + langCode + ".json");
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to load language: " + langCode);
            e.printStackTrace();
        }
    }

    public String getTranslation(String langCode, String key, Object... args) {
        langCode = langCode.toLowerCase();
        Map<String, String> langMap = languages.get(langCode);
        if (langMap == null) {
            langMap = languages.get(defaultLang);
        }
        
        String translation = null;
        if (langMap != null) {
            translation = langMap.get(key);
        }
        
        if (translation == null) {
            // Fallback to default language if key is missing
            Map<String, String> defaultMap = languages.get(defaultLang);
            if (defaultMap != null) {
                translation = defaultMap.get(key);
            }
        }
        
        if (translation == null) {
            return key; // Return key if translation is completely missing
        }
        
        if (args.length > 0) {
            try {
                return String.format(translation, args);
            } catch (Exception e) {
                return translation;
            }
        }
        return translation;
    }

    public String getTranslation(CommandSender sender, String key, Object... args) {
        String langCode = defaultLang;
        if (sender instanceof Player player) {
            langCode = player.getLocale();
        }
        return getTranslation(langCode, key, args);
    }
    
    public void sendMessage(CommandSender sender, String key, Object... args) {
        sender.sendMessage(getTranslation(sender, key, args));
    }

    public void broadcastMessage(String key, Object... args) {
        for (Player player : org.bukkit.Bukkit.getOnlinePlayers()) {
            sendMessage(player, key, args);
        }
        sendMessage(org.bukkit.Bukkit.getConsoleSender(), key, args);
    }
}
