package com.leafuke.minebackup.plugin.command;

import com.leafuke.minebackup.plugin.config.PluginConfig;
import com.leafuke.minebackup.plugin.runtime.PluginRuntime;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MineBackupCommand implements CommandExecutor, TabCompleter {
    private static final List<String> ROOT = List.of(
            "help", "status", "save", "backup", "restore", "confirm", "stop", "list", "target", "auto", "reload");
    private final PluginRuntime runtime;

    public MineBackupCommand(PluginRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("minebackup.command")) {
            runtime.messages().send(sender, "no_permission");
            return true;
        }
        List<String> tokens;
        try {
            tokens = CommandTokenizer.tokenize(args);
        } catch (IllegalArgumentException exception) {
            runtime.messages().send(sender, "argument_parse_failed");
            return true;
        }
        if (tokens.isEmpty() || tokens.get(0).equalsIgnoreCase("help")) {
            help(sender);
            return true;
        }
        String root = tokens.get(0).toLowerCase(Locale.ROOT);
        try {
            switch (root) {
                case "status" -> runtime.status(sender).forEach(sender::sendMessage);
                case "save" -> requireSize(tokens, 1, "/mb save", () -> runtime.save(sender));
                case "backup" -> runtime.backupCurrent(sender, join(tokens, 1, 1_024));
                case "restore" -> {
                    String file = join(tokens, 1, 255);
                    if (!file.isBlank() && !safeFileName(file)) {
                        throw input("invalid_backup_file");
                    }
                    runtime.restore(sender, file);
                }
                case "confirm" -> requireSize(tokens, 1, "/mb confirm", () -> runtime.confirmRestore(sender));
                case "stop" -> requireSize(tokens, 1, "/mb stop", () -> runtime.cancelRestore(sender));
                case "list" -> list(sender, tokens);
                case "target" -> target(sender, tokens);
                case "auto" -> auto(sender, tokens);
                case "reload" -> requireSize(tokens, 1, "/mb reload", () -> runtime.reload(sender));
                default -> runtime.messages().send(sender, "invalid_command");
            }
        } catch (CommandInputException exception) {
            runtime.messages().send(sender, exception.key, exception.arguments);
        } catch (IllegalArgumentException exception) {
            runtime.messages().send(sender, "invalid_argument", exception.getMessage());
        }
        return true;
    }

    private void list(CommandSender sender, List<String> tokens) {
        if (tokens.size() == 2 && tokens.get(1).equalsIgnoreCase("configs")) {
            runtime.listConfigs(sender);
        } else if (tokens.size() == 3 && tokens.get(1).equalsIgnoreCase("folders")) {
            requireId(tokens.get(2));
            runtime.listFolders(sender, tokens.get(2));
        } else if (tokens.size() == 4 && tokens.get(1).equalsIgnoreCase("backups")) {
            requireId(tokens.get(2));
            requireText(tokens.get(3), 255);
            runtime.listBackups(sender, tokens.get(2), tokens.get(3));
        } else {
            throw input("usage", "/mb list configs | folders <config-id> | backups <config-id> <folder>");
        }
    }

    private void target(CommandSender sender, List<String> tokens) {
        if (tokens.size() < 4 || !tokens.get(1).equalsIgnoreCase("backup")) {
            throw input("usage", "/mb target backup <config-id> <folder> [comment]");
        }
        requireId(tokens.get(2));
        requireText(tokens.get(3), 255);
        runtime.targetBackup(sender, tokens.get(2), tokens.get(3), join(tokens, 4, 1_024));
    }

    private void auto(CommandSender sender, List<String> tokens) {
        if (tokens.size() == 2 && tokens.get(1).equalsIgnoreCase("stop")) {
            runtime.stopAutoBackup(sender);
            return;
        }
        if (tokens.size() != 3 || !tokens.get(1).equalsIgnoreCase("start")) {
            throw input("usage", "/mb auto start <minutes> | /mb auto stop");
        }
        int minutes;
        try {
            minutes = Integer.parseInt(tokens.get(2));
        } catch (NumberFormatException exception) {
            throw input("invalid_minutes_number");
        }
        if (minutes < 1 || minutes > PluginConfig.MAX_AUTO_BACKUP_INTERVAL_MINUTES) {
            throw input("invalid_minutes_range", PluginConfig.MAX_AUTO_BACKUP_INTERVAL_MINUTES);
        }
        runtime.startAutoBackup(sender, minutes);
    }

    private void help(CommandSender sender) {
        runtime.messages().send(sender, "help_title");
        for (String key : List.of("help_save", "help_backup", "help_restore", "help_restore_control",
                "help_list", "help_target_backup", "help_auto", "help_status")) {
            runtime.messages().send(sender, key);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("minebackup.command")) {
            return List.of();
        }
        String current = args.length == 0 ? "" : args[args.length - 1];
        if (args.length <= 1) {
            return filter(ROOT, current);
        }
        String root = args[0].toLowerCase(Locale.ROOT);
        if (root.equals("list")) {
            if (args.length == 2) return filter(List.of("configs", "folders", "backups"), current);
            if (args.length == 3 && (args[1].equalsIgnoreCase("folders") || args[1].equalsIgnoreCase("backups"))) {
                return filter(runtime.suggestions().get("configs"), current);
            }
            if (args.length == 4 && args[1].equalsIgnoreCase("backups")) {
                return quoted(filter(runtime.suggestions().get("folders:" + args[2]), current));
            }
        } else if (root.equals("restore") && args.length >= 2) {
            return quoted(filter(runtime.suggestions().get("backups:current"), current));
        } else if (root.equals("target")) {
            if (args.length == 2) return filter(List.of("backup"), current);
            if (args.length == 3) return filter(runtime.suggestions().get("configs"), current);
            if (args.length == 4) return quoted(filter(runtime.suggestions().get("folders:" + args[2]), current));
        } else if (root.equals("auto") && args.length == 2) {
            return filter(List.of("start", "stop"), current);
        }
        return List.of();
    }

    private static List<String> filter(List<String> values, String current) {
        String prefix = current == null ? "" : current.replace("\"", "").toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }

    private static List<String> quoted(List<String> values) {
        return values.stream().map(value -> value.contains(" ") ? "\"" + value.replace("\"", "\\\"") + "\"" : value).toList();
    }

    private static void requireSize(List<String> tokens, int size, String usage, Runnable action) {
        if (tokens.size() != size) {
            throw input("usage", usage);
        }
        action.run();
    }

    private static void requireId(String value) {
        if (!value.matches("[A-Za-z0-9_-]{1,128}")) {
            throw input("invalid_config_id");
        }
    }

    private static void requireText(String value, int maximum) {
        if (value.isBlank() || value.length() > maximum || value.chars().anyMatch(Character::isISOControl)) {
            throw input("invalid_text", maximum);
        }
    }

    private static String join(List<String> values, int start, int maximum) {
        if (start >= values.size()) {
            return "";
        }
        String result = String.join(" ", values.subList(start, values.size())).trim();
        if (result.length() > maximum || result.chars().anyMatch(Character::isISOControl)) {
            throw input("invalid_text", maximum);
        }
        return result;
    }

    private static boolean safeFileName(String value) {
        return !value.equals(".") && !value.equals("..") && !value.contains("/") && !value.contains("\\")
                && !value.contains("\u0000") && PathLike.isSingleName(value);
    }

    private static final class PathLike {
        static boolean isSingleName(String value) {
            try {
                return java.nio.file.Path.of(value).getNameCount() == 1 && !java.nio.file.Path.of(value).isAbsolute();
            } catch (RuntimeException exception) {
                return false;
            }
        }
    }

    private static CommandInputException input(String key, Object... arguments) {
        return new CommandInputException(key, arguments);
    }

    /** 将可预期的输入错误保留为消息键，避免把英文异常文本直接展示给玩家。 */
    private static final class CommandInputException extends IllegalArgumentException {
        private final String key;
        private final Object[] arguments;

        private CommandInputException(String key, Object... arguments) {
            this.key = key;
            this.arguments = arguments;
        }
    }
}
