package com.leafuke.minebackup.plugin.command;

import java.util.ArrayList;
import java.util.List;

public final class CommandTokenizer {
    private CommandTokenizer() {
    }

    public static List<String> tokenize(String[] rawArguments) {
        String input = String.join(" ", rawArguments).trim();
        if (input.isEmpty()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean escaped = false;
        for (int index = 0; index < input.length(); index++) {
            char character = input.charAt(index);
            if (escaped) {
                current.append(character);
                escaped = false;
            } else if (character == '\\') {
                escaped = true;
            } else if (character == '"') {
                quoted = !quoted;
            } else if (Character.isWhitespace(character) && !quoted) {
                if (!current.isEmpty()) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(character);
            }
        }
        if (escaped) {
            current.append('\\');
        }
        if (quoted) {
            throw new IllegalArgumentException("Unclosed quote");
        }
        if (!current.isEmpty()) {
            tokens.add(current.toString());
        }
        return List.copyOf(tokens);
    }
}
