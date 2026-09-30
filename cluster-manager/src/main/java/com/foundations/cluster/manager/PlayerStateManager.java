package com.foundations.cluster.manager;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Cluster-wide lightweight player state used to make reconnects consistent across workers. */
final class PlayerStateManager implements AutoCloseable {
    private final ManagerConfig cfg;
    private final ClusterLog log;
    private final ConcurrentHashMap<String, ClusterPlayerState> byUuid = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> uuidByName = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "foundations-player-state"); t.setDaemon(true); return t;
    });
    private final Path stateFile;
    private volatile boolean dirty;

    PlayerStateManager(ManagerConfig cfg, ClusterLog log) throws IOException {
        this.cfg = cfg; this.log = log;
        this.stateFile = cfg.home().resolve("runtime/player-state.tsv");
        Files.createDirectories(stateFile.getParent());
        load();
        scheduler.scheduleAtFixedRate(this::flushSafe, 5, 5, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::expireSafe, 60, 60, TimeUnit.SECONDS);
        log.info("Cluster Player State initialized: records=" + byUuid.size() + " restoreBasic=" + cfg.getBoolean("playerState.restoreBasic", true));
    }

    Collection<ClusterPlayerState> all() { return new ArrayList<>(byUuid.values()); }

    ClusterPlayerState find(String uuid, String name) {
        if (uuid != null && !uuid.isBlank()) {
            ClusterPlayerState s = byUuid.get(uuid.toLowerCase(Locale.ROOT));
            if (s != null) return s;
        }
        if (name != null && !name.isBlank()) {
            String id = uuidByName.get(name.toLowerCase(Locale.ROOT));
            if (id != null) return byUuid.get(id);
        }
        return null;
    }

    ClusterPlayerState findByName(String name) { return find("", name); }

    ClusterPlayerState update(Map<String,String> m) {
        String uuid = m.getOrDefault("uuid", "").trim().toLowerCase(Locale.ROOT);
        String name = m.getOrDefault("name", "").trim();
        if (uuid.isBlank()) throw new IllegalArgumentException("uuid is required");
        ClusterPlayerState s = byUuid.computeIfAbsent(uuid, ClusterPlayerState::new);
        synchronized (s) {
            s.name = name.isBlank() ? (s.name == null ? "" : s.name) : name;
            s.workerId = m.getOrDefault("workerId", s.workerId == null ? "" : s.workerId);
            s.dimensionId = m.getOrDefault("dimensionId", s.dimensionId == null ? "minecraft:overworld" : s.dimensionId);
            s.x = d(m.get("x"), s.x); s.y = d(m.get("y"), s.y); s.z = d(m.get("z"), s.z);
            s.yaw = (float)d(m.get("yaw"), s.yaw); s.pitch = (float)d(m.get("pitch"), s.pitch);
            s.gameMode = m.getOrDefault("gameMode", s.gameMode == null ? "survival" : s.gameMode);
            if (m.containsKey("op")) s.op = Boolean.parseBoolean(m.get("op"));
            s.revision = Math.max(s.revision + 1, l(m.get("revision"), s.revision + 1));
            s.updatedAtMillis = System.currentTimeMillis();
            if (!s.name.isBlank()) uuidByName.put(s.name.toLowerCase(Locale.ROOT), uuid);
        }
        dirty = true;
        return s;
    }

    void ingestBlob(String workerId, String base64) { ingestBlob(workerId, base64, (uuid,name)->true); }

    void ingestBlob(String workerId, String base64, java.util.function.BiPredicate<String,String> allow) {
        if (base64 == null || base64.isBlank()) return;
        try {
            String text = new String(Base64.getUrlDecoder().decode(base64), StandardCharsets.UTF_8);
            for (String line : text.split("\\n")) {
                if (line.isBlank()) continue;
                String[] p = line.split("\\t", -1);
                if (p.length < 10) continue;
                Map<String,String> m = new HashMap<>();
                String decodedName=decField(p[1]);
                if(!allow.test(p[0],decodedName)) continue;
                m.put("uuid", p[0]); m.put("name", decodedName); m.put("dimensionId", decField(p[2]));
                m.put("x", p[3]); m.put("y", p[4]); m.put("z", p[5]); m.put("yaw", p[6]); m.put("pitch", p[7]);
                m.put("gameMode", decField(p[8])); m.put("op", p[9]); m.put("workerId", workerId);
                update(m);
            }
        } catch (Exception e) { log.warn("Player-state snapshot decode failed from " + workerId + ": " + e.getMessage()); }
    }

    void enrichPlacement(String playerName, Map<String,String> placement, int cellChunks) {
        ClusterPlayerState s = findByName(playerName);
        if (s == null) return;
        placement.put("dimensionId", s.dimensionId == null || s.dimensionId.isBlank() ? "minecraft:overworld" : s.dimensionId);
        int bx = (int)Math.floor(s.x), bz = (int)Math.floor(s.z);
        int chunkX = Math.floorDiv(bx, 16), chunkZ = Math.floorDiv(bz, 16);
        placement.put("cellX", Integer.toString(Math.floorDiv(chunkX, Math.max(1, cellChunks))));
        placement.put("cellZ", Integer.toString(Math.floorDiv(chunkZ, Math.max(1, cellChunks))));
    }


    ClusterPlayerState updateFromSnapshotJson(String body, String workerId) {
        if (body == null || body.isBlank()) return null;
        Map<String,String> m = new HashMap<>();
        m.put("uuid", jsonString(body,"uuid"));
        m.put("name", jsonString(body,"name"));
        m.put("dimensionId", jsonString(body,"dimensionId"));
        m.put("x", jsonNumber(body,"x","0")); m.put("y", jsonNumber(body,"y","64")); m.put("z", jsonNumber(body,"z","0"));
        m.put("yaw", jsonNumber(body,"yaw","0")); m.put("pitch", jsonNumber(body,"pitch","0"));
        m.put("gameMode", jsonString(body,"gameMode")); m.put("op", jsonBooleanText(body,"op","false"));
        m.put("workerId", workerId == null ? "" : workerId);
        if (m.get("uuid").isBlank()) throw new IllegalArgumentException("handoff snapshot missing uuid");
        return update(m);
    }

    private static String jsonString(String body,String key){String needle="\""+key+"\":\"";int i=body.indexOf(needle);if(i<0)return "";i+=needle.length();StringBuilder out=new StringBuilder();boolean esc=false;for(;i<body.length();i++){char c=body.charAt(i);if(esc){out.append(c);esc=false;}else if(c=='\\')esc=true;else if(c=='\"')break;else out.append(c);}return out.toString();}
    private static String jsonNumber(String body,String key,String def){String needle="\""+key+"\":";int i=body.indexOf(needle);if(i<0)return def;i+=needle.length();int j=i;while(j<body.length()&&"-+.0123456789Ee".indexOf(body.charAt(j))>=0)j++;return j==i?def:body.substring(i,j);}
    private static String jsonBooleanText(String body,String key,String def){String needle="\""+key+"\":";int i=body.indexOf(needle);if(i<0)return def;i+=needle.length();return body.startsWith("true",i)?"true":body.startsWith("false",i)?"false":def;}

    String restoreJson(String uuid, String name) {
        ClusterPlayerState s = find(uuid, name);
        if (s == null || !cfg.getBoolean("playerState.restoreBasic", true)) return "{\"found\":false}";
        return "{\"found\":true,\"uuid\":"+Json.q(s.uuid)+",\"name\":"+Json.q(s.name)+",\"dimensionId\":"+Json.q(s.dimensionId)
                +",\"x\":"+fmt(s.x)+",\"y\":"+fmt(s.y)+",\"z\":"+fmt(s.z)+",\"yaw\":"+fmt(s.yaw)+",\"pitch\":"+fmt(s.pitch)
                +",\"gameMode\":"+Json.q(s.gameMode)+",\"op\":"+s.op+",\"revision\":"+s.revision+",\"updatedAtMillis\":"+s.updatedAtMillis+"}";
    }

    private void expireSafe() {
        long days = Math.max(1, cfg.getLong("playerState.retentionDays", 30));
        long cutoff = System.currentTimeMillis() - days * 86_400_000L;
        boolean changed=false;
        for (var e : byUuid.entrySet()) {
            ClusterPlayerState s=e.getValue();
            if (s.updatedAtMillis < cutoff && byUuid.remove(e.getKey(), s)) { if (s.name!=null) uuidByName.remove(s.name.toLowerCase(Locale.ROOT), e.getKey()); changed=true; }
        }
        if (changed) dirty=true;
    }

    private void load() throws IOException {
        if (!Files.isRegularFile(stateFile)) return;
        for (String line : Files.readAllLines(stateFile, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            try {
                String[] p=line.split("\\t",-1); if(p.length<13)continue;
                ClusterPlayerState s=new ClusterPlayerState(dec(p[0]));
                s.name=dec(p[1]); s.workerId=dec(p[2]); s.dimensionId=dec(p[3]);
                s.x=Double.parseDouble(p[4]);s.y=Double.parseDouble(p[5]);s.z=Double.parseDouble(p[6]);
                s.yaw=Float.parseFloat(p[7]);s.pitch=Float.parseFloat(p[8]);s.gameMode=dec(p[9]);s.op=Boolean.parseBoolean(p[10]);s.revision=Long.parseLong(p[11]);s.updatedAtMillis=Long.parseLong(p[12]);
                byUuid.put(s.uuid.toLowerCase(Locale.ROOT),s); if(s.name!=null&&!s.name.isBlank())uuidByName.put(s.name.toLowerCase(Locale.ROOT),s.uuid.toLowerCase(Locale.ROOT));
            } catch(Exception ignored){}
        }
    }

    private synchronized void flushSafe(){if(!dirty)return;try{flush();}catch(Exception e){log.warn("Could not persist Cluster Player State: "+e.getMessage());}}
    private synchronized void flush() throws IOException {
        Path tmp=stateFile.resolveSibling(stateFile.getFileName()+".tmp");StringBuilder b=new StringBuilder("# Foundations Cluster Player State 0.0.5a\n");
        for(ClusterPlayerState s:byUuid.values().stream().sorted(Comparator.comparing(a->a.uuid)).toList()){
            b.append(enc(s.uuid)).append('\t').append(enc(s.name)).append('\t').append(enc(s.workerId)).append('\t').append(enc(s.dimensionId)).append('\t')
                    .append(s.x).append('\t').append(s.y).append('\t').append(s.z).append('\t').append(s.yaw).append('\t').append(s.pitch).append('\t')
                    .append(enc(s.gameMode)).append('\t').append(s.op).append('\t').append(s.revision).append('\t').append(s.updatedAtMillis).append('\n');
        }
        Files.writeString(tmp,b.toString(),StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);
        try{Files.move(tmp,stateFile,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(tmp,stateFile,StandardCopyOption.REPLACE_EXISTING);}dirty=false;
    }

    private static double d(String s,double def){try{return s==null?def:Double.parseDouble(s);}catch(Exception e){return def;}}
    private static long l(String s,long def){try{return s==null?def:Long.parseLong(s);}catch(Exception e){return def;}}
    private static String fmt(double d){return String.format(Locale.ROOT,"%.4f",d);}
    private static String enc(String s){return Base64.getUrlEncoder().withoutPadding().encodeToString((s==null?"":s).getBytes(StandardCharsets.UTF_8));}
    private static String dec(String s){return new String(Base64.getUrlDecoder().decode(s),StandardCharsets.UTF_8);}
    private static String decField(String s){try{return dec(s);}catch(Exception e){return s;}}

    @Override public void close(){scheduler.shutdownNow();try{flush();}catch(Exception ignored){}}
}
