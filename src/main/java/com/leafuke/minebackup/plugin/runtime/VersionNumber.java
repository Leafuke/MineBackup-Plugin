package com.leafuke.minebackup.plugin.runtime;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VersionNumber {
    private static final Pattern VERSION_PATTERN = Pattern.compile(
            "^[vV]?(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?(?:[-+].*)?$");

    private VersionNumber() {
    }

    public static boolean isAtLeast(String actual, String minimum) {
        Optional<int[]> actualParts = parts(actual);
        Optional<int[]> minimumParts = parts(minimum);
        if (actualParts.isEmpty() || minimumParts.isEmpty()) {
            return false;
        }
        int[] left = actualParts.orElseThrow();
        int[] right = minimumParts.orElseThrow();
        for (int index = 0; index < left.length; index++) {
            int a = left[index];
            int b = right[index];
            if (a != b) {
                return a > b;
            }
        }
        return true;
    }

    private static Optional<int[]> parts(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = VERSION_PATTERN.matcher(value.trim());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        int[] result = new int[3];
        try {
            for (int index = 0; index < result.length; index++) {
                String component = matcher.group(index + 1);
                result[index] = component == null ? 0 : Integer.parseInt(component);
            }
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
        return Optional.of(result);
    }
}
