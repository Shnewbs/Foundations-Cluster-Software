package com.foundations.cluster.manager;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 0.0.5a.R7 worker-to-worker data-plane relay with authoritative chunk read replication.
 *
 * The Cluster Manager remains the authenticated rendezvous/control point while
 * workers exchange bounded, fenced simulation frames. R7 keeps the manager relay while adding fenced CHUNK_REQUEST/CHUNK_SNAPSHOT frames so session anchors can materialize authoritative read replicas before direct worker-to-worker transport is introduced.
 */
final class DataPlaneManager implements AutoCloseable {
    record Summary(boolean enabled,long published,long delivered,long acked,long rejected,long dropped,int pending,int inflight,long bytes,long chunkRequests,long chunkSnapshots,long snapshotBytes) {}

    private final ManagerConfig cfg;
    private final WorkerManager workers;
    private final SessionManager sessions;
    private final RegionAuthorityManager regions;
    private final ClusterLog log;
    private final PersistentBlockJournal persistence;
    private final PersistentBlockEntityJournal blockEntityPersistence;
    private final String incarnation=UUID.randomUUID().toString();
    private final AtomicLong sequence=new AtomicLong();
    private final ConcurrentHashMap<String,ConcurrentLinkedDeque<DataPlaneFrame>> pending=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,ConcurrentHashMap<String,DataPlaneFrame>> inflight=new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<DataPlaneFrame> recent=new ConcurrentLinkedDeque<>();
    private final ScheduledExecutorService cleanup=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"foundations-dataplane-cleanup");t.setDaemon(true);return t;});
    private final AtomicLong published=new AtomicLong(),delivered=new AtomicLong(),acked=new AtomicLong(),rejected=new AtomicLong(),dropped=new AtomicLong(),bytes=new AtomicLong();
    private final AtomicLong chunkRequests=new AtomicLong(),chunkSnapshots=new AtomicLong(),snapshotBytes=new AtomicLong();
    private final AtomicLong blockEntityCommits=new AtomicLong();
    // Coalesce only notifications still queued, never writes after a notification was sent.
    private record InvalidationKey(String target, String world, String dimension, String region, long epoch, String payload) {}
    private final Set<InvalidationKey> queuedInvalidations = ConcurrentHashMap.newKeySet();

    DataPlaneManager(ManagerConfig cfg, WorkerManager workers, SessionManager sessions, RegionAuthorityManager regions, ClusterLog log) {
        this.cfg=cfg;this.workers=workers;this.sessions=sessions;this.regions=regions;this.log=log;
        try {
            this.persistence = new PersistentBlockJournal(cfg, log);
            this.blockEntityPersistence = new PersistentBlockEntityJournal(cfg, log);
        }
        catch (java.io.IOException e) { throw new IllegalStateException("Could not initialize persistence journals", e); }
        cleanup.scheduleAtFixedRate(this::cleanupSafe,1,1,TimeUnit.SECONDS);
        log.info("Simulation Data Plane initialized: enabled="+enabled()+" relay=manager maxQueue="+maxQueue()+" frameTtl="+ttlMillis()+"ms");
    }

    Map<String,byte[]> diagnosticSnapshots(int limit) {
        Map<String,byte[]> out=new LinkedHashMap<>();
        out.put("runtime/persistent-blocks.tsv",persistence.diagnosticSnapshot(limit));
        out.put("runtime/persistent-block-entities.tsv",blockEntityPersistence.diagnosticSnapshot(limit));
        return out;
    }

    boolean enabled(){return cfg.getBoolean("dataplane.enabled",true);}
    int maxQueue(){return Math.max(32,cfg.getInt("dataplane.maxQueuePerWorker",2048));}
    int maxPayloadBytes(){return Math.max(1024,cfg.getInt("dataplane.maxPayloadBytes",16777216));}
    int maxPollFrames(){return Math.max(1,Math.min(256,cfg.getInt("dataplane.maxFramesPerPoll",64)));}
    long ttlMillis(){return Math.max(1000,cfg.getLong("dataplane.frameTtlMillis",10000));}
    long retryMillis(){return Math.max(50,cfg.getLong("dataplane.retryMillis",500));}

    DataPlaneFrame publish(Map<String,String> m) {
        if(!enabled())throw reject("data plane is disabled");
        String kind=req(m,"kind").toUpperCase(Locale.ROOT);
        String source=req(m,"sourceWorkerId");
        String target=m.getOrDefault("targetWorkerId","").trim();
        String sessionId=m.getOrDefault("sessionId","").trim();
        String playerId=m.getOrDefault("playerId","").trim();
        String worldId=m.getOrDefault("worldId","foundations").trim();
        String dimensionId=m.getOrDefault("dimensionId","minecraft:overworld").trim();
        String regionId=m.getOrDefault("regionId","").trim();
        long epoch=Json.l(m,"authorityEpoch",0);
        String payload=m.getOrDefault("payload","");
        int payloadBytes=payload.getBytes(StandardCharsets.UTF_8).length;
        if(payloadBytes>maxPayloadBytes())throw reject("payload exceeds dataplane.maxPayloadBytes");
        WorkerRecord sw=workers.get(source);if(sw==null)throw reject("unknown source worker: "+source);

        // R7.R4/R7.R5 manager-resident durable mutation commits. These frames are
        // consumed by the manager rather than relayed to another worker.
        if(kind.equals("PERSIST_BLOCK_ENTITY")){
            synchronized (regions) {
            if(regionId.isBlank())throw reject("PERSIST_BLOCK_ENTITY requires regionId");
            RegionRecord r=regions.get(regionId);if(r==null)throw reject("unknown authority region: "+regionId);
            if(r.state!=RegionState.ACTIVE)throw reject("persistent BlockEntity commit requires ACTIVE authority region: "+r.state);
            if(!source.equals(r.ownerWorkerId))throw reject("persistent BlockEntity commit source does not own region");
            if(System.currentTimeMillis() >= r.leaseExpiresAtMillis)throw reject("authority lease expired");
            if(epoch!=r.epoch)throw reject("persistent BlockEntity commit uses stale authority epoch "+epoch+" current="+r.epoch);
            String[] pp=payload.split("\\|",-1);if(pp.length!=6 && pp.length!=7)throw reject("bad PERSIST_BLOCK_ENTITY payload");
            int x=Integer.parseInt(pp[0]),y=Integer.parseInt(pp[1]),z=Integer.parseInt(pp[2]);
            if(!pp[3].equals("0") && !pp[3].equals("1"))throw reject("invalid BlockEntity presence flag");
            boolean present="1".equals(pp[3]);
            RegionKey coordinateRegion=regions.forBlock(worldId,dimensionId,x,z);
            if(!coordinateRegion.id().equals(regionId))throw reject("persistent BlockEntity coordinate does not belong to claimed region: expected="+coordinateRegion.id()+" claimed="+regionId);
            String typeId=dec(pp[4]);
            // R11 host-block-v3 payload adds the encoded host block state between type and NBT.
            // Accept the legacy six-field payload so existing workers/journals remain readable during the update.
            String blockState=pp.length>=7?dec(pp[5]):"";
            String nbtPayload=pp.length>=7?pp[6]:pp[5];
            var committed=blockEntityPersistence.record(worldId,dimensionId,regionId,epoch,x,y,z,present,typeId,blockState,nbtPayload);
            String id=id();DataPlaneFrame f=new DataPlaneFrame(id,kind,source,source,sessionId,playerId,worldId,dimensionId,regionId,epoch,payload);
            published.incrementAndGet();acked.incrementAndGet();bytes.addAndGet(payloadBytes);recordRecent(f);
            long beCount=blockEntityCommits.incrementAndGet();
            // R10 could emit hundreds of INFO lines per minute for volatile beehives.  Keep
            // a small audit trail without turning every NBT tick into synchronous log I/O.
            if(beCount<=5 || beCount%1000==0) log.info("Persistent BlockEntity commits="+beCount+" latestRevision="+committed.revision()+" owner="+source+" pos="+x+","+y+","+z+" type="+typeId);
            fanoutChunkInvalidate(source,worldId,dimensionId,regionId,epoch,x,z);
            return f;
            }
        }

        if(kind.equals("PERSIST_BLOCK")){
            synchronized (regions) {
            if(regionId.isBlank())throw reject("PERSIST_BLOCK requires regionId");
            RegionRecord r=regions.get(regionId);if(r==null)throw reject("unknown authority region: "+regionId);
            if(r.state!=RegionState.ACTIVE)throw reject("persistent block commit requires ACTIVE authority region: "+r.state);
            if(!source.equals(r.ownerWorkerId))throw reject("persistent block commit source does not own region");
            if(System.currentTimeMillis() >= r.leaseExpiresAtMillis)throw reject("authority lease expired");
            if(epoch!=r.epoch)throw reject("persistent block commit uses stale authority epoch "+epoch+" current="+r.epoch);
            String[] pp=payload.split("\\|",-1);if(pp.length!=4)throw reject("bad PERSIST_BLOCK payload");
            int x=Integer.parseInt(pp[0]),y=Integer.parseInt(pp[1]),z=Integer.parseInt(pp[2]);
            RegionKey coordinateRegion=regions.forBlock(worldId,dimensionId,x,z);
            if(!coordinateRegion.id().equals(regionId))throw reject("persistent block coordinate does not belong to claimed region: expected="+coordinateRegion.id()+" claimed="+regionId);
            String blockState=dec(pp[3]);
            var committed=persistence.record(worldId,dimensionId,regionId,epoch,x,y,z,blockState);
            String id=id();DataPlaneFrame f=new DataPlaneFrame(id,kind,source,source,sessionId,playerId,worldId,dimensionId,regionId,epoch,payload);
            published.incrementAndGet();acked.incrementAndGet();bytes.addAndGet(payloadBytes);recordRecent(f);
            log.info("Persistent block commit region="+regionId+" owner="+source+" epoch="+epoch+" revision="+committed.revision()+" pos="+x+","+y+","+z);
            fanoutChunkInvalidate(source,worldId,dimensionId,regionId,epoch,x,z);
            return f;
            }
        }

        SessionRecord session=sessionId.isBlank()?null:sessions.get(sessionId);
        if(session==null&&!playerId.isBlank())session=sessions.findByPlayer(playerId);
        if(session!=null){
            sessionId=session.sessionId;playerId=session.playerId;
        }

        boolean request=kind.endsWith("_REQUEST")||kind.equals("INTERACTION_REQUEST")||kind.equals("BLOCK_REQUEST");
        boolean persistent=kind.equals("BLOCK_DELTA")||kind.equals("BLOCK_ENTITY_DELTA")||kind.equals("CONTAINER_DELTA")||kind.equals("AUTHORITY_COMMIT")||kind.equals("CHUNK_SNAPSHOT")||kind.equals("CHUNK_INVALIDATE");

        if(request){
            if(session==null)throw reject("request frame requires an active session");
            if(!source.equals(session.anchorWorkerId))throw reject("request source is not the session anchor");
            if(regionId.isBlank())regionId=session.regionId;
            RegionRecord r=regionId.isBlank()?null:regions.get(regionId);
            if(r==null)throw reject("request has no active authority region");
            if(r.state!=RegionState.ACTIVE)throw reject("authority region is not ACTIVE: "+r.state);
            if(epoch<=0)epoch=r.epoch;
            if(epoch!=r.epoch)throw reject("request uses stale authority epoch "+epoch+" current="+r.epoch);
            if(target.isBlank())target=r.ownerWorkerId;
            if(!target.equals(r.ownerWorkerId))throw reject("request target is not current region authority owner");
        } else if(persistent){
            if(regionId.isBlank())throw reject("persistent frame requires regionId");
            RegionRecord r=regions.get(regionId);if(r==null)throw reject("unknown authority region: "+regionId);
            if(r.state!=RegionState.ACTIVE)throw reject("authority region is not ACTIVE: "+r.state);
            if(!source.equals(r.ownerWorkerId))throw reject("persistent frame source does not own region");
            if(epoch!=r.epoch)throw reject("stale authority epoch "+epoch+" current="+r.epoch);
            if(target.isBlank()&&session!=null)target=session.anchorWorkerId;
        } else {
            if(target.isBlank()&&session!=null)target=session.anchorWorkerId;
        }

        if(target.isBlank())throw reject("targetWorkerId could not be resolved");
        WorkerRecord tw=workers.get(target);if(tw==null)throw reject("unknown target worker: "+target);
        if(source.equals(target)){ // local delivery is unnecessary; acknowledge as a no-op frame for observability
            String id=id();DataPlaneFrame f=new DataPlaneFrame(id,kind,source,target,sessionId,playerId,worldId,dimensionId,regionId,epoch,payload);
            published.incrementAndGet();acked.incrementAndGet();recordRecent(f);return f;
        }
        String id=id();DataPlaneFrame f=new DataPlaneFrame(id,kind,source,target,sessionId,playerId,worldId,dimensionId,regionId,epoch,payload);
        if (!enqueue(f)) return f;
        published.incrementAndGet();bytes.addAndGet(payloadBytes);
        if(kind.equals("CHUNK_REQUEST"))chunkRequests.incrementAndGet();
        if(kind.equals("CHUNK_SNAPSHOT")){chunkSnapshots.incrementAndGet();snapshotBytes.addAndGet(payloadBytes);}
        recordRecent(f);
        return f;
    }

    /** HF10: a durable authority mutation makes any active remote anchor replica stale immediately. */
    private void fanoutChunkInvalidate(String source,String worldId,String dimensionId,String regionId,long epoch,int blockX,int blockZ){
        int chunkX=Math.floorDiv(blockX,16),chunkZ=Math.floorDiv(blockZ,16);
        for(SessionRecord session:sessions.all()){
            if(!regionId.equals(session.regionId))continue;
            String target=session.anchorWorkerId;
            if(target==null||target.isBlank()||target.equals(source)||workers.get(target)==null)continue;
            String payload=chunkX+"|"+chunkZ;
            DataPlaneFrame invalidate=new DataPlaneFrame(id(),"CHUNK_INVALIDATE",source,target,session.sessionId,session.playerId,worldId,dimensionId,regionId,epoch,payload);
            if (enqueue(invalidate)) {
                published.incrementAndGet();bytes.addAndGet(payload.getBytes(StandardCharsets.UTF_8).length);recordRecent(invalidate);
            }
        }
    }

    private InvalidationKey invalidationKey(DataPlaneFrame f) {
        return new InvalidationKey(f.targetWorkerId, f.worldId, f.dimensionId, f.regionId, f.authorityEpoch, f.payload);
    }

    private void dequeued(DataPlaneFrame f) {
        if (f.kind.equals("CHUNK_INVALIDATE")) queuedInvalidations.remove(invalidationKey(f));
    }

    private boolean enqueue(DataPlaneFrame f) {
        var q=pending.computeIfAbsent(f.targetWorkerId,k->new ConcurrentLinkedDeque<>());
        synchronized (q) {
            long now=System.currentTimeMillis();
            q.removeIf(old -> {if(now-old.createdAtMillis>ttlMillis()){dequeued(old);dropped.incrementAndGet();return true;}return false;});
            if (f.kind.equals("CHUNK_INVALIDATE") && !queuedInvalidations.add(invalidationKey(f))) return false;
            while (q.size() >= maxQueue()) {
                DataPlaneFrame old=q.removeFirst();dequeued(old);dropped.incrementAndGet();
            }
            q.addLast(f);
            return true;
        }
    }

    String pollBundle(String workerId,int requestedMax){
        WorkerRecord w=workers.get(workerId);if(w==null)throw reject("unknown polling worker: "+workerId);
        int max=Math.max(1,Math.min(maxPollFrames(),requestedMax<=0?maxPollFrames():requestedMax));
        long now=System.currentTimeMillis();
        var q=pending.computeIfAbsent(workerId,k->new ConcurrentLinkedDeque<>());
        var in=inflight.computeIfAbsent(workerId,k->new ConcurrentHashMap<>());
        List<DataPlaneFrame> out=new ArrayList<>();

        synchronized (q) {
        // Bound unacknowledged frames as well as the pending queue.
        in.values().removeIf(f -> {if(now-f.createdAtMillis>ttlMillis()){dropped.incrementAndGet();return true;}return false;});
        // Retry timed-out inflight frames first.
        for(DataPlaneFrame f:in.values().stream().sorted(Comparator.comparingLong(a->a.createdAtMillis)).toList()){
            if(out.size()>=max)break;
            if(now-f.lastDeliveryAtMillis>=retryMillis()){markDelivery(f,now);out.add(f);}
        }
        while(out.size()<max && in.size()<maxQueue()){
            DataPlaneFrame f=q.pollFirst();if(f==null)break;
            dequeued(f);
            if(now-f.createdAtMillis>ttlMillis()){dropped.incrementAndGet();continue;}
            in.put(f.id,f);markDelivery(f,now);out.add(f);
        }
        }
        if(out.isEmpty())return "";
        StringBuilder b=new StringBuilder();
        for(DataPlaneFrame f:out){if(!b.isEmpty())b.append('\n');b.append(encode(f));}
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b.toString().getBytes(StandardCharsets.UTF_8));
    }

    void ack(String workerId,String frameId,boolean ok,String error){
        var in=inflight.get(workerId);DataPlaneFrame f=in==null?null:in.remove(frameId);
        if(f==null)return;
        if(ok)acked.incrementAndGet();else {rejected.incrementAndGet();log.warn("Data-plane frame "+frameId+" rejected by "+workerId+(error==null||error.isBlank()?"":" error="+error));}
    }

    String routeTokenForAnchor(String workerId){
        StringBuilder b=new StringBuilder();
        for(SessionRecord s:sessions.all().stream().filter(x->workerId.equals(x.anchorWorkerId)).sorted(Comparator.comparing(x->x.playerId)).toList()){
            RegionRecord current=s.regionId==null||s.regionId.isBlank()?null:regions.get(s.regionId);
            String authority=current==null?s.authorityWorkerId:current.ownerWorkerId;long epoch=current==null?s.authorityEpoch:current.epoch;
            if(current!=null){s.authorityWorkerId=authority;s.authorityEpoch=epoch;}
            if(!b.isEmpty())b.append(';');
            b.append(enc(s.sessionId)).append(',').append(enc(s.playerId)).append(',').append(enc(s.simulationWorkerId)).append(',')
                    .append(enc(authority)).append(',').append(enc(s.regionId)).append(',').append(epoch).append(',')
                    .append(enc(s.dimensionId));
        }
        return b.toString();
    }

    Summary summary(){
        int p=pending.values().stream().mapToInt(Deque::size).sum();int i=inflight.values().stream().mapToInt(Map::size).sum();
        return new Summary(enabled(),published.get(),delivered.get(),acked.get(),rejected.get(),dropped.get(),p,i,bytes.get(),chunkRequests.get(),chunkSnapshots.get(),snapshotBytes.get());
    }

    String persistenceBundleForWorker(String workerId){
        WorkerRecord w=workers.get(workerId);if(w==null)throw reject("unknown persistence replay worker: "+workerId);
        return persistence.bundleForRegions(regions.regionsForWorker(workerId));
    }
    String blockEntityPersistenceBundleForWorker(String workerId){
        WorkerRecord w=workers.get(workerId);if(w==null)throw reject("unknown BlockEntity persistence replay worker: "+workerId);
        return blockEntityPersistence.bundleForRegions(regions.regionsForWorker(workerId));
    }
    String persistenceBundleForRegions(Collection<RegionRecord> selected){return persistence.bundleForRegions(selected);}
    String blockEntityPersistenceBundleForRegions(Collection<RegionRecord> selected){return blockEntityPersistence.bundleForRegions(selected);}
    PersistentBlockJournal.Summary persistenceSummary(){return persistence.summary();}
    PersistentBlockEntityJournal.Summary blockEntityPersistenceSummary(){return blockEntityPersistence.summary();}

    List<DataPlaneFrame> recent(int limit){
        var list=new ArrayList<>(recent);Collections.reverse(list);return list.subList(0,Math.min(Math.max(0,limit),list.size()));
    }

    private void markDelivery(DataPlaneFrame f,long now){f.lastDeliveryAtMillis=now;f.deliveries++;delivered.incrementAndGet();}
    private void recordRecent(DataPlaneFrame f){recent.addLast(f);while(recent.size()>500)recent.pollFirst();}
    private String id(){return String.format(Locale.ROOT,"dp-%s-%010d",incarnation,sequence.incrementAndGet());}
    private IllegalArgumentException reject(String s){rejected.incrementAndGet();return new IllegalArgumentException(s);}
    private static String req(Map<String,String>m,String k){String v=m.getOrDefault(k,"").trim();if(v.isBlank())throw new IllegalArgumentException(k+" is required");return v;}
    private static String enc(String s){return Base64.getUrlEncoder().withoutPadding().encodeToString((s==null?"":s).getBytes(StandardCharsets.UTF_8));}
    private static String dec(String s){return new String(Base64.getUrlDecoder().decode(s),StandardCharsets.UTF_8);}
    private static String encode(DataPlaneFrame f){
        return String.join("\t",enc(f.id),enc(f.kind),enc(f.sourceWorkerId),enc(f.targetWorkerId),enc(f.sessionId),enc(f.playerId),enc(f.worldId),enc(f.dimensionId),enc(f.regionId),Long.toString(f.authorityEpoch),enc(f.payload));
    }
    private void cleanupSafe(){try{cleanup();}catch(Throwable t){log.warn("Data-plane cleanup failed: "+t.getMessage());}}
    private void cleanup(){long now=System.currentTimeMillis();for(var q:pending.values()){synchronized(q){q.removeIf(f->{boolean x=now-f.createdAtMillis>ttlMillis();if(x){dequeued(f);dropped.incrementAndGet();}return x;});}}for(var m:inflight.values())for(var e:new ArrayList<>(m.entrySet()))if(now-e.getValue().createdAtMillis>ttlMillis()){if(m.remove(e.getKey(),e.getValue()))dropped.incrementAndGet();}}
    @Override public void close(){cleanup.shutdownNow();pending.clear();inflight.clear();queuedInvalidations.clear();persistence.close();blockEntityPersistence.close();}
}
