package com.leafuke.minebackup.plugin.runtime;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ManagedResources implements AutoCloseable {
    private final Deque<AutoCloseable> resources = new ArrayDeque<>();
    private final Logger logger;
    private boolean closed;

    public ManagedResources(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public synchronized <T extends AutoCloseable> T add(T resource) {
        if (closed) {
            throw new IllegalStateException("Managed resources are closed");
        }
        resources.push(Objects.requireNonNull(resource, "resource"));
        return resource;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        while (!resources.isEmpty()) {
            try {
                resources.pop().close();
            } catch (Exception exception) {
                logger.log(Level.WARNING, "Failed to close a MineBackup service", exception);
            }
        }
    }
}
