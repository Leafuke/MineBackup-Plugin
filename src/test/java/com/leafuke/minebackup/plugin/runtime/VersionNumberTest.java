package com.leafuke.minebackup.plugin.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionNumberTest {
    @Test
    void comparesReleaseAndPrereleaseVersionsByNumericCore() {
        assertTrue(VersionNumber.isAtLeast("1.14.0", "1.14.0"));
        assertTrue(VersionNumber.isAtLeast("1.15.5", "1.14.0"));
        assertTrue(VersionNumber.isAtLeast("3.0.0-beta.1", "2.1.1"));
        assertFalse(VersionNumber.isAtLeast("1.13.9", "1.14.0"));
        assertFalse(VersionNumber.isAtLeast("", "1.14.0"));
        assertFalse(VersionNumber.isAtLeast("not-a-version", "1.14.0"));
        assertFalse(VersionNumber.isAtLeast("999999999999999999999", "1.14.0"));
    }
}
