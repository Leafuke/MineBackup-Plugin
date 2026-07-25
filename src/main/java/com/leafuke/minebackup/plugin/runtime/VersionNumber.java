package com.leafuke.minebackup.plugin.runtime;

import java.util.ArrayList;
import java.util.List;

public final class VersionNumber {
    private VersionNumber() {
    }

    public static boolean isAtLeast(String actual, String minimum) {
        List<Integer> left = parts(actual);
        List<Integer> right = parts(minimum);
        int size = Math.max(left.size(), right.size());
        for (int index = 0; index < size; index++) {
            int a = index < left.size() ? left.get(index) : 0;
            int b = index < right.size() ? right.get(index) : 0;
            if (a != b) {
                return a > b;
            }
        }
        return true;
    }

    private static List<Integer> parts(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<Integer> result = new ArrayList<>();
        for (String part : value.trim().split("[.-]")) {
            if (!part.chars().allMatch(Character::isDigit)) {
                break;
            }
            try {
                result.add(Integer.parseInt(part));
            } catch (NumberFormatException exception) {
                result.add(Integer.MAX_VALUE);
            }
        }
        return List.copyOf(result);
    }
}
