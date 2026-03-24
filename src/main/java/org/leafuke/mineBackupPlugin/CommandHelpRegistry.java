package org.leafuke.mineBackupPlugin;

import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class CommandHelpRegistry {
    private static final List<HelpEntry> ENTRIES = List.of(
            entry("help", "[subcommand]", "minebackup.help.help.summary", "/mb help quickbackup"),
            entry("save", "", "minebackup.help.save.summary", "/mb save"),
            entry("status", "", "minebackup.help.status.summary", "/mb status"),
            entry("reload", "", "minebackup.help.reload.summary", "/mb reload"),
            entry("list_configs", "", "minebackup.help.list_configs.summary", "/mb list_configs"),
            entry("list_worlds", "<config_id>", "minebackup.help.list_worlds.summary", "/mb list_worlds 1"),
            entry("list_backups", "<config_id> <world_index>", "minebackup.help.list_backups.summary", "/mb list_backups 1 0"),
            entry("backup", "<config_id> <world_index> [comment]", "minebackup.help.backup.summary", "/mb backup 1 0 before_boss"),
            entry("restore", "<config_id> <world_index> <backup_file>", "minebackup.help.restore.summary", "/mb restore 1 0 [Full][2026-03-24]world.7z"),
            entry("quickbackup", "[comment]", "minebackup.help.quickbackup.summary", "/mb quickbackup before_boss", "quicksave"),
            entry("quicksave", "[comment]", "minebackup.help.quicksave.summary", "/mb quicksave before_boss"),
            entry("quickrestore", "[backup_file]", "minebackup.help.quickrestore.summary", "/mb quickrestore [Full][2026-03-24]world.7z"),
            entry("confirm", "", "minebackup.help.confirm.summary", "/mb confirm"),
            entry("abort", "", "minebackup.help.abort.summary", "/mb abort"),
            entry("auto", "<config_id> <world_index> <interval_seconds>", "minebackup.help.auto.summary", "/mb auto 1 0 1800"),
            entry("stop", "<config_id> <world_index>", "minebackup.help.stop.summary", "/mb stop 1 0"),
            entry("snap", "<config_id> <world_index> <backup_file>", "minebackup.help.snap.summary", "/mb snap 1 0 [Full][2026-03-24]world.7z")
    );

    private static final Map<String, HelpEntry> LOOKUP = buildLookup();

    private CommandHelpRegistry() {
    }

    public static String buildRootHelp(CommandSender sender, LanguageManager languageManager) {
        StringBuilder builder = new StringBuilder(languageManager.getTranslation(sender, "minebackup.help.title"));
        for (HelpEntry entry : ENTRIES) {
            builder.append("\n ")
                    .append(languageManager.getTranslation(sender, "minebackup.help.entry",
                            entry.name(),
                            formatUsage(entry.usage()),
                            languageManager.getTranslation(sender, entry.summaryKey())));
        }
        builder.append("\n")
                .append(languageManager.getTranslation(sender, "minebackup.help.footer"));
        return builder.toString();
    }

    public static String buildCommandHelp(CommandSender sender, LanguageManager languageManager, String requestedName) {
        HelpEntry entry = find(requestedName);
        if (entry == null) {
            return languageManager.getTranslation(sender, "minebackup.help.unknown", requestedName);
        }

        StringBuilder builder = new StringBuilder(
                languageManager.getTranslation(sender, "minebackup.help.command.title", entry.name()));
        builder.append("\n")
                .append(languageManager.getTranslation(sender, entry.summaryKey()));
        builder.append("\n")
                .append(languageManager.getTranslation(sender, "minebackup.help.command.usage",
                        entry.name(), formatUsage(entry.usage())));
        if (!entry.aliases().isEmpty()) {
            builder.append("\n")
                    .append(languageManager.getTranslation(sender, "minebackup.help.command.aliases",
                            String.join(", ", entry.aliases())));
        }
        builder.append("\n")
                .append(languageManager.getTranslation(sender, "minebackup.help.command.example", entry.example()));
        return builder.toString();
    }

    public static List<String> suggestCommands(String partial) {
        String remaining = partial == null ? "" : partial.toLowerCase(Locale.ROOT);
        List<String> suggestions = new ArrayList<>();
        for (HelpEntry entry : ENTRIES) {
            if (matches(entry.name(), remaining)) {
                suggestions.add(entry.name());
            }
            for (String alias : entry.aliases()) {
                if (matches(alias, remaining)) {
                    suggestions.add(alias);
                }
            }
        }
        return suggestions;
    }

    public static HelpEntry find(String requestedName) {
        if (requestedName == null) {
            return null;
        }
        return LOOKUP.get(requestedName.toLowerCase(Locale.ROOT));
    }

    private static Map<String, HelpEntry> buildLookup() {
        Map<String, HelpEntry> lookup = new LinkedHashMap<>();
        for (HelpEntry entry : ENTRIES) {
            lookup.put(entry.name(), entry);
            for (String alias : entry.aliases()) {
                lookup.put(alias.toLowerCase(Locale.ROOT), entry);
            }
        }
        return lookup;
    }

    private static boolean matches(String candidate, String partial) {
        return partial.isEmpty() || candidate.toLowerCase(Locale.ROOT).startsWith(partial);
    }

    private static HelpEntry entry(String name, String usage, String summaryKey, String example, String... aliases) {
        return new HelpEntry(name, usage, summaryKey, example, List.of(aliases));
    }

    private static String formatUsage(String usage) {
        return usage == null || usage.isBlank() ? "" : " " + usage;
    }

    public record HelpEntry(String name, String usage, String summaryKey, String example, List<String> aliases) {
    }
}
