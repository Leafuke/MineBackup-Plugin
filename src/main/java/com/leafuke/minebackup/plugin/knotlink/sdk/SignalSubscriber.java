/*
 * KnotLink SDK - Java
 * Copyright (c) 2024-2026 KnotLink Contributors
 * SPDX-License-Identifier: MIT
 */

package com.leafuke.minebackup.plugin.knotlink.sdk;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class SignalSubscriber implements AutoCloseable {
    private final String appId;
    private final String signalId;
    private final String host;
    private final int port;
    private final int connectTimeoutMillis;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean stopping = new AtomicBoolean();

    private volatile Consumer<String> signalListener;
    private volatile Consumer<Throwable> disconnectListener;
    private volatile TcpClient client;

    public SignalSubscriber(String appId, String signalId, String host, int port, int connectTimeoutMillis) {
        this.appId = requireId(appId, "appId");
        this.signalId = requireId(signalId, "signalId");
        this.host = Objects.requireNonNull(host, "host");
        if (host.isBlank() || port < 1 || port > 65_535 || connectTimeoutMillis <= 0) {
            throw new IllegalArgumentException("Invalid KnotLink subscriber endpoint");
        }
        this.port = port;
        this.connectTimeoutMillis = connectTimeoutMillis;
    }

    public void setSignalListener(Consumer<String> listener) {
        signalListener = Objects.requireNonNull(listener, "listener");
    }

    public void setDisconnectListener(Consumer<Throwable> listener) {
        disconnectListener = listener;
    }

    public void start() throws IOException {
        if (signalListener == null) {
            throw new IllegalStateException("Signal listener must be set before starting");
        }
        if (stopping.get() || !started.compareAndSet(false, true)) {
            throw new IOException("KnotLink signal subscriber cannot be started");
        }
        TcpClient candidate = new TcpClient(TcpClient.FrameFormat.MAGIC_V2);
        client = candidate;
        candidate.setDataReceivedListener(data -> signalListener.accept(data));
        candidate.setClosedListener(cause -> {
            if (!stopping.get()) {
                Consumer<Throwable> listener = disconnectListener;
                if (listener != null) {
                    listener.accept(cause);
                }
            }
        });
        try {
            candidate.connect(host, port, connectTimeoutMillis);
            candidate.sendData(appId + "-" + signalId);
        } catch (IOException | RuntimeException exception) {
            stopping.set(true);
            candidate.close();
            throw exception;
        }
    }

    public boolean isRunning() {
        TcpClient current = client;
        return current != null && current.isRunning();
    }

    @Override
    public void close() {
        stopping.set(true);
        TcpClient current = client;
        client = null;
        if (current != null) {
            current.close();
        }
    }

    private static String requireId(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
