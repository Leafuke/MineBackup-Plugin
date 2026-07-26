/*
 * KnotLink SDK - Java
 * Copyright (c) 2024-2026 KnotLink Contributors
 * SPDX-License-Identifier: MIT
 */

package com.leafuke.minebackup.plugin.knotlink.sdk;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class TcpClient implements AutoCloseable {
    public enum FrameFormat {
        MAGIC_V2
    }

    public static final int DEFAULT_MAX_MESSAGE_BYTES = 16 * 1024 * 1024;

    private final Duration readTimeout;
    private final int maxMessageBytes;
    // 生命周期锁保护 socket/reader 的替换，写锁保证并发请求不会交错写坏同一帧。
    private final Object lifecycleLock = new Object();
    private final Object writeLock = new Object();
    private final AtomicBoolean closedNotified = new AtomicBoolean();

    private volatile Socket socket;
    private volatile Thread readerThread;
    private volatile boolean closed;
    private volatile Consumer<String> dataReceivedListener;
    private volatile Consumer<Throwable> closedListener;

    public TcpClient(FrameFormat frameFormat) {
        this(Duration.ofMinutes(3), frameFormat, DEFAULT_MAX_MESSAGE_BYTES);
    }

    public TcpClient(Duration readTimeout, FrameFormat frameFormat, int maxMessageBytes) {
        this.readTimeout = Objects.requireNonNull(readTimeout, "readTimeout");
        Objects.requireNonNull(frameFormat, "frameFormat");
        // Duration.ZERO 映射为 Socket 的 0 超时，即长连接永久等待；负数仍非法。
        if (readTimeout.isNegative() || readTimeout.toMillis() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("readTimeout is invalid");
        }
        if (maxMessageBytes < 1) {
            throw new IllegalArgumentException("maxMessageBytes must be positive");
        }
        this.maxMessageBytes = maxMessageBytes;
    }

    public void setDataReceivedListener(Consumer<String> listener) {
        dataReceivedListener = Objects.requireNonNull(listener, "listener");
    }

    public void setClosedListener(Consumer<Throwable> listener) {
        closedListener = listener;
    }

    public void connect(String host, int port, int timeoutMillis) throws IOException {
        Objects.requireNonNull(host, "host");
        if (host.isBlank() || port < 1 || port > 65_535 || timeoutMillis <= 0) {
            throw new IllegalArgumentException("Invalid KnotLink endpoint");
        }
        Socket candidate = new Socket();
        candidate.setTcpNoDelay(true);
        candidate.setKeepAlive(true);
        candidate.setSoTimeout((int) readTimeout.toMillis());
        try {
            candidate.connect(new InetSocketAddress(host, port), timeoutMillis);
            synchronized (lifecycleLock) {
                if (closed) {
                    throw new IOException("KnotLink TCP client is closed");
                }
                if (socket != null) {
                    throw new IOException("KnotLink TCP client is already connected");
                }
                socket = candidate;
                Thread reader = new Thread(() -> readLoop(candidate), "knotlink-tcp-reader");
                reader.setDaemon(true);
                readerThread = reader;
                reader.start();
            }
        } catch (IOException | RuntimeException exception) {
            try {
                candidate.close();
            } catch (IOException ignored) {
            }
            throw exception;
        }
    }

    public void sendData(String message) throws IOException {
        Objects.requireNonNull(message, "message");
        byte[] frame = FrameCodec.encode(message, maxMessageBytes);
        synchronized (writeLock) {
            Socket current = socket;
            if (closed || current == null || current.isClosed()) {
                throw new IOException("KnotLink TCP client is not connected");
            }
            OutputStream output = current.getOutputStream();
            output.write(frame);
            output.flush();
        }
    }

    public boolean isRunning() {
        Socket current = socket;
        return !closed && current != null && current.isConnected() && !current.isClosed();
    }

    private void readLoop(Socket connectedSocket) {
        Throwable failure = null;
        try {
            InputStream input = connectedSocket.getInputStream();
            while (!closed) {
                String data = FrameCodec.read(input, maxMessageBytes);
                Consumer<String> listener = dataReceivedListener;
                if (listener != null) {
                    listener.accept(data);
                }
            }
        } catch (Throwable exception) {
            if (!closed) {
                failure = exception;
            }
        } finally {
            closeInternal(failure);
        }
    }

    @Override
    public void close() {
        closeInternal(null);
        Thread reader = readerThread;
        if (reader != null && reader != Thread.currentThread()) {
            try {
                reader.join(1_000L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void closeInternal(Throwable cause) {
        Socket current;
        synchronized (lifecycleLock) {
            closed = true;
            current = socket;
            socket = null;
        }
        if (current != null) {
            try {
                current.close();
            } catch (IOException ignored) {
            }
        }
        // 无论读线程、调用方还是连接失败先触发关闭，断连回调都只能通知一次。
        Consumer<Throwable> listener = closedListener;
        if (listener != null && closedNotified.compareAndSet(false, true)) {
            listener.accept(cause);
        }
    }
}
