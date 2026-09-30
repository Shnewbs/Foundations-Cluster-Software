package com.foundations.cluster.manager;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

/** Inspect the embedded mod declaration, never trust a renamed JAR's filename. */
public final class NodeArtifact {
    private NodeArtifact() {}
    public static final String MOD_ID = "foundations_cluster_node";
    private static final int METADATA_LIMIT = 65_536;
    private static final Pattern VALUE = Pattern.compile("^\\s*(modId|version)\\s*=\\s*[\"']([^\"']+)[\"']\\s*(?:#.*)?$");

    public static boolean isVersion(Path path, String expected) {
        try { requireVersion(path, expected); return true; } catch (IOException e) { return false; }
    }

    public static void requireVersion(Path path, String expected) throws IOException {
        if (!Files.isRegularFile(path)) throw new IOException("file missing: " + path);
        try (ZipFile jar = new ZipFile(path.toFile())) {
            ZipEntry entry = jar.getEntry("META-INF/neoforge.mods.toml");
            if (entry == null) throw new IOException("missing NeoForge metadata: " + path);
            byte[] data;
            try (InputStream in = jar.getInputStream(entry)) { data = in.readNBytes(METADATA_LIMIT + 1); }
            if (data.length > METADATA_LIMIT) throw new IOException("oversized NeoForge metadata: " + path);
            String id = "", version = ""; boolean inMod = false, found = false;
            for (String line : new String(data, StandardCharsets.UTF_8).replace("\uFEFF", "").split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("[")) {
                    if (inMod && MOD_ID.equals(id)) {
                        if (found) throw new IOException("duplicate Node declarations: " + path);
                        found = true;
                        if (!expected.equals(version)) throw new IOException("embedded Node version is " + version + ", expected " + expected + ": " + path);
                    }
                    inMod = trimmed.equals("[[mods]]"); id = ""; version = "";
                } else if (inMod) {
                    Matcher m = VALUE.matcher(line);
                    if (m.matches()) { if (m.group(1).equals("modId")) id = m.group(2); else version = m.group(2); }
                }
            }
            if (inMod && MOD_ID.equals(id)) {
                if (found) throw new IOException("duplicate Node declarations: " + path);
                found = true;
                if (!expected.equals(version)) throw new IOException("embedded Node version is " + version + ", expected " + expected + ": " + path);
            }
            if (!found) throw new IOException("Node mod declaration not found: " + path);
            for (String required : List.of("com/foundations/cluster/node/FoundationsClusterNode.class", "foundations_cluster_node.mixins.json")) {
                if (jar.getEntry(required) == null) throw new IOException("required Node entry missing: " + required + ": " + path);
            }
        } catch (ZipException e) { throw new IOException("invalid Node JAR: " + path, e); }
    }
}
