package org.leafuke.mineBackupPlugin.knotlink;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * KnotLink TCP 客户端
 * 负责与 MineBackup 主程序的底层 TCP 通信
 */
public class TcpClient {
    private static final Logger LOGGER = Logger.getLogger("MineBackup-TcpClient");

    private Socket socket;
    private PrintWriter out;
    private InputStream in;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final String heartbeatMessage = "heartbeat";
    private final String heartbeatResponse = "heartbeat_response";
    private volatile boolean running = false;

    public TcpClient() {
    }

    /**
     * 连接到 KnotLink 服务器
     */
    public boolean connectToServer(String host, int port) {
        try {
            this.socket = new Socket(host, port);
            this.out = new PrintWriter(new OutputStreamWriter(
                    this.socket.getOutputStream(), StandardCharsets.UTF_8), true);
            this.in = this.socket.getInputStream();
            LOGGER.info("Connected to KnotLink server at " + host + ":" + port);

            Thread reader = new Thread(this::readData, "minebackup-knotlink-reader");
            reader.setDaemon(true);
            reader.start();
            this.startHeartbeat();
            return true;
        } catch (IOException e) {
            LOGGER.warning("Failed to connect to KnotLink server: " + e.getMessage());
            return false;
        }
    }

    /**
     * 发送数据到 KnotLink 服务器
     */
    public void sendData(String data) {
        if (this.out != null) {
            this.out.print(data);
            this.out.flush();
        } else {
            LOGGER.warning("Socket is not connected.");
        }
    }

    private void startHeartbeat() {
        this.running = true;
        this.scheduler.scheduleAtFixedRate(() -> {
            if (this.running) {
                this.sendData(this.heartbeatMessage);
            }
        }, 1L, 3L, TimeUnit.MINUTES);
    }

    private void stopHeartbeat() {
        this.running = false;
        this.scheduler.shutdown();
    }

    private void readData() {
        LOGGER.fine("Start reading data from KnotLink server...");
        try {
            byte[] buffer = new byte[1024];
            while (true) {
                int bytesRead = in.read(buffer);
                if (bytesRead == -1) {
                    break;
                }
                String receivedData = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
                LOGGER.fine("Received raw data: " + receivedData);
                if (receivedData.trim().equals(heartbeatResponse)) {
                    continue;
                }
                if (dataReceivedListener != null) {
                    dataReceivedListener.onDataReceived(receivedData);
                }
            }
        } catch (IOException e) {
            LOGGER.warning("KnotLink socket error: " + e.getMessage());
        } finally {
            stopHeartbeat();
            LOGGER.info("KnotLink server disconnected.");
        }
    }

    /**
     * 数据接收监听器接口
     */
    public interface DataReceivedListener {
        void onDataReceived(String data);
    }

    private DataReceivedListener dataReceivedListener;

    public void setDataReceivedListener(DataReceivedListener listener) {
        this.dataReceivedListener = listener;
        LOGGER.fine("DataReceivedListener set successfully.");
    }

    /**
     * 关闭连接
     */
    public void close() {
        this.stopHeartbeat();
        try {
            if (this.socket != null) {
                this.socket.close();
            }
        } catch (IOException e) {
            LOGGER.warning("Error closing socket: " + e.getMessage());
        }
    }
}
