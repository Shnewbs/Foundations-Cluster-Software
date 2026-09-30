package com.foundations.cluster.manager;

import java.io.IOException;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

final class ManagerLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;
    private final Path pidFile;

    private ManagerLock(FileChannel channel, FileLock lock, Path pidFile) throws IOException {
        this.channel=channel; this.lock=lock; this.pidFile=pidFile;
        Files.writeString(pidFile, Long.toString(ProcessHandle.current().pid()) + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    static ManagerLock acquire(Path runtime) throws IOException {
        Files.createDirectories(runtime);
        Path file = runtime.resolve("cluster-manager.lock");
        FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock;
        try { lock = ch.tryLock(); }
        catch (OverlappingFileLockException e) { lock = null; }
        if (lock == null) { ch.close(); throw new IOException("Another Foundations Cluster Manager is already running for this directory."); }
        return new ManagerLock(ch, lock, runtime.resolve("manager.pid"));
    }

    public void close() {
        try { Files.deleteIfExists(pidFile); } catch (IOException ignored) {}
        try { lock.release(); } catch (IOException ignored) {}
        try { channel.close(); } catch (IOException ignored) {}
    }
}
