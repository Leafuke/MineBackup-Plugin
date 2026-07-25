package com.leafuke.minebackup.plugin.dedicated;

import com.leafuke.minebackup.plugin.config.PluginConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class RestartScriptResolver {
    private static final List<String> WINDOWS = List.of("start.bat", "start.cmd", "run.bat", "run.cmd");
    private static final List<String> UNIX = List.of("start.sh", "run.sh");

    private RestartScriptResolver() {
    }

    public static Resolution resolve(
            PluginConfig.DedicatedRestore config,
            Path workingDirectory,
            String osName) {
        Objects.requireNonNull(config, "config");
        Path root = Objects.requireNonNull(workingDirectory, "workingDirectory").toAbsolutePath().normalize();
        if (config.mode() == PluginConfig.DedicatedRestoreMode.DISABLED) {
            return Resolution.unavailable("Dedicated restore is disabled");
        }
        boolean windows = Objects.requireNonNull(osName, "osName").toLowerCase(Locale.ROOT).contains("win");
        Path script;
        if (!config.restartScript().isBlank()) {
            Path configured = Path.of(config.restartScript());
            script = (configured.isAbsolute() ? configured : root.resolve(configured)).toAbsolutePath().normalize();
            if (!Files.isRegularFile(script)) {
                return Resolution.unavailable("Configured restart script is not a regular file: " + script);
            }
        } else {
            List<Path> found = new ArrayList<>();
            for (String name : windows ? WINDOWS : UNIX) {
                Path candidate = root.resolve(name);
                if (Files.isRegularFile(candidate)) {
                    found.add(candidate.toAbsolutePath().normalize());
                }
            }
            if (found.size() != 1) {
                return Resolution.unavailable(found.isEmpty()
                        ? "No restart script candidate was found"
                        : "Multiple restart scripts were found; configure one explicitly");
            }
            script = found.get(0);
        }
        String name = script.getFileName().toString().toLowerCase(Locale.ROOT);
        List<String> command;
        if (windows && (name.endsWith(".bat") || name.endsWith(".cmd"))) {
            command = List.of("cmd.exe", "/c", script.toString());
        } else if (!windows && name.endsWith(".sh")) {
            command = List.of("/bin/sh", script.toString());
        } else if (Files.isExecutable(script)) {
            command = List.of(script.toString());
        } else {
            return Resolution.unavailable("Configured restart file is not executable: " + script);
        }
        return new Resolution(true, script, command, "");
    }

    public record Resolution(boolean available, Path script, List<String> command, String reason) {
        public Resolution {
            command = List.copyOf(command);
            reason = reason == null ? "" : reason;
        }

        static Resolution unavailable(String reason) {
            return new Resolution(false, null, List.of(), reason);
        }
    }
}
