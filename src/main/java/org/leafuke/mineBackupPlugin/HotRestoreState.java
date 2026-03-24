package org.leafuke.mineBackupPlugin;

public final class HotRestoreState {
    private HotRestoreState() {
    }

    public static volatile boolean isRestoring = false;
    public static volatile boolean waitingForServerStopAck = false;

    public static volatile boolean handshakeCompleted = false;
    public static volatile String mainProgramVersion = null;
    public static volatile boolean versionCompatible = true;
    public static volatile String requiredMinModVersion = null;

    public static volatile boolean relaySessionActive = false;
    public static volatile boolean sidecarReady = false;
    public static volatile long relaySessionDeadlineMillis = 0L;
    public static volatile long lastRelaySequence = 0L;

    public static void reset() {
        isRestoring = false;
        waitingForServerStopAck = false;
    }

    public static void resetHandshake() {
        handshakeCompleted = false;
        mainProgramVersion = null;
        versionCompatible = true;
        requiredMinModVersion = null;
    }

    public static void resetRelay() {
        relaySessionActive = false;
        sidecarReady = false;
        relaySessionDeadlineMillis = 0L;
        lastRelaySequence = 0L;
    }
}
