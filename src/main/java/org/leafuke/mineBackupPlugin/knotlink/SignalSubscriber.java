package org.leafuke.mineBackupPlugin.knotlink;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class SignalSubscriber {
    private static final Logger LOGGER = Logger.getLogger("MineBackup-SignalSubscriber");
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 6372;
    private static final long RECONNECT_DELAY_MS = 2000L;

    private final String appID;
    private final String signalID;

    private volatile boolean running;
    private volatile boolean connected;
    private volatile TcpClient knotLinkSubscriber;
    private SignalListener signalListener;

    public SignalSubscriber(String appID, String signalID) {
        this.appID = appID;
        this.signalID = signalID;
    }

    public void setSignalListener(SignalListener listener) {
        this.signalListener = listener;
    }

    public boolean isConnected() {
        return connected;
    }

    public void start() {
        running = true;
        while (running) {
            CountDownLatch disconnectLatch = new CountDownLatch(1);
            TcpClient client = new TcpClient();
            knotLinkSubscriber = client;
            client.setDisconnectListener(() -> {
                connected = false;
                disconnectLatch.countDown();
            });
            client.setDataReceivedListener(data -> {
                if (signalListener != null) {
                    signalListener.onSignalReceived(data);
                }
            });

            if (!client.connectToServer(HOST, PORT)) {
                sleepBeforeReconnect();
                continue;
            }

            String subscriptionKey = appID + "-" + signalID;
            client.sendData(subscriptionKey);
            connected = true;
            LOGGER.info("SignalSubscriber started and subscribed to " + subscriptionKey + ".");

            while (running && client.isConnected()) {
                try {
                    if (disconnectLatch.await(1, TimeUnit.SECONDS)) {
                        break;
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    running = false;
                }
            }

            connected = false;
            client.close();
            knotLinkSubscriber = null;
            if (running) {
                LOGGER.warning("KnotLink subscriber disconnected, retrying soon.");
                sleepBeforeReconnect();
            }
        }
    }

    public void stop() {
        running = false;
        connected = false;
        TcpClient currentClient = knotLinkSubscriber;
        if (currentClient != null) {
            currentClient.close();
        }
        LOGGER.info("SignalSubscriber stopped.");
    }

    private void sleepBeforeReconnect() {
        try {
            Thread.sleep(RECONNECT_DELAY_MS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }

    public interface SignalListener {
        void onSignalReceived(String data);
    }
}
