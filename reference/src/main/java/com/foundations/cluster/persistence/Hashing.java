package com.foundations.cluster.persistence;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

final class Hashing {
    private Hashing() {}

    static String sha256(Path file) throws IOException {
        MessageDigest digest = digest();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static String aggregate(List<RegionFileSnapshot> files) {
        MessageDigest digest = digest();
        files.stream().sorted((a, b) -> a.kind().compareTo(b.kind())).forEach(f -> {
            String line = f.kind() + "|" + f.present() + "|" + f.bytes() + "|" + f.sha256() + "\n";
            digest.update(line.getBytes(StandardCharsets.UTF_8));
        });
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
