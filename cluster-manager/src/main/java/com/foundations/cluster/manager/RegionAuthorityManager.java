package com.foundations.cluster.manager;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * 0.0.4a control-plane region authority registry.
 *
 * Regions are aligned to configurable square chunk groups (default 32x32 chunks,
 * matching Minecraft Anvil region-file geometry). Each region has one logical
 * owner, a renewable lease, and a monotonically increasing fencing epoch.
 *
 * IMPORTANT: 0.0.4a establishes and distributes authority metadata. The
 * vanilla/NeoForge chunk save path is not yet redirected to shared storage, so
 * write fencing is reported as control-plane-only until the data plane is wired.
 */
final class RegionAuthorityManager implements AutoCloseable {
    record Summary(int total, int active, int suspect, int recovering, int unassigned, int owners) {}

    private final ManagerConfig cfg;
    private final WorkerManager workers;
    private final ClusterLog log;
    private final ConcurrentHashMap<RegionKey, RegionRecord> regions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "foundations-region-authority"); t.setDaemon(true); return t;
    });
    private final Path stateFile;
    private final Set<RegionKey> startupRecoveryRegions = ConcurrentHashMap.newKeySet();
    private final long startupRecoveryUntilMillis;
    private volatile boolean dirty;
    private final Map<String, SaveBarrier> saveBarriers = new ConcurrentHashMap<>();
    private final Map<String, String> lastSaves = new ConcurrentHashMap<>();
    private record HydrationReport(String instance, Map<String,Long> epochs) {}
    private final Map<String,HydrationReport> hydrationReports = new ConcurrentHashMap<>();

    // R10 reports the region epochs actually restored on this worker process.
    // Legacy nodes retain their old protocol, but cannot pass R10 acceptance.
    private boolean requiresHydration(WorkerRecord w) {
        return w != null && w.nodeVersion.matches("0\\.0\\.6a\\.R(?:[1-9][0-9]+)");
    }
    private boolean hydrated(RegionRecord r, WorkerRecord w) {
        if (!requiresHydration(w)) return true;
        HydrationReport report = hydrationReports.get(w.id);
        return report != null && !w.persistenceInstance.isBlank() && report.instance().equals(w.persistenceInstance)
            && Objects.equals(report.epochs().get(r.key.id()), r.epoch);
    }
    synchronized void reportHydration(WorkerRecord w, String token) {
        Map<String,Long> epochs = parseHydrationToken(token);
        // A delayed heartbeat may name an old epoch. It never satisfies a new owner/epoch.
        epochs.entrySet().removeIf(e -> {RegionRecord r=get(e.getKey()); return r==null || !w.id.equals(r.ownerWorkerId) || r.epoch!=e.getValue();});
        hydrationReports.put(w.id,new HydrationReport(w.persistenceInstance,Map.copyOf(epochs)));
    }
    static Map<String,Long> parseHydrationToken(String token) {
        Map<String,Long> epochs=new HashMap<>();
        if(token!=null&&!token.isBlank())for(String row:token.split(";")) {
            String[] fields=row.split("\\|",-1);
            if(fields.length!=2)throw new IllegalArgumentException("Invalid region hydration token");
            String id=dec(fields[0]);long epoch=Long.parseLong(fields[1]);
            if(epoch<1||epochs.putIfAbsent(id,epoch)!=null)throw new IllegalArgumentException("Duplicate/invalid hydration epoch");
        }
        return epochs;
    }
    synchronized List<RegionRecord> replayRegions(String worker, String instance, String token) {
        WorkerRecord w=workers.get(worker);
        if(w==null||instance.isBlank()||!instance.equals(w.persistenceInstance))throw new IllegalStateException("Replay worker process changed");
        Map<String,Long> requested=parseHydrationToken(token);
        if(requested.isEmpty())throw new IllegalArgumentException("Replay must name pending region epochs");
        List<RegionRecord> result=new ArrayList<>();
        for(var entry:requested.entrySet()) {
            RegionRecord region=require(entry.getKey());
            if(!worker.equals(region.ownerWorkerId)||region.epoch!=entry.getValue())throw new IllegalStateException("Replay region owner/epoch changed: "+entry.getKey());
            result.add(region);
        }
        return result;
    }

    private static final class SaveBarrier {
        final String id=UUID.randomUUID().toString(), region, source, destination, instance;
        final long epoch, started=System.currentTimeMillis();
        final CompletableFuture<Void> saved=new CompletableFuture<>();
        SaveBarrier(RegionRecord r,String destination,String instance){this.instance=instance;region=r.key.id();source=r.ownerWorkerId;epoch=r.epoch;this.destination=destination;}
    }
    String checkpointCommands(String worker) {
        return saveBarriers.values().stream().filter(b->b.source.equals(worker))
            .map(b->b.id+"|"+enc(b.region)+"|"+b.epoch).collect(java.util.stream.Collectors.joining(";"));
    }
    synchronized void acknowledgeCheckpoint(String worker,String instance,String id,long epoch,String error) {
        SaveBarrier b=saveBarriers.values().stream().filter(x->x.id.equals(id)).findFirst().orElseThrow(()->new IllegalStateException("Checkpoint expired"));
        RegionRecord r=require(b.region);
        if(!b.instance.equals(instance)||!b.instance.equals(workers.get(worker).persistenceInstance)||!b.source.equals(worker)||b.epoch!=epoch||!r.ownerWorkerId.equals(worker)||r.epoch!=epoch)
            throw new IllegalStateException("Checkpoint owner/epoch mismatch");
        if(error==null||error.isBlank())b.saved.complete(null);else b.saved.completeExceptionally(new IllegalStateException(error));
    }
    synchronized void cancelCheckpoint(String region) {
        SaveBarrier b=saveBarriers.get(region);if(b!=null)b.saved.completeExceptionally(new IllegalStateException("Cancelled by administrator"));
    }
    String checkpointStatus(String region) {
        SaveBarrier b=saveBarriers.get(region);
        return b==null?lastSaves.getOrDefault(region,""):"Saving "+b.source+" → "+b.destination+" ("+((System.currentTimeMillis()-b.started)/1000)+"s)";
    }
    boolean checkpointPending(String region){return saveBarriers.containsKey(region);}


    RegionAuthorityManager(ManagerConfig cfg, WorkerManager workers, ClusterLog log) throws IOException {
        this.cfg = cfg; this.workers = workers; this.log = log;
        this.stateFile = cfg.home().resolve("runtime/region-authority.tsv");
        Files.createDirectories(stateFile.getParent());
        this.startupRecoveryUntilMillis = System.currentTimeMillis() + Math.max(0, cfg.getLong("region.startupRecoverySeconds", 90)) * 1000L;
        load();
        long tick = Math.max(1, cfg.getLong("region.checkSeconds", 2));
        scheduler.scheduleAtFixedRate(this::tickSafe, tick, tick, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::flushSafe, 5, 5, TimeUnit.SECONDS);
        log.info("Region Authority initialized: enabled=" + enabled() + " regionChunks=" + regionChunks()
                + " lease=" + leaseSeconds() + "s autoFailover=" + autoFailover() + " writeFencing=control-plane-only");
    }

    boolean enabled() { return cfg.getBoolean("region.enabled", true); }
    int regionChunks() { return Math.max(1, cfg.getInt("region.regionChunks", 32)); }
    long leaseSeconds() { return Math.max(5, cfg.getLong("region.leaseSeconds", 15)); }
    long leaseMillis() { return leaseSeconds() * 1000L; }
    long recoveryMillis() { return Math.max(0, cfg.getLong("region.recoverySeconds", 3)) * 1000L; }
    boolean autoFailover() { return cfg.getBoolean("region.autoFailover", true); }
    long startupRecoverySeconds() { return Math.max(0, cfg.getLong("region.startupRecoverySeconds", 90)); }
    boolean writeFencingEnforced() { return false; }

    Collection<RegionRecord> all() { return new ArrayList<>(regions.values()); }
    RegionRecord get(String id) { for (RegionRecord r : regions.values()) if (r.key.id().equals(id)) return r; return null; }

    RegionKey forCell(LayerCellKey cell, int cellChunks) {
        int firstChunkX = cell.cellX() * cellChunks;
        int firstChunkZ = cell.cellZ() * cellChunks;
        return new RegionKey(cell.worldId(), cell.dimensionId(), Math.floorDiv(firstChunkX, regionChunks()), Math.floorDiv(firstChunkZ, regionChunks()));
    }

    RegionKey forBlock(String worldId, String dimensionId, double x, double z) {
        int bx = (int)Math.floor(x), bz = (int)Math.floor(z);
        int chunkX = Math.floorDiv(bx, 16), chunkZ = Math.floorDiv(bz, 16);
        return new RegionKey(worldId, dimensionId, Math.floorDiv(chunkX, regionChunks()), Math.floorDiv(chunkZ, regionChunks()));
    }

    RegionRecord ensureForCell(LayerCellKey cell, int cellChunks, String preferredWorkerId) {
        return ensure(forCell(cell, cellChunks), preferredWorkerId, "session/layer activity");
    }

    synchronized RegionRecord ensure(RegionKey key, String preferredWorkerId, String reason) {
        RegionRecord existing = regions.get(key);
        if (existing != null) {
            if ((existing.ownerWorkerId == null || existing.ownerWorkerId.isBlank() || existing.state == RegionState.UNASSIGNED) && enabled()) {
                WorkerRecord w = chooseOwner(preferredWorkerId, null);
                if (w != null) assign(existing, w.id, true, "assigned: " + reason);
            }
            return existing;
        }
        WorkerRecord owner = enabled() ? chooseOwner(preferredWorkerId, null) : null;
        long now = System.currentTimeMillis();
        RegionRecord created = new RegionRecord(key, owner == null ? "" : owner.id,
                owner == null ? "" : UUID.randomUUID().toString(), 1,
                owner == null ? 0 : now + leaseMillis(), owner == null ? RegionState.UNASSIGNED : requiresHydration(owner) ? RegionState.RECOVERING : RegionState.ACTIVE);
        created.note = reason == null ? "" : reason;
        regions.put(key, created); dirty = true;
        log.info("Region authority created " + key.id() + " owner=" + (owner == null ? "UNASSIGNED" : owner.id)
                + " epoch=" + created.epoch + " reason=" + created.note);
        return created;
    }

    RegionRecord transfer(String regionId, String targetWorkerId, String reason) {
        SaveBarrier barrier;
        synchronized(this) {
            RegionRecord r=require(regionId);WorkerRecord target=workers.get(targetWorkerId),source=workers.get(r.ownerWorkerId);
            if(target==null||!target.usableForExistingSession())throw new IllegalStateException("Target worker is not healthy: "+targetWorkerId);
            if(targetWorkerId.equals(r.ownerWorkerId)&&r.state==RegionState.ACTIVE)return r;
            if(r.state!=RegionState.ACTIVE||source==null||!source.usableForExistingSession())throw new IllegalStateException("Source must be active for a planned transfer; use recovery for an unavailable owner");
            if(!source.nodeVersion.matches("0\\.0\\.6a\\.R(?:9|[1-9][0-9]+)"))throw new IllegalStateException("Source needs Node R9 or newer for confirmed saves");
            barrier=new SaveBarrier(r,targetWorkerId,source.persistenceInstance);
            if(saveBarriers.putIfAbsent(regionId,barrier)!=null)throw new IllegalStateException("A region save is already pending");
            log.info("Region save requested "+regionId+" source="+barrier.source+" epoch="+barrier.epoch+" checkpoint="+barrier.id);
        }
        try {
            barrier.saved.get(25,TimeUnit.SECONDS);
            synchronized(this) {
                RegionRecord r=require(regionId);WorkerRecord target=workers.get(targetWorkerId);
                if(cfg.lifecycle.shuttingDown()||r.state!=RegionState.ACTIVE||!barrier.instance.equals(workers.get(barrier.source).persistenceInstance)||target==null||!target.usableForExistingSession()||r.epoch!=barrier.epoch||!r.ownerWorkerId.equals(barrier.source))
                    throw new IllegalStateException("Topology changed while saving; transfer cancelled");
                assign(r,targetWorkerId,true,reason==null?"confirmed save transfer":reason);
                lastSaves.put(regionId,"Saved and transferred from "+barrier.source+" at "+java.time.Instant.now());
                log.info("Region save confirmed; authority transferred "+regionId+" "+barrier.source+" -> "+targetWorkerId+" epoch="+r.epoch);
                return r;
            }
        } catch(Exception ex) {
            if(ex instanceof InterruptedException)Thread.currentThread().interrupt();
            String error=ex instanceof TimeoutException?"Save acknowledgement timed out; source ownership retained":String.valueOf(ex.getCause()==null?ex.getMessage():ex.getCause().getMessage());
            lastSaves.put(regionId,"Save blocked: "+error);log.warn("Region transfer blocked "+regionId+": "+error);
            throw new IllegalStateException(error,ex);
        } finally {saveBarriers.remove(regionId,barrier);}
    }

    synchronized void freeze(String regionId, boolean value) {
        RegionRecord r = require(regionId);
        r.state = value ? RegionState.FROZEN : hydrated(r,workers.get(r.ownerWorkerId)) ? RegionState.ACTIVE : RegionState.RECOVERING;
        r.stateChangedAtMillis = System.currentTimeMillis();
        if (!value && !r.ownerWorkerId.isBlank()) r.leaseExpiresAtMillis = System.currentTimeMillis() + leaseMillis();
        r.note = value ? "manual freeze" : "manual unfreeze";
        dirty = true;
        log.info("Region " + r.key.id() + " -> " + r.state);
    }

    synchronized void forceExpire(String regionId) {
        RegionRecord r = require(regionId);
        r.leaseExpiresAtMillis = 0;
        r.state = RegionState.EXPIRED;
        r.stateChangedAtMillis = System.currentTimeMillis();
        r.note = "manual lease expiry";
        dirty = true;
        log.warn("Region lease forcibly expired: " + r.key.id() + " epoch=" + r.epoch);
    }


    int activeRegionCountForWorker(String workerId) {
        int n=0; for(RegionRecord r:regions.values()) if(workerId!=null&&workerId.equals(r.ownerWorkerId)&&r.state!=RegionState.UNASSIGNED) n++; return n;
    }

    int evacuateWorker(String workerId, String reason) {
        int moved=0;
        for(RegionRecord r:new ArrayList<>(regions.values())) {
            if(!workerId.equals(r.ownerWorkerId)) continue;
            WorkerRecord replacement=chooseOwner(null,workerId);
            if(replacement==null) throw new IllegalStateException("Cannot evacuate "+workerId+": region "+r.key.id()+" has no other healthy authority worker available.");
            transfer(r.key.id(),replacement.id,reason==null?"worker evacuation":reason); moved++;
            log.warn("Region evacuated "+r.key.id()+" "+workerId+" -> "+replacement.id+" epoch="+r.epoch);
        }
        return moved;
    }

    synchronized int forceReleaseWorker(String workerId, String reason) {
        int changed=0; long now=System.currentTimeMillis();
        for(RegionRecord r:new ArrayList<>(regions.values())) {
            if(!workerId.equals(r.ownerWorkerId)) continue;
            WorkerRecord replacement=chooseOwner(null,workerId);
            if(replacement!=null) assign(r,replacement.id,true,reason==null?"forced worker release":reason);
            else {
                r.previousOwnerWorkerId=r.ownerWorkerId; r.ownerWorkerId=""; r.leaseId=""; r.epoch=Math.max(1,r.epoch+1); r.leaseExpiresAtMillis=0;
                r.state=RegionState.UNASSIGNED; r.stateChangedAtMillis=now; r.note=reason==null?"forced worker release":reason; dirty=true;
            }
            changed++;
        }
        return changed;
    }

    List<RegionRecord> regionsForWorker(String workerId) {
        return regions.values().stream().filter(r -> workerId != null && workerId.equals(r.ownerWorkerId) && r.state != RegionState.UNASSIGNED)
                .sorted(Comparator.comparing(a -> a.key.id())).toList();
    }

    String authorityTokenForWorker(String workerId) {
        StringBuilder b = new StringBuilder(); boolean first = true;
        for (RegionRecord r : regionsForWorker(workerId)) {
            if (!first) b.append(';'); first = false;
            b.append(enc(r.key.worldId())).append(',').append(enc(r.key.dimensionId())).append(',')
                    .append(r.key.regionX()).append(',').append(r.key.regionZ()).append(',')
                    .append(r.epoch).append(',').append(enc(r.leaseId)).append(',').append(r.leaseExpiresAtMillis).append(',').append(r.state.name());
        }
        return b.toString();
    }

    Summary summary() {
        int active=0,suspect=0,recovering=0,unassigned=0; Set<String> owners=new HashSet<>();
        for (RegionRecord r:regions.values()) {
            if (!r.ownerWorkerId.isBlank()) owners.add(r.ownerWorkerId);
            switch (r.state) {
                case ACTIVE -> active++;
                case SUSPECT, EXPIRED -> suspect++;
                case RECOVERING, TRANSFERRING -> recovering++;
                case UNASSIGNED -> unassigned++;
                default -> {}
            }
        }
        return new Summary(regions.size(),active,suspect,recovering,unassigned,owners.size());
    }

    private void tickSafe() { try { tick(); } catch (Throwable t) { log.warn("Region Authority tick failed: " + t.getMessage()); } }

    private synchronized void tick() {
        if (cfg.lifecycle.shuttingDown()) return;

        if (!enabled()) return;
        long now = System.currentTimeMillis();
        for (RegionRecord r : regions.values()) {
            if (r.state == RegionState.FROZEN || r.state == RegionState.UNASSIGNED) continue;
            WorkerRecord owner = workers.get(r.ownerWorkerId);
            boolean healthy = ownerHealthy(owner);
            if (startupRecoveryRegions.contains(r.key)) {
                if (healthy && hydrated(r,owner)) {
                    startupRecoveryRegions.remove(r.key);
                    r.lastHealthyAtMillis = now;
                    r.state = RegionState.ACTIVE;
                    r.stateChangedAtMillis = now;
                    r.leaseExpiresAtMillis = now + leaseMillis();
                    r.note = "persisted owner restored after manager restart";
                    dirty = true;
                    log.info("Region authority startup restore ACTIVE " + r.key.id() + " owner=" + r.ownerWorkerId + " epoch=" + r.epoch);
                    continue;
                }
                if (healthy || now < startupRecoveryUntilMillis) {
                    if (r.state != RegionState.RECOVERING || !"waiting for persisted owner startup".equals(r.note)) {
                        r.state = RegionState.RECOVERING;
                        r.stateChangedAtMillis = now;
                        r.note = "waiting for persisted owner startup";
                        dirty = true;
                        log.info("Region authority startup recovery waiting " + r.key.id() + " owner=" + r.ownerWorkerId + " graceRemainingMs=" + (startupRecoveryUntilMillis-now));
                    }
                    r.leaseExpiresAtMillis = Math.max(r.leaseExpiresAtMillis, startupRecoveryUntilMillis);
                    continue;
                }
                startupRecoveryRegions.remove(r.key);
                r.leaseExpiresAtMillis = 0;
                r.state = RegionState.EXPIRED;
                r.note = "persisted owner did not return during startup recovery grace";
                dirty = true;
                log.warn("Region authority startup recovery expired " + r.key.id() + " oldOwner=" + r.ownerWorkerId + " epoch=" + r.epoch);
                healthy = false;
            }
            if (healthy) {
                r.lastHealthyAtMillis = now;
                if (!hydrated(r,owner)) {
                    r.state=RegionState.RECOVERING; r.note="waiting for destination block/BlockEntity hydration acknowledgement";
                    r.leaseExpiresAtMillis=now+leaseMillis(); dirty=true; continue;
                }
                if (r.state == RegionState.RECOVERING && now < r.recoveryReadyAtMillis) continue;
                if (r.state != RegionState.ACTIVE) {
                    r.state = RegionState.ACTIVE; r.stateChangedAtMillis = now;
                    log.info("Region authority ACTIVE " + r.key.id() + " owner=" + r.ownerWorkerId + " epoch=" + r.epoch);
                }
                r.leaseExpiresAtMillis = now + leaseMillis();
                dirty = true;
                continue;
            }
            if (now < r.leaseExpiresAtMillis) {
                if (r.state != RegionState.SUSPECT) {
                    r.state = RegionState.SUSPECT; r.stateChangedAtMillis = now; r.note = "owner heartbeat/health lost"; dirty = true;
                    log.warn("Region authority SUSPECT " + r.key.id() + " owner=" + r.ownerWorkerId + " leaseRemainingMs=" + (r.leaseExpiresAtMillis-now));
                }
                continue;
            }
            if (r.state != RegionState.EXPIRED) {
                r.state = RegionState.EXPIRED; r.stateChangedAtMillis = now; r.note = "lease expired"; dirty = true;
                log.warn("Region authority EXPIRED " + r.key.id() + " oldOwner=" + r.ownerWorkerId + " epoch=" + r.epoch);
            }
            if (autoFailover()) {
                WorkerRecord replacement = chooseOwner(null, r.ownerWorkerId);
                if (replacement != null) {
                    assign(r, replacement.id, true, "automatic failover after lease expiry");
                    log.warn("Region failover " + r.key.id() + " -> " + replacement.id + " epoch=" + r.epoch);
                }
            }
        }
    }

    private boolean ownerHealthy(WorkerRecord w) {
        if (w == null || !w.usableForExistingSession()) return false;
        long age = w.heartbeatAgeSeconds();
        long timeout = Math.max(3, cfg.getLong("worker.heartbeatTimeoutSeconds", 10));
        return age < 0 || age <= timeout;
    }

    private WorkerRecord chooseOwner(String preferredWorkerId, String excludeWorkerId) {
        if (preferredWorkerId != null && !preferredWorkerId.isBlank() && !preferredWorkerId.equals(excludeWorkerId)) {
            WorkerRecord w = workers.get(preferredWorkerId);
            if (ownerHealthy(w)) return w;
        }
        return workers.all().stream()
                .filter(w -> !w.id.equals(excludeWorkerId))
                .filter(this::ownerHealthy)
                .min(Comparator.comparingInt((WorkerRecord w) -> regionsForWorker(w.id).size())
                        .thenComparingDouble(w -> w.mspt <= 0 ? 0 : w.mspt)
                        .thenComparingInt(w -> w.players)
                        .thenComparing(w -> w.id))
                .orElse(null);
    }

    private void assign(RegionRecord r, String workerId, boolean incrementEpoch, String reason) {
        long now = System.currentTimeMillis();
        if (incrementEpoch) r.epoch = Math.max(1, r.epoch + 1);
        r.previousOwnerWorkerId = r.ownerWorkerId == null ? "" : r.ownerWorkerId;
        r.ownerWorkerId = workerId;
        r.leaseId = UUID.randomUUID().toString();
        r.leaseExpiresAtMillis = now + leaseMillis();
        r.recoveryReadyAtMillis = now + recoveryMillis();
        r.state = recoveryMillis() > 0 || requiresHydration(workers.get(workerId)) ? RegionState.RECOVERING : RegionState.ACTIVE;
        r.stateChangedAtMillis = now;
        r.note = reason == null ? "" : reason;
        dirty = true;
    }

    private RegionRecord require(String id) {
        RegionRecord r = get(id);
        if (r == null) throw new IllegalArgumentException("Unknown region: " + id);
        return r;
    }

    private void load() throws IOException {
        if (!Files.isRegularFile(stateFile)) return;
        for (String line : Files.readAllLines(stateFile, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            try {
                String[] p = line.split("\\t", -1);
                if (p.length < 11) continue;
                RegionKey key = new RegionKey(dec(p[0]), dec(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
                RegionRecord r = new RegionRecord(key, dec(p[4]), dec(p[5]), Long.parseLong(p[6]), Long.parseLong(p[7]), RegionState.valueOf(p[8]));
                r.previousOwnerWorkerId = dec(p[9]); r.note = dec(p[10]);
                regions.put(key, r);
                if (!r.ownerWorkerId.isBlank() && r.state != RegionState.UNASSIGNED && r.state != RegionState.FROZEN) {
                    startupRecoveryRegions.add(key);
                    r.state = RegionState.RECOVERING;
                    r.recoveryReadyAtMillis = 0;
                    r.leaseExpiresAtMillis = Math.max(r.leaseExpiresAtMillis, startupRecoveryUntilMillis);
                    r.note = "waiting for persisted owner startup";
                }
            } catch (Exception ignored) {}
        }
        if (!regions.isEmpty()) log.info("Restored " + regions.size() + " Region Authority record(s) from runtime state; startup owner recovery grace=" + startupRecoverySeconds() + "s");
    }

    private synchronized void flushSafe() { if (!dirty) return; try { flush(); } catch (Exception e) { log.warn("Could not persist Region Authority state: " + e.getMessage()); } }
    private synchronized void flush() throws IOException {
        Path tmp = stateFile.resolveSibling(stateFile.getFileName()+".tmp");
        StringBuilder b = new StringBuilder("# Foundations Region Authority 0.0.4a\n");
        for (RegionRecord r : regions.values().stream().sorted(Comparator.comparing(a -> a.key.id())).toList()) {
            b.append(enc(r.key.worldId())).append('\t').append(enc(r.key.dimensionId())).append('\t').append(r.key.regionX()).append('\t').append(r.key.regionZ()).append('\t')
                    .append(enc(r.ownerWorkerId)).append('\t').append(enc(r.leaseId)).append('\t').append(r.epoch).append('\t').append(r.leaseExpiresAtMillis).append('\t')
                    .append(r.state.name()).append('\t').append(enc(r.previousOwnerWorkerId)).append('\t').append(enc(r.note)).append('\n');
        }
        Files.writeString(tmp,b.toString(),StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);
        try { Files.move(tmp,stateFile,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(tmp,stateFile,StandardCopyOption.REPLACE_EXISTING); }
        dirty=false;
    }

    private static String enc(String s) { return Base64.getUrlEncoder().withoutPadding().encodeToString((s==null?"":s).getBytes(StandardCharsets.UTF_8)); }
    private static String dec(String s) { return new String(Base64.getUrlDecoder().decode(s),StandardCharsets.UTF_8); }

    @Override public void close() { scheduler.shutdownNow(); try { flush(); } catch (Exception ignored) {} }
}
