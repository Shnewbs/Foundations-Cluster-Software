package com.foundations.cluster.manager;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;

final class TokenStore {
    final String adminToken;
    final String agentToken;
    final String gatewayToken;

    TokenStore(Path runtimeDir) throws Exception {
        Path security = runtimeDir.resolve("security");
        SecretVault vault = new SecretVault(security);
        adminToken = migrateOrCreate(vault,"adminToken",runtimeDir.resolve("cluster-admin.token"));
        agentToken = migrateOrCreate(vault,"agentToken",runtimeDir.resolve("cluster-agent.token"));
        gatewayToken = migrateOrCreate(vault,"gatewayToken",runtimeDir.resolve("cluster-gateway.token"));
    }

    private static String migrateOrCreate(SecretVault vault, String name, Path legacy) throws Exception {
        String existing = vault.get(name);
        if (existing != null && !existing.isBlank()) {
            Files.deleteIfExists(legacy);
            return existing;
        }
        if (Files.isRegularFile(legacy)) {
            String s = Files.readString(legacy,StandardCharsets.UTF_8).trim();
            if (!s.isBlank()) {
                vault.put(name,s);
                Files.deleteIfExists(legacy);
                return s;
            }
        }
        return vault.getOrCreate(name,32);
    }
}
