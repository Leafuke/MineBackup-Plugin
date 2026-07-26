package com.leafuke.minebackup.plugin.knotlink;

import com.leafuke.minebackup.plugin.knotlink.protocol.KnotLinkCodec;
import com.leafuke.minebackup.plugin.knotlink.protocol.KnotLinkRequest;
import com.leafuke.minebackup.plugin.knotlink.protocol.KnotLinkResponse;
import com.leafuke.minebackup.plugin.knotlink.sdk.OpenSocketQuerier;
import com.leafuke.minebackup.plugin.knotlink.sdk.SignalSubscriber;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class KnotLinkClient implements AutoCloseable {
    private static final String HOST = "127.0.0.1";
    private static final int QUERY_PORT = 6376;
    private static final int SUBSCRIBER_PORT = 6372;
    private static final String APP_ID = "0x00000020";
    private static final String OPEN_SOCKET_ID = "0x00000010";
    private static final String SIGNAL_ID = "0x00000020";
    private static final int CONNECT_TIMEOUT_MILLIS = 5_000;
    private static final int RESPONSE_TIMEOUT_MILLIS = 5_000;
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;

    private final Logger logger;
    private final ThreadPoolExecutor queryExecutor = new ThreadPoolExecutor(
            4, 4, 30L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64),
            daemonFactory("minebackup-knotlink-query-"), new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService reconnectExecutor = Executors.newSingleThreadScheduledExecutor(
            daemonFactory("minebackup-knotlink-reconnect-"));
    private final Object subscriberLock = new Object();

    private volatile boolean closed;
    private volatile boolean connected;
    private boolean subscriberEnabled;
    private int reconnectDelaySeconds = 1;
    private SignalSubscriber subscriber;
    private Consumer<Map<String, String>> signalListener;

    public KnotLinkClient(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public CompletableFuture<KnotLinkResponse> query(KnotLinkRequest request) {
        Objects.requireNonNull(request, "request");
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("KnotLink client is closed"));
        }
        try {
            return CompletableFuture.supplyAsync(() -> {
                try (OpenSocketQuerier querier = new OpenSocketQuerier(
                        APP_ID, OPEN_SOCKET_ID, HOST, QUERY_PORT, CONNECT_TIMEOUT_MILLIS, MAX_RESPONSE_BYTES)) {
                    String payload = querier.query(request.serialize(), RESPONSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
                    if (payload.getBytes(StandardCharsets.UTF_8).length > MAX_RESPONSE_BYTES) {
                        throw new IOException("KnotLink response exceeds one MiB");
                    }
                    return KnotLinkResponse.parse(payload);
                } catch (Exception exception) {
                    throw new CompletionException(exception);
                }
            }, queryExecutor);
        } catch (RejectedExecutionException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    public void startSubscriber(Consumer<Map<String, String>> listener) {
        Objects.requireNonNull(listener, "listener");
        synchronized (subscriberLock) {
            signalListener = listener;
            if (closed || subscriberEnabled) {
                return;
            }
            subscriberEnabled = true;
            reconnectDelaySeconds = 1;
        }
        scheduleConnect(0);
    }

    public Status status() {
        return new Status(connected, !closed && subscriberEnabled,
                queryExecutor.getActiveCount(), queryExecutor.getQueue().size());
    }

    private void scheduleConnect(int delaySeconds) {
        if (!shouldConnect()) {
            return;
        }
        try {
            reconnectExecutor.schedule(this::connectSubscriber, delaySeconds, TimeUnit.SECONDS);
        } catch (RejectedExecutionException ignored) {
        }
    }

    private void connectSubscriber() {
        SignalSubscriber candidate = new SignalSubscriber(
                APP_ID, SIGNAL_ID, HOST, SUBSCRIBER_PORT, CONNECT_TIMEOUT_MILLIS);
        candidate.setSignalListener(this::dispatchSignal);
        candidate.setDisconnectListener(cause -> disconnected(candidate, cause));
        synchronized (subscriberLock) {
            if (!subscriberEnabled || closed || subscriber != null) {
                return;
            }
            subscriber = candidate;
        }
        try {
            candidate.start();
            synchronized (subscriberLock) {
                if (subscriber == candidate && subscriberEnabled && !closed) {
                    connected = true;
                    reconnectDelaySeconds = 1;
                    logger.info("Connected to KnotLink SDK 2.0 signal channel");
                    return;
                }
            }
            candidate.close();
        } catch (IOException | RuntimeException exception) {
            candidate.close();
            boolean reconnect = clearSubscriber(candidate);
            if (reconnect) {
                int delay = nextDelay();
                logReconnectFailure("KnotLink signal connection failed", delay, exception);
                scheduleConnect(delay);
            }
        }
    }

    private void dispatchSignal(String payload) {
        try {
            Map<String, String> fields = KnotLinkCodec.parse(payload);
            Consumer<Map<String, String>> listener;
            synchronized (subscriberLock) {
                listener = signalListener;
            }
            if (listener != null) {
                listener.accept(fields);
            }
        } catch (Exception exception) {
            logger.log(Level.WARNING, "Rejected malformed KnotLink v2 signal", exception);
        }
    }

    private void disconnected(SignalSubscriber candidate, Throwable cause) {
        if (!clearSubscriber(candidate)) {
            return;
        }
        int delay = nextDelay();
        logReconnectFailure("KnotLink signal channel disconnected", delay, cause);
        scheduleConnect(delay);
    }

    private void logReconnectFailure(String message, int delaySeconds, Throwable cause) {
        String retryMessage = message + "; retrying in " + delaySeconds + "s";
        if (delaySeconds == 1) {
            logger.log(Level.WARNING, retryMessage, cause);
            return;
        }
        String detail = cause == null || cause.getMessage() == null
                ? ""
                : ": " + cause.getMessage();
        logger.warning(retryMessage + detail);
    }

    private boolean clearSubscriber(SignalSubscriber candidate) {
        synchronized (subscriberLock) {
            if (subscriber != candidate) {
                return false;
            }
            subscriber = null;
            connected = false;
            return subscriberEnabled && !closed;
        }
    }

    private int nextDelay() {
        synchronized (subscriberLock) {
            int current = reconnectDelaySeconds;
            reconnectDelaySeconds = Math.min(30, reconnectDelaySeconds * 2);
            return current;
        }
    }

    private boolean shouldConnect() {
        synchronized (subscriberLock) {
            return subscriberEnabled && !closed && subscriber == null;
        }
    }

    @Override
    public void close() {
        SignalSubscriber current;
        synchronized (subscriberLock) {
            if (closed) {
                return;
            }
            closed = true;
            connected = false;
            subscriberEnabled = false;
            signalListener = null;
            current = subscriber;
            subscriber = null;
        }
        if (current != null) {
            current.close();
        }
        reconnectExecutor.shutdownNow();
        queryExecutor.shutdownNow();
    }

    private static ThreadFactory daemonFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    public record Status(boolean connected, boolean reconnecting, int activeQueries, int queuedQueries) {
    }
}
