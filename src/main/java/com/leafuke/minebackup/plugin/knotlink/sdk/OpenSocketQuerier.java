/*
 * KnotLink SDK - Java
 * Copyright (c) 2024-2026 KnotLink Contributors
 * SPDX-License-Identifier: MIT
 */

package com.leafuke.minebackup.plugin.knotlink.sdk;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class OpenSocketQuerier implements AutoCloseable {
    private final String appId;
    private final String openSocketId;
    private final TcpClient client;
    private final AtomicReference<CompletableFuture<String>> pending = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public OpenSocketQuerier(
            String appId,
            String openSocketId,
            String host,
            int port,
            int connectTimeoutMillis,
            int maxMessageBytes) throws IOException {
        this.appId = requireId(appId, "appId");
        this.openSocketId = requireId(openSocketId, "openSocketId");
        client = new TcpClient(Duration.ofMinutes(3), TcpClient.FrameFormat.MAGIC_V2, maxMessageBytes);
        client.setDataReceivedListener(this::onDataReceived);
        client.setClosedListener(this::onClosed);
        client.connect(host, port, connectTimeoutMillis);
    }

    public CompletableFuture<String> queryAsync(String payload) {
        Objects.requireNonNull(payload, "payload");
        if (closed.get()) {
            return CompletableFuture.failedFuture(new IOException("KnotLink querier is closed"));
        }
        CompletableFuture<String> future = new CompletableFuture<>();
        if (!pending.compareAndSet(null, future)) {
            return CompletableFuture.failedFuture(new IllegalStateException("A KnotLink query is already pending"));
        }
        try {
            client.sendData(appId + "-" + openSocketId + "&*&" + payload);
        } catch (IOException exception) {
            pending.compareAndSet(future, null);
            future.completeExceptionally(exception);
        }
        future.whenComplete((ignored, error) -> pending.compareAndSet(future, null));
        return future;
    }

    public String query(String payload, long timeout, TimeUnit unit) throws Exception {
        if (timeout <= 0) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        CompletableFuture<String> future = queryAsync(payload);
        try {
            return future.get(timeout, unit);
        } catch (TimeoutException exception) {
            if (pending.compareAndSet(future, null)) {
                future.completeExceptionally(exception);
            }
            throw exception;
        }
    }

    private void onDataReceived(String data) {
        CompletableFuture<String> future = pending.getAndSet(null);
        if (future != null) {
            future.complete(data);
        }
    }

    private void onClosed(Throwable cause) {
        CompletableFuture<String> future = pending.getAndSet(null);
        if (future != null) {
            IOException failure = new IOException("KnotLink connection closed before a response", cause);
            future.completeExceptionally(failure);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        CompletableFuture<String> future = pending.getAndSet(null);
        if (future != null) {
            future.completeExceptionally(new IOException("KnotLink query was cancelled"));
        }
        client.close();
    }

    private static String requireId(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
