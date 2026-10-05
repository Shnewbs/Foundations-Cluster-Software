package com.foundations.cluster.manager;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;

/** Explicit write/force/rename ordering shared by security files and journals. */
final class DurableFiles {
    private DurableFiles() {}

    static void writeFully(WritableByteChannel channel, ByteBuffer bytes) throws IOException {
        while (bytes.hasRemaining()) channel.write(bytes);
    }

    /** Returns false if the platform cannot force directory metadata.
     * Callers must retain their WAL in that case. No non-atomic replace fallback.
     */
    static boolean replace(Path target, byte[] bytes) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path temp;
        if (target.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            temp = Files.createTempFile(target.toAbsolutePath().getParent(), ".foundations-", ".tmp",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } else temp = Files.createTempFile(target.toAbsolutePath().getParent(), ".foundations-", ".tmp");
        try {
            try (FileChannel ch = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                writeFully(ch, ByteBuffer.wrap(bytes));
                ch.force(true);
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return forceDirectory(target.toAbsolutePath().getParent());
        } finally { Files.deleteIfExists(temp); }
    }

    static void validateJournal(Path file) throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return;
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Recovery file is not a regular file: " + file);
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            if (ch.size() == 0) return;
            ByteBuffer last = ByteBuffer.allocate(1);
            ch.position(ch.size() - 1);
            if (ch.read(last) != 1 || last.array()[0] != '\n')
                throw new IOException("Incomplete recovery record in " + file + "; restore/reconcile before starting");
        }
    }

    static boolean forceDirectory(Path directory) {
        try (FileChannel ch = FileChannel.open(directory, StandardOpenOption.READ)) {
            ch.force(true);
            return true;
        } catch (IOException | UnsupportedOperationException e) { return false; }
    }
}
