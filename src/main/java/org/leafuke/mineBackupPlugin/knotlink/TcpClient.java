package org.leafuke.mineBackupPlugin.knotlink;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class TcpClient {
    private static final Logger LOGGER = Logger.getLogger("MineBackup-TcpClient");
    private static final long HEARTBEAT_INITIAL_DELAY_MINUTES = 1L;
    private static final long HEARTBEAT_INTERVAL_MINUTES = 3L;

    private Socket socket;
    private PrintWriter out;
    private InputStream in;
    private ScheduledExecutorService scheduler;
    private final String heartbeatMessage = "heartbeat";
    private final String heartbeatResponse = "heartbeat_response";
    private volatile boolean running = false;
    private volatile boolean connected = false;

    private DataReceivedListener dataReceivedListener;
    private DisconnectListener disconnectListener;

    public boolean connectToServer(String host, int port) {
        try {
            this.socket = new Socket(host, port);
            this.out = new PrintWriter(new OutputStreamWriter(
                    this.socket.getOutputStream(), StandardCharsets.UTF_8), true);
            this.in = this.socket.getInputStream();
            this.connected = true;
            LOGGER.info("Connected to KnotLink server at " + host + ":" + port);

            Thread reader = new Thread(this::readData, "minebackup-knotlink-reader");
            reader.setDaemon(true);
            reader.start();
            startHeartbeat();
            return true;
        } catch (IOException e) {
            LOGGER.warning("Failed to connect to KnotLink server: " + e.getMessage());
            notifyDisconnected();
            return false;
        }
    }

    public void sendData(String data) {
        PrintWriter currentOut = this.out;
        if (currentOut == null || !connected) {
            LOGGER.warning("Socket is not connected.");
            return;
        }

        try {
            currentOut.print(data);
            currentOut.flush();
            if (currentOut.checkError()) {
                throw new IOException("Writer reported an error");
            }
        } catch (Exception e) {
            LOGGER.warning("Failed to send data to KnotLink server: " + e.getMessage());
            close();
        }
    }

    public boolean isConnected() {
        return connected && socket != null && socket.isConnected() && !socket.isClosed();
    }

    public void setDataReceivedListener(DataReceivedListener listener) {
        this.dataReceivedListener = listener;
    }

    public void setDisconnectListener(DisconnectListener listener) {
        this.disconnectListener = listener;
    }

    public void close() {
        running = false;
        connected = false;
        stopHeartbeat();

        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException e) {
            LOGGER.warning("Error closing socket: " + e.getMessage());
        } finally {
            socket = null;
            out = null;
            in = null;
        }
    }

    private void startHeartbeat() {
        stopHeartbeat();
        running = true;
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "minebackup-knotlink-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleAtFixedRate(() -> {
            if (running && isConnected()) {
                sendData(heartbeatMessage);
            }
        }, HEARTBEAT_INITIAL_DELAY_MINUTES, HEARTBEAT_INTERVAL_MINUTES, TimeUnit.MINUTES);
    }

    private void stopHeartbeat() {
        ScheduledExecutorService currentScheduler = scheduler;
        scheduler = null;
        if (currentScheduler != null) {
            currentScheduler.shutdownNow();
        }
    }

    private void readData() {
        try {
            byte[] buffer = new byte[1024];
            while (running && in != null) {
                int bytesRead = in.read(buffer);
                if (bytesRead == -1) {
                    break;
                }

                String receivedData = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
                if (receivedData.trim().equals(heartbeatResponse)) {
                    continue;
                }

                if (dataReceivedListener != null) {
                    dataReceivedListener.onDataReceived(receivedData);
                }
            }
        } catch (IOException e) {
            if (running) {
                LOGGER.warning("KnotLink socket error: " + e.getMessage());
            }
        } finally {
            notifyDisconnected();
            close();
        }
    }

    private void notifyDisconnected() {
        boolean wasConnected = connected;
        connected = false;
        if (wasConnected && disconnectListener != null) {
            disconnectListener.onDisconnected();
        }
    }

    public interface DataReceivedListener {
        void onDataReceived(String data);
    }

    public interface DisconnectListener {
        void onDisconnected();
    }
}
