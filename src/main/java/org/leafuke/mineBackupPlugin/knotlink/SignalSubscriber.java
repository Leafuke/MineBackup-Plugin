package org.leafuke.mineBackupPlugin.knotlink;

import java.util.logging.Logger;

/**
 * KnotLink 信号订阅器
 * 通过 TCP 长连接订阅 MineBackup 主程序的广播信号
 * 端口: 6372
 */
public class SignalSubscriber {
    private static final Logger LOGGER = Logger.getLogger("MineBackup-SignalSubscriber");

    private TcpClient knotLinkSubscriber;
    private final String appID;
    private final String signalID;

    /**
     * 信号监听器接口
     */
    public interface SignalListener {
        void onSignalReceived(String data);
    }

    private SignalListener signalListener;

    public SignalSubscriber(String appID, String signalID) {
        this.appID = appID;
        this.signalID = signalID;
    }

    public void setSignalListener(SignalListener listener) {
        this.signalListener = listener;
    }

    /**
     * 启动订阅器，连接到 KnotLink 并开始接收信号
     */
    public void start() {
        knotLinkSubscriber = new TcpClient();
        if (knotLinkSubscriber.connectToServer("127.0.0.1", 6372)) {
            knotLinkSubscriber.setDataReceivedListener(data -> {
                LOGGER.fine("收到 KnotLink 广播数据: " + data);
                if (signalListener != null) {
                    signalListener.onSignalReceived(data);
                }
            });

            String s_key = appID + "-" + signalID;
            knotLinkSubscriber.sendData(s_key);
            LOGGER.info("SignalSubscriber started and subscribed to " + s_key + ".");
        } else {
            LOGGER.severe("SignalSubscriber failed to start.");
        }
    }

    /**
     * 停止订阅器
     */
    public void stop() {
        if (knotLinkSubscriber != null) {
            knotLinkSubscriber.close();
            LOGGER.info("SignalSubscriber stopped.");
        }
    }
}
