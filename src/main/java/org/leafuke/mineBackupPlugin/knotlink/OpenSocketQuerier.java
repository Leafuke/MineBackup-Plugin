package org.leafuke.mineBackupPlugin.knotlink;

import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * KnotLink 开放式查询器
 * 通过一次性 TCP 短连接向 MineBackup 主程序发送请求并获取响应
 * 端口: 6376
 */
public class OpenSocketQuerier {
    private static final Logger LOGGER = Logger.getLogger("MineBackup-Querier");
    private static final String SERVER_IP = "127.0.0.1";
    private static final int QUERIER_PORT = 6376;

    /**
     * 异步发送查询请求
     *
     * @param appID        应用ID
     * @param openSocketID 套接字ID
     * @param question     查询命令
     * @return 包含响应字符串的 CompletableFuture
     */
    public static CompletableFuture<String> query(String appID, String openSocketID, String question) {
        return CompletableFuture.supplyAsync(() -> {
            try (Socket socket = new Socket(SERVER_IP, QUERIER_PORT);
                 PrintWriter out = new PrintWriter(new OutputStreamWriter(
                         socket.getOutputStream(), StandardCharsets.UTF_8), true);
                 InputStream in = socket.getInputStream()) {

                socket.setSoTimeout(5000); // 5秒超时

                String packet = String.format("%s-%s&*&%s", appID, openSocketID, question);
                LOGGER.info("Sending query to KnotLink: " + question);
                out.print(packet);
                out.flush();

                byte[] buffer = new byte[4096];
                int bytesRead = in.read(buffer);

                if (bytesRead > 0) {
                    String response = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
                    LOGGER.info("Received query response: " + response);
                    return response;
                } else {
                    LOGGER.warning("Received no response from KnotLink server.");
                    return "ERROR:NO_RESPONSE";
                }

            } catch (Exception e) {
                LOGGER.warning("Failed to query KnotLink server for command '"
                        + question + "': " + e.getMessage());
                return "ERROR:COMMUNICATION_FAILED";
            }
        });
    }
}
