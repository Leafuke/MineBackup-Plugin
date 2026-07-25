package com.leafuke.minebackup.plugin.dedicated;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class WorldReleaseProbe {
    private WorldReleaseProbe() {
    }

    public static boolean isReleased(Path root) {
        return canAcquire(root.resolve("session.lock"))
                && canWrite(root.resolve("level.dat"))
                && canWrite(root.resolve("level.dat_old"))
                && canWriteRegionSample(root.resolve("region"));
    }

    private static boolean canAcquire(Path lockPath) {
        if (!Files.exists(lockPath)) {
            return true;
        }
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.WRITE);
             FileLock lock = channel.tryLock()) {
            return lock != null;
        } catch (OverlappingFileLockException | IOException exception) {
            return false;
        }
    }

    private static boolean canWriteRegionSample(Path directory) {
        if (!Files.isDirectory(directory)) {
            return true;
        }
        try (var files = Files.list(directory)) {
            Path sample = files.filter(Files::isRegularFile).findFirst().orElse(null);
            return sample == null || canWrite(sample);
        } catch (IOException exception) {
            return false;
        }
    }

    private static boolean canWrite(Path path) {
        if (!Files.isRegularFile(path)) {
            return true;
        }
        try (FileChannel ignored = FileChannel.open(path, StandardOpenOption.WRITE)) {
            return true;
        } catch (IOException exception) {
            return false;
        }
    }
}
