package com.foundations.cluster.manager;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.TimeUnit;

final class SecretVault {
    private static final SecureRandom RNG = new SecureRandom();
    private final Path dir;
    private final Path vaultFile;
    private final byte[] key;
    private final Properties encrypted = new Properties();

    SecretVault(Path securityDir) throws Exception {
        this.dir = securityDir;
        this.vaultFile = dir.resolve("secrets.vault");
        Files.createDirectories(dir);
        this.key = loadOrCreateMasterKey();
        if (Files.isRegularFile(vaultFile)) try (InputStream in = Files.newInputStream(vaultFile)) { encrypted.load(in); }
    }

    synchronized String getOrCreate(String name, int bytes) throws Exception {
        String existing = get(name);
        if (existing != null && !existing.isBlank()) return existing;
        byte[] b = new byte[bytes]; RNG.nextBytes(b);
        String secret = HexFormat.of().formatHex(b);
        put(name, secret);
        return secret;
    }

    synchronized String get(String name) throws Exception {
        String v = encrypted.getProperty(name);
        if (v == null || v.isBlank()) return null;
        String[] parts = v.split("\\.", 2);
        if (parts.length != 2) throw new IOException("Invalid encrypted vault entry: " + name);
        byte[] nonce = Base64.getDecoder().decode(parts[0]);
        byte[] cipher = Base64.getDecoder().decode(parts[1]);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key,"AES"), new GCMParameterSpec(128, nonce));
        c.updateAAD(name.getBytes(StandardCharsets.UTF_8));
        return new String(c.doFinal(cipher), StandardCharsets.UTF_8);
    }

    synchronized void put(String name, String value) throws Exception {
        byte[] nonce = new byte[12]; RNG.nextBytes(nonce);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key,"AES"), new GCMParameterSpec(128, nonce));
        c.updateAAD(name.getBytes(StandardCharsets.UTF_8));
        byte[] cipher = c.doFinal(value.getBytes(StandardCharsets.UTF_8));
        encrypted.setProperty(name, Base64.getEncoder().encodeToString(nonce)+"."+Base64.getEncoder().encodeToString(cipher));
        save();
    }

    private void save() throws IOException {
        Path tmp = vaultFile.resolveSibling(vaultFile.getFileName()+".tmp");
        try (OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            encrypted.store(out, "Foundations Cluster Runtime encrypted secrets. Do not edit.");
        }
        try { Files.move(tmp,vaultFile,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(tmp,vaultFile,StandardCopyOption.REPLACE_EXISTING); }
        tighten(vaultFile);
    }

    private byte[] loadOrCreateMasterKey() throws Exception {
        if (isWindows()) {
            Path p = dir.resolve("vault-key.dpapi");
            if (Files.isRegularFile(p)) return dpapiUnprotect(Files.readString(p,StandardCharsets.UTF_8).trim());
            byte[] raw = new byte[32]; RNG.nextBytes(raw);
            String protectedB64 = dpapiProtect(raw);
            Files.writeString(p,protectedB64+System.lineSeparator(),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
            return raw;
        }
        Path p = dir.resolve("vault-key.bin");
        if (Files.isRegularFile(p)) return Base64.getDecoder().decode(Files.readString(p,StandardCharsets.UTF_8).trim());
        byte[] raw = new byte[32]; RNG.nextBytes(raw);
        Files.writeString(p,Base64.getEncoder().encodeToString(raw)+System.lineSeparator(),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        tighten(p);
        return raw;
    }

    private static String dpapiProtect(byte[] raw) throws Exception {
        String b64 = Base64.getEncoder().encodeToString(raw);
        String script = "Add-Type -AssemblyName System.Security;$d=[Convert]::FromBase64String($args[0]);$p=[System.Security.Cryptography.ProtectedData]::Protect($d,$null,[System.Security.Cryptography.DataProtectionScope]::CurrentUser);[Convert]::ToBase64String($p)";
        return runPowerShell(script,b64);
    }
    private static byte[] dpapiUnprotect(String protectedB64) throws Exception {
        String script = "Add-Type -AssemblyName System.Security;$d=[Convert]::FromBase64String($args[0]);$p=[System.Security.Cryptography.ProtectedData]::Unprotect($d,$null,[System.Security.Cryptography.DataProtectionScope]::CurrentUser);[Convert]::ToBase64String($p)";
        return Base64.getDecoder().decode(runPowerShell(script,protectedB64));
    }
    private static String runPowerShell(String script, String arg) throws Exception {
        // Use -EncodedCommand plus an environment variable rather than appending user data
        // to -Command. This avoids Windows PowerShell argument parsing differences and gives
        // us a hard timeout so first-run DPAPI initialization can never hang the manager.
        String wrapped = "$ErrorActionPreference='Stop';$inputB64=$env:FOUNDATIONS_DPAPI_INPUT;" + script.replace("$args[0]", "$inputB64");
        String encoded = Base64.getEncoder().encodeToString(wrapped.getBytes(StandardCharsets.UTF_16LE));
        ProcessBuilder pb = new ProcessBuilder("powershell.exe","-NoLogo","-NoProfile","-NonInteractive","-ExecutionPolicy","Bypass","-EncodedCommand",encoded);
        pb.environment().put("FOUNDATIONS_DPAPI_INPUT", arg);
        Process p = pb.start();
        boolean finished = p.waitFor(15, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            throw new IOException("Windows DPAPI operation timed out after 15 seconds.");
        }
        String out; String err;
        try (InputStream in = p.getInputStream(); InputStream ein = p.getErrorStream()) {
            out = new String(in.readAllBytes(),StandardCharsets.UTF_8).trim();
            err = new String(ein.readAllBytes(),StandardCharsets.UTF_8).trim();
        }
        int code = p.exitValue();
        if (code != 0 || out.isBlank()) {
            String detail = !err.isBlank()?err:out;
            throw new IOException("Windows DPAPI operation failed" + (detail.isBlank()?"":": "+detail));
        }
        return out.lines().reduce((a,b)->b).orElse(out).trim();
    }

    private static void tighten(Path p) {
        try { Files.setPosixFilePermissions(p, EnumSet.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE)); }
        catch (Exception ignored) {}
    }
    private static boolean isWindows(){return System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("win");}
}
