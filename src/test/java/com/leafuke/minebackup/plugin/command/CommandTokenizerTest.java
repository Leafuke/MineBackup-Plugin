package com.leafuke.minebackup.plugin.command;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CommandTokenizerTest {
    @Test
    void supportsQuotedAndEscapedArguments() {
        assertEquals(List.of("target", "backup", "abc", "world one", "nightly \"safe\""),
                CommandTokenizer.tokenize(new String[] {
                        "target", "backup", "abc", "\"world", "one\"", "\"nightly", "\\\"safe\\\"\""
                }));
    }

    @Test
    void rejectsUnclosedQuote() {
        assertThrows(IllegalArgumentException.class,
                () -> CommandTokenizer.tokenize(new String[] {"restore", "\"broken"}));
    }
}
