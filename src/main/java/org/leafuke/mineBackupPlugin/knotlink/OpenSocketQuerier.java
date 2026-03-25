package org.leafuke.mineBackupPlugin.knotlink;

import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

public final class OpenSocketQuerier {
    private static final Logger LOGGER = Logger.getLogger("MineBackup-Querier");
    private static final String SERVER_IP = "127.0.0.1";
    private static final int QUERIER_PORT = 6376;
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 2000;
    private static final int DEFAULT_READ_TIMEOUT_MS = 5000;

    private static volatile ExecutorService executor =
            Executors.newCachedThreadPool(new NamedThreadFactory("minebackup-querier-"));

    private OpenSocketQuerier() {
    }

    public static synchronized void initializeExecutor() {
        if (executor == null || executor.isShutdown()) {
            executor = Executors.newCachedThreadPool(new NamedThreadFactory("minebackup-querier-"));
        }
    }

    public static synchronized void shutdownExecutor() {
        if (executor == null) {
            return;
        }

        executor.shutdownNow();
        try {
            executor.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    public static CompletableFuture<String> query(String appID, String openSocketID, String question) {
        ExecutorService currentExecutor = executor;
        if (currentExecutor == null || currentExecutor.isShutdown()) {
            initializeExecutor();
            currentExecutor = executor;
        }

        return CompletableFuture.supplyAsync(
                () -> queryBlocking(appID, openSocketID, question, DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_READ_TIMEOUT_MS),
                currentExecutor
        );
    }

    public static String queryBlocking(String appID, String openSocketID, String question,
                                       int connectTimeoutMs, int readTimeoutMs) {
        Objects.requireNonNull(appID, "appID");
        Objects.requireNonNull(openSocketID, "openSocketID");
        Objects.requireNonNull(question, "question");

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(SERVER_IP, QUERIER_PORT), connectTimeoutMs);
            socket.setSoTimeout(readTimeoutMs);

            try (PrintWriter out = new PrintWriter(new OutputStreamWriter(
                    socket.getOutputStream(), StandardCharsets.UTF_8), true);
                 InputStream in = socket.getInputStream()) {

                String packet = String.format("%s-%s&*&%s", appID, openSocketID, question);
                LOGGER.info("Sending query to KnotLink: " + question);
                out.print(packet);
                out.flush();

                byte[] buffer = new byte[8192];
                int bytesRead = in.read(buffer);
                if (bytesRead > 0) {
                    String response = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
                    LOGGER.info("Received query response: " + response);
                    return response;
                }

                LOGGER.warning("Received no response from KnotLink server.");
                return "ERROR:NO_RESPONSE";
            }
        } catch (Exception e) {
            LOGGER.warning("Failed to query KnotLink server for command '" + question + "': " + e.getMessage());
            return "ERROR:COMMUNICATION_FAILED";
        }
    }

    private static final class NamedThreadFactory implements ThreadFactory {
        private final AtomicInteger index = new AtomicInteger(1);
        private final String prefix;

        private NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, prefix + index.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
