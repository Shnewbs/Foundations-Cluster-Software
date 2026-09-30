package com.foundations.cluster.node;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

@Mod(FoundationsClusterNode.MOD_ID)
public final class FoundationsClusterNode {
    public static final String MOD_ID = "foundations_cluster_node";
    public static final String VERSION = "0.0.6a.R13";
    public static final int WORLD_IDENTITY_SCHEMA = 2;
    private static volatile FoundationsClusterNode activeNode;
    private static final Logger LOG = LoggerFactory.getLogger("Foundations Cluster Node");

    private final String manager = env("FOUNDATIONS_CLUSTER_MANAGER_URL", "http://127.0.0.1:8765");
    private final String token = env("FOUNDATIONS_CLUSTER_AGENT_TOKEN", "");
    private final String workerId = env("FOUNDATIONS_CLUSTER_WORKER_ID", "standalone");
    private final WorldEnvironmentSync worldEnvironment = new WorldEnvironmentSync(workerId,LOG);
    private final int port = parseInt(env("FOUNDATIONS_CLUSTER_WORKER_PORT", "25565"), 25565);
    private final boolean warm = Boolean.parseBoolean(env("FOUNDATIONS_CLUSTER_WARM", "false"));
    private final long expectedWorldSeed = parseLong(env("FOUNDATIONS_EXPECTED_WORLD_SEED", "0"), 0L);
    private final boolean expectedWorldSeedKnown = Boolean.parseBoolean(env("FOUNDATIONS_EXPECTED_WORLD_SEED_KNOWN", "false"));
    private final String expectedWorldIdentity = env("FOUNDATIONS_EXPECTED_WORLD_IDENTITY", env("FOUNDATIONS_WORLD_IDENTITY", ""));
    private final int expectedWorldIdentitySchema = Math.max(1, parseInt(env("FOUNDATIONS_EXPECTED_WORLD_IDENTITY_SCHEMA", "1"), 1));
    private final String worldIdentityKeywords = env("FOUNDATIONS_WORLD_IDENTITY_KEYWORDS", "world,worldgen,terrain,biome,generation,climate,tfc");
    private final boolean remoteBlockPrototype = Boolean.parseBoolean(env("FOUNDATIONS_REMOTE_BLOCK_PROTOTYPE", "true"));
    private final boolean chunkReplicationEnabled = Boolean.parseBoolean(env("FOUNDATIONS_CHUNK_REPLICATION_ENABLED", "true"));
    private final int chunkSnapshotRadius = Math.max(0, parseInt(env("FOUNDATIONS_CHUNK_SNAPSHOT_RADIUS", "1"), 1));
    private final int chunkVerticalRadius = Math.max(16, parseInt(env("FOUNDATIONS_CHUNK_VERTICAL_RADIUS", "96"), 96));
    private final long chunkRequestIntervalMillis = Math.max(100, parseLong(env("FOUNDATIONS_CHUNK_REQUEST_INTERVAL_MILLIS", "1000"), 1000));
    private final long chunkCacheTtlMillis = Math.max(500, parseLong(env("FOUNDATIONS_CHUNK_CACHE_TTL_MILLIS", "5000"), 5000));
    private final int maxChunkRequestsPerCycle = Math.max(1, parseInt(env("FOUNDATIONS_MAX_CHUNK_REQUESTS_PER_CYCLE", "2"), 2));
    // Bounded, non-persistent tickets for chunks currently viewed through a remote anchor.
    // A loaded chunk alone does NOT receive random ticks without a nearby player in 1.21.1.
    private final boolean authorityRandomTicks = Boolean.parseBoolean(env("FOUNDATIONS_AUTHORITY_RANDOM_TICKS", "true"));
    private final long remoteTickTtlMillis = Math.max(5000L, Math.min(60000L, parseLong(env("FOUNDATIONS_REMOTE_TICK_TTL_MILLIS", "20000"), 20000L)));
    private final int remoteTickLimit = Math.max(1, Math.min(1024, parseInt(env("FOUNDATIONS_REMOTE_TICK_LIMIT", "128"), 128)));
    private static final long GROWTH_CONTROL_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(8);
    private static final TicketType<ChunkPos> REMOTE_GROWTH_TICKET = TicketType.create("foundations_remote_growth", Comparator.comparingLong(ChunkPos::toLong));
    private final RemoteTickLeases remoteTickLeases = new RemoteTickLeases(remoteTickLimit, TimeUnit.MILLISECONDS.toNanos(remoteTickTtlMillis));
    private final Map<String,Long> naturalChunkTicks = new HashMap<>(), capturedChunkMutations = new HashMap<>();
    private long lastGrowthControlNanos, allowedGrowthTicks, suppressedGrowthTicks, lastGrowthCapacityLog;
    private final int authorityRegionChunks = Math.max(1, parseInt(env("FOUNDATIONS_REGION_CHUNKS", "32"), 32));
    private final boolean persistenceEnabled = Boolean.parseBoolean(env("FOUNDATIONS_PERSISTENCE_ENABLED", "true"));
    private final long persistenceCheckpointMillis = Math.max(5000L, parseLong(env("FOUNDATIONS_PERSISTENCE_CHECKPOINT_SECONDS", "30"), 30L) * 1000L);
    private final boolean blockEntityPersistenceEnabled = Boolean.parseBoolean(env("FOUNDATIONS_BLOCK_ENTITY_PERSISTENCE_ENABLED", "true"));
    private final int blockEntityScanIntervalTicks = Math.max(1, parseInt(env("FOUNDATIONS_BLOCK_ENTITY_SCAN_INTERVAL_TICKS", "40"), 40));
    private final int blockEntityScanBudget = Math.max(32, parseInt(env("FOUNDATIONS_BLOCK_ENTITY_SCAN_BUDGET", "256"), 256));
    private final int blockEntityMaxPayloadBytes = Math.max(65536, parseInt(env("FOUNDATIONS_BLOCK_ENTITY_MAX_PAYLOAD_BYTES", "8388608"), 8388608));
    private final long blockEntityCoalesceMillis = Math.max(250L, parseLong(env("FOUNDATIONS_BLOCK_ENTITY_COALESCE_MILLIS", "5000"), 5000L));
    private final long blockEntityCoalesceMaxMillis = Math.max(blockEntityCoalesceMillis, parseLong(env("FOUNDATIONS_BLOCK_ENTITY_COALESCE_MAX_MILLIS", "300000"), 300000L));

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "foundations-cluster-heartbeat"); t.setDaemon(true); return t;
    });
    private final ScheduledExecutorService dataPlanePoller = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "foundations-cluster-dataplane"); t.setDaemon(true); return t;
    });
    private final AtomicBoolean dataPlanePollInFlight = new AtomicBoolean(false);
    private final AtomicLong tickStart = new AtomicLong();
    private final double[] tickSamples = new double[256];
    private int tickSampleIndex;
    private int tickSampleCount;
    private long tickCounter;

    private volatile double emaMspt;
    private volatile double p95Mspt;
    private volatile double p99Mspt;
    private volatile MinecraftServer server;
    private volatile String actualWorldIdentity = "";
    private volatile long lastPlayerDataSaveAllMillis;
    private volatile boolean draining;
    private volatile boolean maintenance;
    private volatile String assignedLayerIds = "";
    private volatile String assignedAuthorityRegions = "";
    private volatile String assignedAuthorityFingerprint = "";
    private volatile boolean writeFencingEnforced;
    private final AtomicLong persistentMutationCount = new AtomicLong();
    private volatile long lastCheckpointMutationCount;
    private volatile long lastPersistenceCheckpointMillis;
    private final String persistenceInstance=UUID.randomUUID().toString();
    private final AtomicLong pendingWriteCount=new AtomicLong(),failedWriteCount=new AtomicLong();
    private volatile long lastDurableWrite;
    private volatile boolean replayingMutations;
    private final Map<String,String> changedBlocks=new ConcurrentHashMap<>();
    private final Map<String,Long> sealedRegions=new ConcurrentHashMap<>();
    private final Set<String> checkpointRequests=ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService persistenceRetries=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"foundations-persistence-retry");t.setDaemon(true);return t;});
    private final Object persistencePostLock = new Object();
    private CompletableFuture<Void> persistenceTail = CompletableFuture.completedFuture(null);
    private final ConcurrentHashMap<String,Long> handoffCommandThrottle = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,SimulationRoute> simulationRoutes = new ConcurrentHashMap<>();
    private volatile String simulationRouteToken = "";
    private final ConcurrentHashMap<String,Long> replicaChunkRequested = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,Long> replicaChunkApplied = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,Long> replicaChunkEpoch = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,String> replicaChunkFingerprint = new ConcurrentHashMap<>();
    private final AtomicLong dataplaneFenceRejects = new AtomicLong();
    private volatile long lastDataplaneFenceLogMillis;
    private volatile long lastChunkRequestSweepMillis;
    private final ConcurrentHashMap<String,ConcurrentHashMap<Long,Boolean>> trackedChunks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,String> blockEntityFingerprints = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,Set<String>> blockEntityKeysByChunk = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,Long> remoteBlockEntityPending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,PendingBlockEntityApply> pendingBlockEntityApplies = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,PendingPersistentBlockEntity> pendingPersistentBlockEntities = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,Long> blockEntityLastPersistedAt = new ConcurrentHashMap<>();
    private final AtomicLong blockEntityScanCursor = new AtomicLong();
    private final AuthorityHydration hydration = new AuthorityHydration();
    private final AtomicBoolean replayInFlight = new AtomicBoolean(false);
    private volatile long lastPersistenceReplayRequestMillis;
    private final AtomicLong controlRequestSequence = new AtomicLong();
    private long lastControlSequence;
    private volatile Map<String,OwnedAuthority> ownedAuthorities = Map.of();

    private record OwnedAuthority(String worldId,String dimensionId,int regionX,int regionZ,long epoch,long leaseExpiresAtMillis,String state) {
        String regionId(){ return worldId+"/"+dimensionId+"/r."+regionX+"."+regionZ; }
        boolean active(){ return "ACTIVE".equals(state); }
    }
    private record SimulationRoute(String sessionId,String playerId,String simulationWorkerId,String authorityWorkerId,String regionId,long authorityEpoch,String dimensionId) {}
    private record DataFrame(String id,String kind,String sourceWorkerId,String targetWorkerId,String sessionId,String playerId,String worldId,String dimensionId,String regionId,long authorityEpoch,String payload) {}
    private record BlockEntityPayload(BlockPos pos,boolean present,String typeId,String blockState,String nbtPayload) {}
    private record PendingBlockEntityApply(String dimensionId,BlockEntityPayload payload,long queuedAtMillis,int attempts) {}
    private record PendingPersistentBlockEntity(String sessionId,String playerId,String worldId,String dimensionId,String regionId,long authorityEpoch,
                                                BlockEntityPayload payload,long firstQueuedAtMillis,long lastChangedAtMillis) {}
    private record TrackedChunk(String dimensionId,long packed) {}

    private volatile int players;
    private volatile int maxPlayers;
    private volatile int loadedChunks;
    private volatile int dimensions;
    private volatile String playerStateBlob = "";

    public FoundationsClusterNode(IEventBus modEventBus, ModContainer modContainer) {
        activeNode=this;
        NeoForge.EVENT_BUS.addListener(this::serverStarted);
        NeoForge.EVENT_BUS.addListener(PersistenceProbe::register);
        NeoForge.EVENT_BUS.addListener(this::serverStopping);
        NeoForge.EVENT_BUS.addListener(this::tickPre);
        NeoForge.EVENT_BUS.addListener(this::tickPost);
        NeoForge.EVENT_BUS.addListener(this::playerLoggedIn);
        NeoForge.EVENT_BUS.addListener(this::playerLoggedOut);
        NeoForge.EVENT_BUS.addListener(this::blockBreak);
        NeoForge.EVENT_BUS.addListener(this::blockPlace);
        NeoForge.EVENT_BUS.addListener(this::chunkLoad);
        NeoForge.EVENT_BUS.addListener(this::chunkUnload);
        LOG.info("Foundations Cluster Node {} loaded; workerId={}", VERSION, workerId);
    }

    private void serverStarted(ServerStartedEvent e) {
        server = e.getServer();
        actualWorldIdentity = calculateWorldIdentity();
        snapshotServerMetrics();
        if (token.isBlank()) {
            LOG.warn("FOUNDATIONS_CLUSTER_AGENT_TOKEN is missing; node will run standalone and will not register with Cluster Manager.");
            return;
        }
        post("/api/agent/register", registerBody());
        heartbeats.scheduleWithFixedDelay(this::heartbeat, 0, 2, TimeUnit.SECONDS);
        long pollMillis=Math.max(20,parseInt(env("FOUNDATIONS_DATAPLANE_POLL_MILLIS","50"),50));
        dataPlanePoller.scheduleWithFixedDelay(this::pollDataPlaneSafe, pollMillis, pollMillis, TimeUnit.MILLISECONDS);
    }

    private void serverStopping(ServerStoppingEvent e) {
        snapshotServerMetrics();
        releaseAllRemoteGrowthTickets();
        if (persistenceEnabled) {
            flushCapturedBlocks();
            if (blockEntityPersistenceEnabled) {
                // Capture a final authoritative image and synchronously drain the
                // coalescer before Minecraft closes its world files.
                scanAllBlockEntities(true);
                flushPendingBlockEntityWrites(true);
            }
            awaitPersistencePosts(10000L);
            forcePersistenceCheckpoint("server stopping");
        }
        if (!token.isBlank()) {
            for (ServerPlayer p : e.getServer().getPlayerList().getPlayers()) { flushPlayerProgress(p); post("/api/agent/player-state", playerStateBody(p)); }
            post("/api/agent/offline", "{\"workerId\":\"" + esc(workerId) + "\"}");
        }
        heartbeats.shutdownNow();
        persistenceRetries.shutdownNow();activeNode=null;
        dataPlanePoller.shutdownNow();
        server = null;
    }

    private void playerLoggedIn(PlayerEvent.PlayerLoggedInEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer player) || token.isBlank()) return;
        String body = "{\"workerId\":\""+esc(workerId)+"\",\"uuid\":\""+player.getUUID()+"\",\"name\":\""+esc(player.getGameProfile().getName())+"\"}";
        postForBody("/api/agent/player-restore", body, response -> {
            MinecraftServer s = server;
            if (s != null) s.execute(() -> applyRestore(player, response));
        });
    }

    private void playerLoggedOut(PlayerEvent.PlayerLoggedOutEvent e) {
        if (e.getEntity() instanceof ServerPlayer player && !token.isBlank()) {
            flushPlayerProgress(player);
            post("/api/agent/player-state", playerStateBody(player));
        }
    }

    private void flushPlayerProgress(ServerPlayer player) {
        MinecraftServer s=server;
        if(s==null||player==null)return;
        long now=System.currentTimeMillis();
        // One saveAll() persists every online player NBT record. Throttle it so a
        // batched worker evacuation does not perform O(players^2) disk writes.
        if(now-lastPlayerDataSaveAllMillis>=100L){
            lastPlayerDataSaveAllMillis=now;
            try{s.getPlayerList().saveAll();}catch(Exception ex){LOG.warn("Could not flush player NBT for {}: {}",player.getGameProfile().getName(),ex.getMessage());}
        }
        try{player.getAdvancements().save();}catch(Exception ex){LOG.warn("Could not flush advancements for {}: {}",player.getGameProfile().getName(),ex.getMessage());}
        try{player.getStats().save();}catch(Exception ex){LOG.warn("Could not flush statistics for {}: {}",player.getGameProfile().getName(),ex.getMessage());}
    }

    private void chunkLoad(ChunkEvent.Load e) {
        if (!(e.getLevel() instanceof ServerLevel level) || !(e.getChunk() instanceof LevelChunk chunk)) return;
        String dimension = level.dimension().location().toString();
        trackedChunks.computeIfAbsent(dimension, k -> new ConcurrentHashMap<>()).put(chunk.getPos().toLong(), Boolean.TRUE);
    }

    private void chunkUnload(ChunkEvent.Unload e) {
        if (!(e.getLevel() instanceof ServerLevel level) || !(e.getChunk() instanceof LevelChunk chunk)) return;
        String dimension = level.dimension().location().toString();
        if (blockEntityPersistenceEnabled && persistenceEnabled) {
            // R14: an unload is not a reason to republish every BlockEntity in the
            // chunk. Capture only changed fingerprints into the coalescer, then
            // durably flush the pending mutations belonging to this chunk.
            scanChunkBlockEntities(level, chunk, false, Integer.MAX_VALUE, false);
            flushPendingBlockEntityWritesForChunk(dimension, chunk.getPos().x, chunk.getPos().z);
        }
        ConcurrentHashMap<Long,Boolean> chunks = trackedChunks.get(dimension);
        if (chunks != null) chunks.remove(chunk.getPos().toLong());
        String key=replicaKey(dimension,chunk.getPos().x,chunk.getPos().z);
        naturalChunkTicks.remove(key);capturedChunkMutations.remove(key);
    }

    private void tickPre(ServerTickEvent.Pre e) {
        tickStart.set(System.nanoTime());
        releaseExpiredRemoteGrowthTickets();
    }

    private void tickPost(ServerTickEvent.Post e) {
        long start = tickStart.getAndSet(0);
        if (start != 0) {
            double ms = (System.nanoTime() - start) / 1_000_000.0;
            emaMspt = emaMspt == 0 ? ms : (emaMspt * 0.90 + ms * 0.10);
            synchronized (tickSamples) {
                tickSamples[tickSampleIndex] = ms;
                tickSampleIndex = (tickSampleIndex + 1) % tickSamples.length;
                if (tickSampleCount < tickSamples.length) tickSampleCount++;
                if ((tickCounter % 20) == 0) calculatePercentiles();
            }
        }
        tickCounter++;
        worldEnvironment.tick(e.getServer());
        flushCapturedBlocks();
        sealedRegions.entrySet().removeIf(x->x.getValue()<System.currentTimeMillis());
        if (!hydration.pending().isEmpty() && System.currentTimeMillis()-lastPersistenceReplayRequestMillis>=5000L) requestPersistenceReplay();
        if (blockEntityPersistenceEnabled && persistenceEnabled && (tickCounter % 5L) == 0L) retryPendingBlockEntityApplies();
        if (blockEntityPersistenceEnabled && persistenceEnabled && (tickCounter % blockEntityScanIntervalTicks) == 0) scanAllBlockEntities(false);
        if (blockEntityPersistenceEnabled && persistenceEnabled && (tickCounter % 20L) == 0L) flushPendingBlockEntityWrites(false);
        if ((tickCounter % 20) == 0) { snapshotServerMetrics(); requestReplicaChunksSafe(); checkpointPersistenceIfNeeded(); }
    }

    private void snapshotServerMetrics() {
        MinecraftServer s = server;
        if (s == null) return;
        try {
            worldEnvironment.capture(s);
            players = s.getPlayerList().getPlayerCount();
            maxPlayers = s.getPlayerList().getMaxPlayers();
            int chunks = 0, dims = 0;
            for (ServerLevel level : s.getAllLevels()) {
                dims++;
                chunks += level.getChunkSource().getLoadedChunksCount();
            }
            loadedChunks = chunks;
            dimensions = dims;
            playerStateBlob = encodePlayerStates(s);
        } catch (Exception ex) {
            LOG.debug("Cluster metrics snapshot skipped: {}", ex.getMessage());
        }
    }

    private String encodePlayerStates(MinecraftServer s) {
        StringBuilder text = new StringBuilder();
        for (ServerPlayer p : s.getPlayerList().getPlayers()) {
            if (!text.isEmpty()) text.append('\n');
            text.append(p.getUUID()).append('\t')
                    .append(field(p.getGameProfile().getName())).append('\t')
                    .append(field(p.serverLevel().dimension().location().toString())).append('\t')
                    .append(String.format(Locale.ROOT,"%.5f\t%.5f\t%.5f\t%.3f\t%.3f\t",p.getX(),p.getY(),p.getZ(),p.getYRot(),p.getXRot()))
                    .append(field(p.gameMode.getGameModeForPlayer().getName())).append('\t')
                    .append(s.getPlayerList().isOp(p.getGameProfile()));
        }
        if (text.isEmpty()) return "";
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.toString().getBytes(StandardCharsets.UTF_8));
    }

    private String playerStateBody(ServerPlayer p) {
        MinecraftServer s = server;
        boolean op = s != null && s.getPlayerList().isOp(p.getGameProfile());
        return String.format(Locale.ROOT,
                "{\"workerId\":\"%s\",\"uuid\":\"%s\",\"name\":\"%s\",\"dimensionId\":\"%s\",\"x\":%.5f,\"y\":%.5f,\"z\":%.5f,\"yaw\":%.3f,\"pitch\":%.3f,\"gameMode\":\"%s\",\"op\":%s}",
                esc(workerId), p.getUUID(), esc(p.getGameProfile().getName()), esc(p.serverLevel().dimension().location().toString()),
                p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot(), esc(p.gameMode.getGameModeForPlayer().getName()), op);
    }

    private void applyRestore(ServerPlayer p, String body) {
        if (body == null || !body.contains("\"found\":true")) {
            post("/api/agent/player-state", playerStateBody(p));
            return;
        }
        try {
            MinecraftServer s = server;
            if (s == null || !p.isAlive()) return;
            String dimension = jsonString(body,"dimensionId");
            double x=jsonDouble(body,"x",p.getX()), y=jsonDouble(body,"y",p.getY()), z=jsonDouble(body,"z",p.getZ());
            float yaw=(float)jsonDouble(body,"yaw",p.getYRot()), pitch=(float)jsonDouble(body,"pitch",p.getXRot());
            String gm=jsonString(body,"gameMode"); boolean op=jsonBoolean(body,"op",false);

            ResourceLocation location = ResourceLocation.tryParse(dimension);
            ServerLevel target = location == null ? null : s.getLevel(ResourceKey.create(Registries.DIMENSION, location));
            if (target != null) p.teleportTo(target,x,y,z,yaw,pitch); else p.teleportTo(x,y,z);
            GameType type = GameType.byName(gm, p.gameMode.getGameModeForPlayer());
            p.setGameMode(type);
            boolean currentOp=s.getPlayerList().isOp(p.getGameProfile());
            if(op&&!currentOp)s.getPlayerList().op(p.getGameProfile()); else if(!op&&currentOp)s.getPlayerList().deop(p.getGameProfile());
            LOG.info("Restored cluster player state for {}: dim={} pos={},{},{} gamemode={} op={}",p.getGameProfile().getName(),dimension,x,y,z,type.getName(),op);
        } catch (Exception ex) {
            LOG.warn("Could not restore cluster player state for {}: {}",p.getGameProfile().getName(),ex.getMessage());
        }
    }


    private void handleHandoffControl(String body) {
        String batch=jsonString(body,"handoffCommands");
        if(batch!=null&&!batch.isBlank()){
            for(String row:batch.split(";")){
                try{
                    String[] p=row.split(",",-1);if(p.length<6)continue;
                    handleHandoffAction(unb64(p[0]),unb64(p[1]),unb64(p[2]),unb64(p[5]));
                }catch(Exception ex){LOG.warn("Could not decode batched handoff control: {}",ex.getMessage());}
            }
            return;
        }
        String action=jsonString(body,"handoffAction");
        if(action.isBlank()||action.equals("NONE"))return;
        handleHandoffAction(action,jsonString(body,"handoffId"),jsonString(body,"handoffPlayerId"),jsonString(body,"handoffSnapshot"));
    }

    private void handleHandoffAction(String action,String id,String playerName,String snapshot){
        if(action==null||action.isBlank()||id==null||id.isBlank())return;
        long now=System.currentTimeMillis(),last=handoffCommandThrottle.getOrDefault(id+":"+action,0L);
        if(now-last<500)return;
        handoffCommandThrottle.put(id+":"+action,now);
        MinecraftServer s=server;if(s==null)return;
        if(action.equals("SNAPSHOT_SOURCE_INITIAL")||action.equals("SNAPSHOT_SOURCE_FINAL")){
            s.execute(()->{
                ServerPlayer p=s.getPlayerList().getPlayerByName(playerName);
                if(p==null){post("/api/agent/handoff/abort","{\"handoffId\":\""+esc(id)+"\",\"workerId\":\""+esc(workerId)+"\",\"error\":\"source player not present\"}");return;}
                flushPlayerProgress(p);
                // R11: flush any debounced BlockEntity mutations before authority or
                // physical-anchor movement so the destination never starts behind.
                flushCapturedBlocks();scanAllBlockEntities(true);flushPendingBlockEntityWrites(true);
                awaitPersistencePosts(5000L);
                if(pendingWriteCount.get()>0||failedWriteCount.get()>0){post("/api/agent/handoff/abort","{\"handoffId\":\""+esc(id)+"\",\"workerId\":\""+esc(workerId)+"\",\"error\":\"Source has unconfirmed durable writes\"}");return;}
                String playerSnapshot=playerStateBody(p);
                post("/api/agent/handoff/snapshot","{\"handoffId\":\""+esc(id)+"\",\"workerId\":\""+esc(workerId)+"\",\"snapshot\":\""+esc(playerSnapshot)+"\"}");
                LOG.info("Prepared handoff source snapshot {} for {} action={}",id,playerName,action);
            });
        } else if(action.equals("PRELOAD_DESTINATION")){
            String finalSnapshot=snapshot==null?"":snapshot;
            s.execute(()->preloadHandoffDestination(id,playerName,finalSnapshot));
        }
    }

    private void preloadHandoffDestination(String id,String playerName,String snapshot){
        try{
            String dimension=jsonString(snapshot,"dimensionId");double x=jsonDouble(snapshot,"x",0),z=jsonDouble(snapshot,"z",0);
            ResourceLocation location=ResourceLocation.tryParse(dimension);
            ServerLevel level=location==null?null:server.getLevel(ResourceKey.create(Registries.DIMENSION,location));
            if(level==null)level=server.overworld();
            int chunkX=Math.floorDiv((int)Math.floor(x),16),chunkZ=Math.floorDiv((int)Math.floor(z),16);
            int radius=Math.max(0,parseInt(env("FOUNDATIONS_HANDOFF_PRELOAD_RADIUS","2"),2));
            for(int dx=-radius;dx<=radius;dx++)for(int dz=-radius;dz<=radius;dz++)level.getChunk(chunkX+dx,chunkZ+dz);
            post("/api/agent/handoff/ready","{\"handoffId\":\""+esc(id)+"\",\"workerId\":\""+esc(workerId)+"\"}");
            LOG.info("Prepared handoff destination {} for {} around chunk {},{} radius={}",id,playerName,chunkX,chunkZ,radius);
        }catch(Exception ex){
            post("/api/agent/handoff/abort","{\"handoffId\":\""+esc(id)+"\",\"workerId\":\""+esc(workerId)+"\",\"error\":\""+esc(ex.getMessage())+"\"}");
            LOG.warn("Could not preload handoff destination {}: {}",id,ex.getMessage());
        }
    }


    private void updateSimulationRoutes(String token){
        // HF10: a route/ownership transition must not inherit replica freshness from
        // the previous anchor epoch. Force the new anchor to request authority data.
        replicaChunkApplied.clear();
        replicaChunkEpoch.clear();
        replicaChunkFingerprint.clear();
        replicaChunkRequested.clear();
        simulationRoutes.clear();
        if(token==null||token.isBlank())return;
        for(String row:token.split(";")){
            try{
                String[] p=row.split(",",-1);if(p.length<7)continue;
                SimulationRoute r=new SimulationRoute(unb64(p[0]),unb64(p[1]),unb64(p[2]),unb64(p[3]),unb64(p[4]),Long.parseLong(p[5]),unb64(p[6]));
                simulationRoutes.put(r.playerId().toLowerCase(Locale.ROOT),r);
            }catch(Exception ignored){}
        }
        LOG.info("Simulation data routes assigned to anchor {}: {}",workerId,simulationRoutes.size());
    }

    private SimulationRoute routeFor(String playerName){return playerName==null?null:simulationRoutes.get(playerName.toLowerCase(Locale.ROOT));}

    private void blockBreak(BlockEvent.BreakEvent e){
        if(token.isBlank()||!remoteBlockPrototype||!(e.getPlayer() instanceof ServerPlayer player))return;
        BlockPos pos=e.getPos();String dimension=player.serverLevel().dimension().location().toString();
        if(ownedAuthorityFor("foundations",dimension,pos)!=null)return;
        SimulationRoute r=routeForRegion(regionIdForBlock("foundations",dimension,pos),dimension);
        if(r==null||r.authorityWorkerId().isBlank()||r.authorityWorkerId().equals(workerId))return;
        e.setCanceled(true);
        if(player.gameMode.getGameModeForPlayer()!=GameType.CREATIVE){LOG.debug("Remote block prototype denied non-creative break for {}",player.getGameProfile().getName());return;}
        publishBlockRequest(r,"BREAK",dimension,pos,"");
    }

    private void blockPlace(BlockEvent.EntityPlaceEvent e){
        if(token.isBlank()||!remoteBlockPrototype||!(e.getEntity() instanceof ServerPlayer player))return;
        BlockPos pos=e.getPos();String dimension=player.serverLevel().dimension().location().toString();
        if(ownedAuthorityFor("foundations",dimension,pos)!=null)return;
        SimulationRoute r=routeForRegion(regionIdForBlock("foundations",dimension,pos),dimension);
        if(r==null||r.authorityWorkerId().isBlank()||r.authorityWorkerId().equals(workerId))return;
        e.setCanceled(true);
        if(player.gameMode.getGameModeForPlayer()!=GameType.CREATIVE){LOG.debug("Remote block prototype denied non-creative placement for {}",player.getGameProfile().getName());return;}
        publishBlockRequest(r,"PLACE",dimension,pos,encodeBlockState(e.getPlacedBlock()));
    }

    private void publishBlockRequest(SimulationRoute r,String op,String dimension,BlockPos pos,String blockState){
        String payload=op+"|"+pos.getX()+"|"+pos.getY()+"|"+pos.getZ()+"|"+b64(blockState);
        String body="{\"kind\":\"BLOCK_REQUEST\",\"sourceWorkerId\":\""+esc(workerId)+"\",\"targetWorkerId\":\""+esc(r.authorityWorkerId())+"\",\"sessionId\":\""+esc(r.sessionId())+"\",\"playerId\":\""+esc(r.playerId())+"\",\"worldId\":\"foundations\",\"dimensionId\":\""+esc(dimension)+"\",\"regionId\":\""+esc(r.regionId())+"\",\"authorityEpoch\":"+r.authorityEpoch()+",\"payload\":\""+esc(payload)+"\"}";
        post("/api/agent/dataplane/publish",body);
        LOG.debug("Forwarded remote block {} request for {} to authority {}",op,r.playerId(),r.authorityWorkerId());
    }

    private void pollDataPlaneSafe(){
        if(token.isBlank()||server==null||!dataPlanePollInFlight.compareAndSet(false,true))return;
        try{
            HttpRequest req=HttpRequest.newBuilder(URI.create(manager+"/api/agent/dataplane/poll"))
                    .timeout(Duration.ofSeconds(2)).header("Content-Type","application/json").header("X-Foundations-Agent-Token",token)
                    .POST(HttpRequest.BodyPublishers.ofString("{\"workerId\":\""+esc(workerId)+"\",\"maxFrames\":64}" )).build();
            HttpResponse<String> resp=client.send(req,HttpResponse.BodyHandlers.ofString());
            if(resp.statusCode()<300){String bundle=jsonString(resp.body(),"bundle");if(!bundle.isBlank())applyDataPlaneBundle(bundle);}
            else LOG.debug("Data-plane poll returned HTTP {}",resp.statusCode());
        }catch(Exception ex){LOG.debug("Data-plane poll skipped: {}",ex.getMessage());}
        finally{dataPlanePollInFlight.set(false);}
    }

    private void applyDataPlaneBundle(String bundle){
        try{
            String text=new String(Base64.getUrlDecoder().decode(bundle),StandardCharsets.UTF_8);
            for(String line:text.split("\\n"))if(!line.isBlank()){
                DataFrame f=decodeFrame(line);
                MinecraftServer s=server;if(s==null)continue;
                s.execute(()->applyDataPlaneFrame(f));
            }
        }catch(Exception ex){LOG.warn("Could not decode data-plane bundle: {}",ex.getMessage());}
    }

    private DataFrame decodeFrame(String line){
        String[] p=line.split("\\t",-1);if(p.length<11)throw new IllegalArgumentException("bad frame");
        return new DataFrame(unb64(p[0]),unb64(p[1]),unb64(p[2]),unb64(p[3]),unb64(p[4]),unb64(p[5]),unb64(p[6]),unb64(p[7]),unb64(p[8]),Long.parseLong(p[9]),unb64(p[10]));
    }

    private void applyDataPlaneFrame(DataFrame f){
        boolean ok=false;String error="";boolean priorReplay=replayingMutations;
        try{
            if(Set.of("BLOCK_DELTA","BLOCK_ENTITY_DELTA","CHUNK_SNAPSHOT","CHUNK_INVALIDATE").contains(f.kind())){
                SimulationRoute route=routeForRegion(f.regionId(),f.dimensionId());
                if(ownedAuthorities.containsKey(f.regionId())||route==null||route.authorityEpoch()!=f.authorityEpoch()||!route.authorityWorkerId().equals(f.sourceWorkerId()))
                    throw new IllegalStateException("Stale replica frame owner/epoch");
                replayingMutations=true;
            }
            if(f.kind().equals("BLOCK_REQUEST"))applyAuthorityBlockRequest(f);
            else if(f.kind().equals("BLOCK_DELTA"))applyReplicaBlockDelta(f);
            else if(f.kind().equals("BLOCK_ENTITY_REQUEST"))applyAuthorityBlockEntityRequest(f);
            else if(f.kind().equals("BLOCK_ENTITY_DELTA"))applyReplicaBlockEntityDelta(f);
            else if(f.kind().equals("CHUNK_REQUEST"))applyAuthorityChunkRequest(f);
            else if(f.kind().equals("CHUNK_SNAPSHOT"))applyReplicaChunkSnapshot(f);
            else if(f.kind().equals("CHUNK_INVALIDATE"))applyReplicaChunkInvalidate(f);
            else LOG.debug("Ignoring unsupported data-plane frame kind {}",f.kind());
            ok=true;
        }catch(Exception ex){error=ex.getMessage()==null?ex.toString():ex.getMessage();LOG.warn("Data-plane frame {} {} failed: {}",f.id(),f.kind(),error);}
        replayingMutations=priorReplay;
        ackDataPlane(f.id(),ok,error);
    }

    private void applyAuthorityBlockRequest(DataFrame f){
        if(!ownsActiveAuthority(f.regionId(),f.authorityEpoch())){LOG.debug("Ignoring fenced {} for region={} epoch={}",f.kind(),f.regionId(),f.authorityEpoch());return;}
        String[] p=f.payload().split("\\|",-1);if(p.length<5)throw new IllegalArgumentException("bad block request payload");
        String op=p[0];BlockPos pos=new BlockPos(Integer.parseInt(p[1]),Integer.parseInt(p[2]),Integer.parseInt(p[3]));
        if(!f.regionId().equals(regionIdForBlock(f.worldId(),f.dimensionId(),pos)))throw new IllegalArgumentException("Block request coordinates outside authority region");
        ServerLevel level=levelFor(f.dimensionId());if(level==null)throw new IllegalStateException("dimension unavailable: "+f.dimensionId());
        if(op.equals("BREAK"))level.setBlockAndUpdate(pos,Blocks.AIR.defaultBlockState());
        else if(op.equals("PLACE"))level.setBlockAndUpdate(pos,decodeBlockState(unb64(p[4])));
        else throw new IllegalArgumentException("unsupported block op: "+op);
        BlockState result=level.getBlockState(pos);
        if(persistenceEnabled){
            publishPersistentBlock(f.sessionId(),f.playerId(),f.worldId(),f.dimensionId(),f.regionId(),f.authorityEpoch(),pos,result);
            if(blockEntityPersistenceEnabled)publishPersistentBlockEntityAt(f.sessionId(),f.playerId(),f.worldId(),f.dimensionId(),f.regionId(),f.authorityEpoch(),level,pos);
        }
        String payload=pos.getX()+"|"+pos.getY()+"|"+pos.getZ()+"|"+b64(encodeBlockState(result));
        String body="{\"kind\":\"BLOCK_DELTA\",\"sourceWorkerId\":\""+esc(workerId)+"\",\"targetWorkerId\":\""+esc(f.sourceWorkerId())+"\",\"sessionId\":\""+esc(f.sessionId())+"\",\"playerId\":\""+esc(f.playerId())+"\",\"worldId\":\""+esc(f.worldId())+"\",\"dimensionId\":\""+esc(f.dimensionId())+"\",\"regionId\":\""+esc(f.regionId())+"\",\"authorityEpoch\":"+f.authorityEpoch()+",\"payload\":\""+esc(payload)+"\"}";
        post("/api/agent/dataplane/publish",body);
    }

    private void applyReplicaBlockDelta(DataFrame f){
        String[] p=f.payload().split("\\|",-1);if(p.length<4)throw new IllegalArgumentException("bad block delta payload");
        BlockPos pos=new BlockPos(Integer.parseInt(p[0]),Integer.parseInt(p[1]),Integer.parseInt(p[2]));
        ServerLevel level=levelFor(f.dimensionId());if(level==null)throw new IllegalStateException("dimension unavailable: "+f.dimensionId());
        level.setBlockAndUpdate(pos,decodeBlockState(unb64(p[3])));
        retryPendingBlockEntityAt(level,pos);
        String key=replicaKey(f.dimensionId(),Math.floorDiv(pos.getX(),16),Math.floorDiv(pos.getZ(),16));
        replicaChunkApplied.put(key,System.currentTimeMillis());replicaChunkEpoch.put(key,f.authorityEpoch());
    }


    private void applyAuthorityBlockEntityRequest(DataFrame f){
        if(!ownsActiveAuthority(f.regionId(),f.authorityEpoch())){LOG.debug("Ignoring fenced {} for region={} epoch={}",f.kind(),f.regionId(),f.authorityEpoch());return;}
        BlockEntityPayload p=parseBlockEntityPayload(f.payload());
        String expectedRegion=regionIdForBlock(f.worldId(),f.dimensionId(),p.pos());
        if(!expectedRegion.equals(f.regionId()))throw new IllegalArgumentException("BlockEntity request coordinate does not belong to claimed region expected="+expectedRegion+" claimed="+f.regionId());
        ServerLevel level=levelFor(f.dimensionId());if(level==null)throw new IllegalStateException("dimension unavailable: "+f.dimensionId());
        applyBlockEntityPayload(level,p);
        if(persistenceEnabled&&blockEntityPersistenceEnabled)publishPersistentBlockEntity(f.sessionId(),f.playerId(),f.worldId(),f.dimensionId(),f.regionId(),f.authorityEpoch(),p);
        String body="{\"kind\":\"BLOCK_ENTITY_DELTA\",\"sourceWorkerId\":\""+esc(workerId)+"\",\"targetWorkerId\":\""+esc(f.sourceWorkerId())+"\",\"sessionId\":\""+esc(f.sessionId())+"\",\"playerId\":\""+esc(f.playerId())+"\",\"worldId\":\""+esc(f.worldId())+"\",\"dimensionId\":\""+esc(f.dimensionId())+"\",\"regionId\":\""+esc(f.regionId())+"\",\"authorityEpoch\":"+f.authorityEpoch()+",\"payload\":\""+esc(encodeBlockEntityPayload(p))+"\"}";
        post("/api/agent/dataplane/publish",body);
    }

    private void applyReplicaBlockEntityDelta(DataFrame f){
        BlockEntityPayload p=parseBlockEntityPayload(f.payload());
        ServerLevel level=levelFor(f.dimensionId());if(level==null)throw new IllegalStateException("dimension unavailable: "+f.dimensionId());
        applyBlockEntityPayload(level,p);
        remoteBlockEntityPending.remove(blockEntityKey(f.dimensionId(),p.pos()));
        String key=replicaKey(f.dimensionId(),Math.floorDiv(p.pos().getX(),16),Math.floorDiv(p.pos().getZ(),16));
        replicaChunkApplied.put(key,System.currentTimeMillis());replicaChunkEpoch.put(key,f.authorityEpoch());
    }

    private void publishPersistentBlockEntityAt(String sessionId,String playerId,String worldId,String dimensionId,String regionId,long authorityEpoch,ServerLevel level,BlockPos pos){
        BlockEntity be=level.getBlockEntity(pos);
        BlockEntityPayload payload=be==null?new BlockEntityPayload(pos,false,"","",""):captureBlockEntityPayload(level,be);
        publishPersistentBlockEntity(sessionId,playerId,worldId,dimensionId,regionId,authorityEpoch,payload);
        String key=blockEntityKey(dimensionId,pos);
        if(payload.present())blockEntityFingerprints.put(key,blockEntityFingerprint(payload)); else blockEntityFingerprints.remove(key);
    }

    private void publishPersistentBlockEntity(String sessionId,String playerId,String worldId,String dimensionId,String regionId,long authorityEpoch,BlockEntityPayload p){
        if(!persistenceEnabled||!blockEntityPersistenceEnabled||token.isBlank())return;
        if(!ownsActiveAuthority(regionId,authorityEpoch)){LOG.debug("Skipping fenced persistent BlockEntity write region={} epoch={}",regionId,authorityEpoch);return;}
        pendingPersistentBlockEntities.remove(blockEntityKey(dimensionId,p.pos()));
        String payload=encodeBlockEntityPayload(p);
        if(payload.getBytes(StandardCharsets.UTF_8).length>blockEntityMaxPayloadBytes){LOG.warn("Skipping oversized BlockEntity persistence at {} payload={} bytes limit={}",p.pos(),payload.length(),blockEntityMaxPayloadBytes);return;}
        String body="{\"kind\":\"PERSIST_BLOCK_ENTITY\",\"sourceWorkerId\":\""+esc(workerId)+"\",\"sessionId\":\""+esc(sessionId)+"\",\"playerId\":\""+esc(playerId)+"\",\"worldId\":\""+esc(worldId)+"\",\"dimensionId\":\""+esc(dimensionId)+"\",\"regionId\":\""+esc(regionId)+"\",\"authorityEpoch\":"+authorityEpoch+",\"payload\":\""+esc(payload)+"\"}";
        postPersistence("/api/agent/dataplane/publish",body);
        blockEntityLastPersistedAt.put(blockEntityKey(dimensionId,p.pos()),System.currentTimeMillis());
        persistentMutationCount.incrementAndGet();
    }

    private void queuePersistentBlockEntity(String sessionId,String playerId,String worldId,String dimensionId,String regionId,long authorityEpoch,BlockEntityPayload payload){
        String key=blockEntityKey(dimensionId,payload.pos());
        long now=System.currentTimeMillis();
        pendingPersistentBlockEntities.compute(key,(k,old)->new PendingPersistentBlockEntity(
                sessionId,playerId,worldId,dimensionId,regionId,authorityEpoch,payload,
                old==null?now:old.firstQueuedAtMillis(),now));
    }

    private void flushPendingBlockEntityWrites(boolean force){
        if(pendingPersistentBlockEntities.isEmpty())return;
        long now=System.currentTimeMillis();int flushed=0;
        for(var entry:new ArrayList<>(pendingPersistentBlockEntities.entrySet())){
            PendingPersistentBlockEntity pending=entry.getValue();
            boolean quiet=now-pending.lastChangedAtMillis()>=blockEntityCoalesceMillis;
            long lastPersisted=blockEntityLastPersistedAt.getOrDefault(entry.getKey(),0L);
            boolean maxAge=now-pending.firstQueuedAtMillis()>=blockEntityCoalesceMaxMillis
                    && now-lastPersisted>=blockEntityCoalesceMaxMillis;
            if(!force&&!quiet&&!maxAge)continue;
            if(!pendingPersistentBlockEntities.remove(entry.getKey(),pending))continue;
            publishPersistentBlockEntity(pending.sessionId(),pending.playerId(),pending.worldId(),pending.dimensionId(),pending.regionId(),pending.authorityEpoch(),pending.payload());
            flushed++;
        }
        if(flushed>0)LOG.debug("Flushed {} coalesced persistent BlockEntity update(s) force={} remaining={}",flushed,force,pendingPersistentBlockEntities.size());
    }

    private void flushPendingBlockEntityWritesForChunk(String dimensionId,int chunkX,int chunkZ){
        if(pendingPersistentBlockEntities.isEmpty())return;
        int flushed=0;
        for(var entry:new ArrayList<>(pendingPersistentBlockEntities.entrySet())){
            PendingPersistentBlockEntity pending=entry.getValue();
            if(!Objects.equals(dimensionId,pending.dimensionId()))continue;
            BlockPos pos=pending.payload().pos();
            if((pos.getX()>>4)!=chunkX || (pos.getZ()>>4)!=chunkZ)continue;
            if(!pendingPersistentBlockEntities.remove(entry.getKey(),pending))continue;
            publishPersistentBlockEntity(pending.sessionId(),pending.playerId(),pending.worldId(),pending.dimensionId(),pending.regionId(),pending.authorityEpoch(),pending.payload());
            flushed++;
        }
        if(flushed>0)LOG.debug("Flushed {} coalesced persistent BlockEntity update(s) for unloading chunk {},{} remaining={}",flushed,chunkX,chunkZ,pendingPersistentBlockEntities.size());
    }

    private void publishBlockEntityRequest(SimulationRoute r,String dimensionId,BlockEntityPayload p){
        if(token.isBlank()||r==null||r.authorityWorkerId().isBlank())return;
        String payload=encodeBlockEntityPayload(p);
        if(payload.getBytes(StandardCharsets.UTF_8).length>blockEntityMaxPayloadBytes){LOG.warn("Skipping oversized remote BlockEntity write at {} payload={} bytes limit={}",p.pos(),payload.length(),blockEntityMaxPayloadBytes);return;}
        String body="{\"kind\":\"BLOCK_ENTITY_REQUEST\",\"sourceWorkerId\":\""+esc(workerId)+"\",\"targetWorkerId\":\""+esc(r.authorityWorkerId())+"\",\"sessionId\":\""+esc(r.sessionId())+"\",\"playerId\":\""+esc(r.playerId())+"\",\"worldId\":\"foundations\",\"dimensionId\":\""+esc(dimensionId)+"\",\"regionId\":\""+esc(r.regionId())+"\",\"authorityEpoch\":"+r.authorityEpoch()+",\"payload\":\""+esc(payload)+"\"}";
        post("/api/agent/dataplane/publish",body);
    }

    private BlockEntityPayload captureBlockEntityPayload(ServerLevel level,BlockEntity be){
        try{
            CompoundTag tag=be.saveWithFullMetadata(level.registryAccess());
            ByteArrayOutputStream out=new ByteArrayOutputStream();NbtIo.writeCompressed(tag,out);
            String nbt=Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray());
            ResourceLocation id=BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType());
            String hostState=encodeBlockState(level.getBlockState(be.getBlockPos()));
            return new BlockEntityPayload(be.getBlockPos(),true,id==null?"":id.toString(),hostState,nbt);
        }catch(Exception ex){throw new IllegalStateException("could not serialize BlockEntity at "+be.getBlockPos()+": "+ex.getMessage(),ex);}
    }

    private void applyBlockEntityPayload(ServerLevel level,BlockEntityPayload p){
        if(!tryApplyBlockEntityPayload(level,p,true))
            LOG.debug("Deferred BlockEntity {} at {} until matching block state is available",p.typeId(),p.pos());
    }

    private boolean applyBlockEntityPayloadReplay(ServerLevel level,BlockEntityPayload p){
        return tryApplyBlockEntityPayload(level,p,false);
    }

    private boolean tryApplyBlockEntityPayload(ServerLevel level,BlockEntityPayload p,boolean deferIfIncompatible){
        String dimension=level.dimension().location().toString();
        String key=blockEntityKey(dimension,p.pos());
        if(!p.present()){
            BlockEntity existing=level.getBlockEntity(p.pos());
            if(existing!=null){level.removeBlockEntity(p.pos());BlockState state=level.getBlockState(p.pos());level.sendBlockUpdated(p.pos(),state,state,3);}
            blockEntityFingerprints.remove(key);pendingBlockEntityApplies.remove(key);return true;
        }
        try{
            byte[] raw=Base64.getUrlDecoder().decode(p.nbtPayload());
            CompoundTag tag=NbtIo.readCompressed(new ByteArrayInputStream(raw),NbtAccounter.create(Math.max(16L*1024L*1024L,raw.length*16L)));
            BlockPos tagPos=BlockEntity.getPosFromTag(tag);
            if(!tagPos.equals(p.pos()))throw new IllegalArgumentException("BlockEntity NBT position mismatch tag="+tagPos+" expected="+p.pos());
            level.getChunkAt(p.pos());
            BlockState state=level.getBlockState(p.pos());
            if(!p.blockState().isBlank()){
                BlockState desired=decodeBlockState(p.blockState());
                if(!state.equals(desired)){
                    level.setBlock(p.pos(),desired,replayingMutations?2|16|32:3);
                    state=level.getBlockState(p.pos());
                }
            }
            BlockEntity be=level.getBlockEntity(p.pos());
            String currentType=be==null?"":String.valueOf(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()));
            if(be==null||(!p.typeId().isBlank()&&!p.typeId().equals(currentType))){
                BlockEntity candidate=null;
                if(state.getBlock() instanceof EntityBlock entityBlock)candidate=entityBlock.newBlockEntity(p.pos(),state);
                String candidateType=candidate==null?"":String.valueOf(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(candidate.getType()));
                if(candidate==null||(!p.typeId().isBlank()&&!p.typeId().equals(candidateType))){
                    if(deferIfIncompatible)queuePendingBlockEntityApply(dimension,p);
                    return false;
                }
                if(be!=null)level.removeBlockEntity(p.pos());
                be=candidate;
                be.loadWithComponents(tag,level.registryAccess());
                level.setBlockEntity(be);
            }else{
                be.loadWithComponents(tag,level.registryAccess());
            }
            be.setChanged();
            level.sendBlockUpdated(p.pos(),state,state,3);
            blockEntityFingerprints.put(key,blockEntityFingerprint(p));
            pendingBlockEntityApplies.remove(key);
            return true;
        }catch(Exception ex){
            throw new IllegalStateException("could not apply BlockEntity at "+p.pos()+": "+ex.getMessage(),ex);
        }
    }

    private void queuePendingBlockEntityApply(String dimensionId,BlockEntityPayload p){
        String key=blockEntityKey(dimensionId,p.pos());
        long now=System.currentTimeMillis();
        pendingBlockEntityApplies.compute(key,(k,old)->new PendingBlockEntityApply(dimensionId,p,old==null?now:old.queuedAtMillis(),old==null?1:old.attempts()+1));
    }

    private void retryPendingBlockEntityAt(ServerLevel level,BlockPos pos){
        String key=blockEntityKey(level.dimension().location().toString(),pos);
        PendingBlockEntityApply pending=pendingBlockEntityApplies.get(key);
        if(pending==null)return;
        try{
            if(tryApplyBlockEntityPayload(level,pending.payload(),false))pendingBlockEntityApplies.remove(key,pending);
        }catch(Exception ex){
            LOG.warn("Deferred BlockEntity retry failed at {}: {}",pos,ex.getMessage());
            pendingBlockEntityApplies.remove(key,pending);
        }
    }

    private void retryPendingBlockEntityApplies(){
        if(pendingBlockEntityApplies.isEmpty())return;
        long now=System.currentTimeMillis();int budget=256;
        for(var entry:new ArrayList<>(pendingBlockEntityApplies.entrySet())){
            if(budget--<=0)break;
            PendingBlockEntityApply pending=entry.getValue();
            if(now-pending.queuedAtMillis()>15000L){
                if(pendingBlockEntityApplies.remove(entry.getKey(),pending))
                    LOG.warn("Dropping deferred BlockEntity {} at {} after {} attempts because a matching block state never arrived",pending.payload().typeId(),pending.payload().pos(),pending.attempts());
                continue;
            }
            ServerLevel level=levelFor(pending.dimensionId());
            if(level==null)continue;
            try{
                if(tryApplyBlockEntityPayload(level,pending.payload(),false))pendingBlockEntityApplies.remove(entry.getKey(),pending);
                else pendingBlockEntityApplies.computeIfPresent(entry.getKey(),(k,v)->new PendingBlockEntityApply(v.dimensionId(),v.payload(),v.queuedAtMillis(),v.attempts()+1));
            }catch(Exception ex){
                LOG.warn("Deferred BlockEntity {} at {} failed permanently: {}",pending.payload().typeId(),pending.payload().pos(),ex.getMessage());
                pendingBlockEntityApplies.remove(entry.getKey(),pending);
            }
        }
    }

    private static String encodeBlockEntityPayload(BlockEntityPayload p){return p.pos().getX()+"|"+p.pos().getY()+"|"+p.pos().getZ()+"|"+(p.present()?"1":"0")+"|"+b64(p.typeId())+"|"+b64(p.blockState())+"|"+(p.nbtPayload()==null?"":p.nbtPayload());}
    private static BlockEntityPayload parseBlockEntityPayload(String payload){
        String[] p=payload.split("\\|",-1);if(p.length<6)throw new IllegalArgumentException("bad BlockEntity payload");
        BlockPos pos=new BlockPos(Integer.parseInt(p[0]),Integer.parseInt(p[1]),Integer.parseInt(p[2]));boolean present="1".equals(p[3]);String type=unb64(p[4]);
        if(p.length>=7)return new BlockEntityPayload(pos,present,type,unb64(p[5]),p[6]);
        return new BlockEntityPayload(pos,present,type,"",p[5]);
    }

    private void publishPersistentBlock(String sessionId,String playerId,String worldId,String dimensionId,String regionId,long authorityEpoch,BlockPos pos,BlockState state){
        if(!persistenceEnabled||token.isBlank())return;
        if(!ownsActiveAuthority(regionId,authorityEpoch)){LOG.debug("Skipping fenced persistent block write region={} epoch={}",regionId,authorityEpoch);return;}
        String payload=pos.getX()+"|"+pos.getY()+"|"+pos.getZ()+"|"+b64(encodeBlockState(state));
        String body="{\"kind\":\"PERSIST_BLOCK\",\"sourceWorkerId\":\""+esc(workerId)+"\",\"sessionId\":\""+esc(sessionId)+"\",\"playerId\":\""+esc(playerId)+"\",\"worldId\":\""+esc(worldId)+"\",\"dimensionId\":\""+esc(dimensionId)+"\",\"regionId\":\""+esc(regionId)+"\",\"authorityEpoch\":"+authorityEpoch+",\"payload\":\""+esc(payload)+"\"}";
        postPersistence("/api/agent/dataplane/publish",body);
        persistentMutationCount.incrementAndGet();
    }

    private void requestPersistenceReplay(){
        if(!persistenceEnabled||token.isBlank()||server==null||System.currentTimeMillis()-lastPersistenceReplayRequestMillis<5000||!replayInFlight.compareAndSet(false,true))return;
        Map<String,Long> requested=hydration.pending();
        if(requested.isEmpty()){replayInFlight.set(false);return;}
        String regionToken=AuthorityHydration.token(requested);
        lastPersistenceReplayRequestMillis=System.currentTimeMillis();
        String body="{\"workerId\":\""+esc(workerId)+"\",\"persistenceInstance\":\""+persistenceInstance+"\",\"regions\":\""+regionToken+"\"}";
        HttpRequest request=HttpRequest.newBuilder(URI.create(manager+"/api/agent/persistence/replay"))
            .timeout(Duration.ofSeconds(20)).header("Content-Type","application/json").header("X-Foundations-Agent-Token",token)
            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        client.sendAsync(request,HttpResponse.BodyHandlers.ofString()).whenComplete((response,error)->{
            MinecraftServer current=server;
            if(current==null){replayInFlight.set(false);return;}
            current.execute(()->{
                try {
                    if(error!=null)throw new IllegalStateException("Replay request failed",error);
                    if(response.statusCode()!=200)throw new IllegalStateException("Replay HTTP "+response.statusCode());
                    if(!hydration.current(requested))return; // Never apply an old owner's asynchronous response.
                    if(!regionToken.equals(jsonString(response.body(),"regions")))throw new IllegalStateException("Replay region acknowledgement differs");
                    replayingMutations=true;
                    applyPersistenceReplay(jsonString(response.body(),"bundle"),requested);
                    if(blockEntityPersistenceEnabled)applyBlockEntityPersistenceReplay(jsonString(response.body(),"blockEntityBundle"),requested);
                    current.saveEverything(false,true,true);
                    hydration.complete(requested);
                    LOG.info("Region hydration complete; acknowledging restored epochs {}",regionToken);
                } catch(Exception ex) {
                    LOG.warn("Region hydration incomplete; authority remains fenced and replay will retry: {}",ex.toString());
                } finally {replayingMutations=false;replayInFlight.set(false);}
            });
        });
    }

    private void validateReplayEntry(String[] p,Map<String,Long> requested,BlockPos pos) {
        String region=unb64(p[2]);Long epoch=requested.get(region);
        if(epoch==null||Long.parseLong(p[3])>epoch||!region.equals(regionIdForBlock(unb64(p[0]),unb64(p[1]),pos)))
            throw new IllegalStateException("Replay entry is outside requested region epochs: "+region+" "+pos);
    }

    private void applyPersistenceReplay(String bundle,Map<String,Long> requested){
        if(bundle.isBlank())return;
        String raw=new String(Base64.getUrlDecoder().decode(bundle),StandardCharsets.UTF_8);int applied=0;
        for(String line:raw.split("\n")){
            if(line.isBlank())continue;String[] p=line.split("\t",-1);
            if(p.length!=9)throw new IllegalArgumentException("Malformed block replay row");
            BlockPos pos=new BlockPos(Integer.parseInt(p[4]),Integer.parseInt(p[5]),Integer.parseInt(p[6]));validateReplayEntry(p,requested,pos);
            ServerLevel level=levelFor(unb64(p[1]));if(level==null)throw new IllegalStateException("Replay dimension unavailable: "+unb64(p[1]));
            level.getChunkAt(pos);BlockState state=decodeBlockState(unb64(p[7]));
            if(!level.getBlockState(pos).equals(state)){
                // Restore a snapshot, not a sequence of gameplay actions: suppress neighbor
                // shape updates/drops until all supports, stems and leaves are present.
                level.setBlock(pos,state,2|16|32);
                BlockState actual=level.getBlockState(pos);
                if(!actual.equals(state)&&!(actual.isAir()&&state.isAir()))throw new IllegalStateException("Block replay failed at "+pos+" expected="+encodeBlockState(state)+" actual="+encodeBlockState(actual)+" region="+unb64(p[2])+" revision="+p[8]);
                // Snapshot ingestion suppresses onPlace. Resume intrinsic fire ticking after
                // hydration; the region seal still prevents mutation until ACTIVE.
                if(state.getBlock() instanceof net.minecraft.world.level.block.FireBlock)level.scheduleTick(pos,state.getBlock(),40);
                applied++;
            }
        }
        LOG.info("Applied durable region replay blocks={}",applied);
    }

    private void applyBlockEntityPersistenceReplay(String bundle,Map<String,Long> requested){
        if(bundle.isBlank())return;
        String raw=new String(Base64.getUrlDecoder().decode(bundle),StandardCharsets.UTF_8);int applied=0;
        for(String line:raw.split("\n")){
            if(line.isBlank())continue;String[] p=line.split("\t",-1);
            if(p.length!=11&&p.length!=12)throw new IllegalArgumentException("Malformed BlockEntity replay row");
            BlockPos pos=new BlockPos(Integer.parseInt(p[4]),Integer.parseInt(p[5]),Integer.parseInt(p[6]));validateReplayEntry(p,requested,pos);
            String dimension=unb64(p[1]);ServerLevel level=levelFor(dimension);
            if(level==null)throw new IllegalStateException("Replay dimension unavailable: "+dimension);
            level.getChunkAt(pos);
            String host=p.length==12?unb64(p[9]):"",nbt=unb64(p[p.length==12?10:9]);
            if(!applyBlockEntityPayloadReplay(level,new BlockEntityPayload(pos,"1".equals(p[7]),unb64(p[8]),host,nbt)))
                throw new IllegalStateException("BlockEntity replay incompatible at "+dimension+" "+pos+" type="+unb64(p[8]));
            applied++;
        }
        LOG.info("Applied durable region replay BlockEntities={}",applied);
    }

    private void checkpointPersistenceIfNeeded(){
        if(!persistenceEnabled)return;long mutations=persistentMutationCount.get();
        if(mutations==lastCheckpointMutationCount)return;long now=System.currentTimeMillis();
        if(now-lastPersistenceCheckpointMillis<persistenceCheckpointMillis)return;
        forcePersistenceCheckpoint("periodic");
    }

    private void forcePersistenceCheckpoint(String reason){
        MinecraftServer s=server;if(s==null)return;
        try{
            s.saveEverything(false,true,false);
            lastCheckpointMutationCount=persistentMutationCount.get();lastPersistenceCheckpointMillis=System.currentTimeMillis();
            LOG.info("Persistence checkpoint complete reason={} mutations={}",reason,lastCheckpointMutationCount);
        }catch(Exception ex){LOG.warn("Persistence checkpoint failed reason={} error={}",reason,ex.getMessage());}
    }

    private void postPersistence(String path,String body){
        HttpRequest req=HttpRequest.newBuilder(URI.create(manager+path)).timeout(Duration.ofSeconds(5)).header("Content-Type","application/json").header("X-Foundations-Agent-Token",token).POST(HttpRequest.BodyPublishers.ofString(body)).build();
        if(pendingWriteCount.incrementAndGet()>16384){pendingWriteCount.decrementAndGet();failedWriteCount.incrementAndGet();LOG.error("Persistence queue overflow: this worker is not safe to transfer");return;}
        synchronized(persistencePostLock){
            persistenceTail=persistenceTail.handle((ignored,priorError)->null).thenCompose(ignored->sendDurable(req,0))
                .handle((resp,err)->{pendingWriteCount.decrementAndGet();if(err!=null){failedWriteCount.incrementAndGet();LOG.error("Durable write failed; planned transfer blocked: {}",err.getMessage());}else lastDurableWrite=System.currentTimeMillis();return null;});
        }
    }
    private CompletableFuture<Void> sendDurable(HttpRequest req,int attempt){
        return client.sendAsync(req,HttpResponse.BodyHandlers.ofString()).handle((resp,err)->{
            if(err==null&&resp.statusCode()<300)return CompletableFuture.<Void>completedFuture(null);
            String problem=err==null?"HTTP "+resp.statusCode()+": "+resp.body():err.getMessage();
            if(attempt>=3||(err==null&&resp.statusCode()==409))return CompletableFuture.<Void>failedFuture(new IOException(problem));
            CompletableFuture<Void> next=new CompletableFuture<>();
            persistenceRetries.schedule(()->sendDurable(req,attempt+1).whenComplete((v,e)->{if(e==null)next.complete(null);else next.completeExceptionally(e);}),250L<<attempt,TimeUnit.MILLISECONDS);
            return next;
        }).thenCompose(f->f);
    }

    // Called by the server-side LevelChunk mutation hook, covering explosions,
    // commands, fluids, fire, pistons and normal block changes through this API.
    public static void captureMutation(Level level,BlockPos pos,BlockState oldState,BlockState state){
        FoundationsClusterNode n=activeNode;
        if(n==null||!(level instanceof ServerLevel sl)||oldState==null||oldState==state||!sl.getServer().isSameThread()||n.server==null||!n.persistenceEnabled||n.token.isBlank()||n.replayingMutations)return;
        state=sl.getBlockState(pos); // onPlace/onRemove can perform nested writes; journal the final result.
        String dim=sl.dimension().location().toString();
        if(!n.trackedChunks.getOrDefault(dim,new ConcurrentHashMap<>()).containsKey(new ChunkPos(pos).toLong()))return;
        OwnedAuthority owner=n.ownedAuthorityFor("foundations",dim,pos);if(owner==null)return;
        String row=b64(dim)+"|"+b64(owner.regionId())+"|"+owner.epoch()+"|"+pos.getX()+"|"+pos.getY()+"|"+pos.getZ()+"|"+b64(n.encodeBlockState(state));
        if(n.changedBlocks.size()>=65536){n.failedWriteCount.incrementAndGet();return;}
        n.changedBlocks.put(dim+"|"+pos.asLong(),row);
        n.capturedChunkMutations.merge(n.replicaKey(dim,Math.floorDiv(pos.getX(),16),Math.floorDiv(pos.getZ(),16)),1L,Long::sum);
        if(n.blockEntityPersistenceEnabled&&(oldState.hasBlockEntity()||state.hasBlockEntity()))n.publishPersistentBlockEntityAt("","","foundations",dim,owner.regionId(),owner.epoch(),sl,pos);
    }
    public static boolean replayingSnapshot(Level level){
        FoundationsClusterNode n=activeNode;
        return n!=null&&n.replayingMutations&&level instanceof ServerLevel sl&&sl.getServer().isSameThread();
    }
    static boolean regionReady(String region) {
        FoundationsClusterNode n=activeNode;if(n==null)return false;
        OwnedAuthority owner=n.ownedAuthorities.get(region);
        return owner!=null&&n.ownsActiveAuthority(region,owner.epoch())&&n.sealedRegions.getOrDefault(region,0L)<=System.currentTimeMillis();
    }
    public static boolean regionSealed(Level level,BlockPos pos){
        FoundationsClusterNode n=activeNode;if(n==null||level.isClientSide||n.replayingMutations)return false;
        String id=n.regionIdForBlock("foundations",level.dimension().location().toString(),pos);
        OwnedAuthority assigned=n.ownedAuthorities.get(id);
        return n.sealedRegions.getOrDefault(id,0L)>System.currentTimeMillis() || (assigned!=null&&(!assigned.active()||!n.hydration.ready(id,assigned.epoch())));
    }
    private void flushCapturedBlocks(){
        if(changedBlocks.isEmpty())return;
        List<String> rows=new ArrayList<>();
        for(var e:changedBlocks.entrySet())if(changedBlocks.remove(e.getKey(),e.getValue())){
            rows.add(e.getValue());if(rows.size()==256){postBlockBatch(rows);rows.clear();}
        }
        if(!rows.isEmpty())postBlockBatch(rows);
    }
    private void postBlockBatch(List<String> rows){
        postPersistence("/api/agent/persistence/blocks","{\"workerId\":\""+esc(workerId)+"\",\"mutations\":\""+esc(String.join(";",rows))+"\"}");
        persistentMutationCount.addAndGet(rows.size());
    }
    private void handleCheckpointControl(String commands){
        MinecraftServer s=server;if(s==null)return;
        s.execute(()->{
            Set<String> current=new HashSet<>();
            for(String row:commands.split(";")){
                if(row.isBlank())continue;
                String[] p=row.split("\\|",-1);if(p.length!=3)continue;
                String id=p[0],region=unb64(p[1]);long epoch=Long.parseLong(p[2]);current.add(region);
                if(!checkpointRequests.add(id))continue;
                if(checkpointRequests.size()>1024)checkpointRequests.removeIf(x->!x.equals(id));
                if(!ownsActiveAuthority(region,epoch)){ackCheckpoint(id,epoch,"Source epoch changed");continue;}
                sealedRegions.put(region,System.currentTimeMillis()+35000L);
                for(ServerPlayer player:s.getPlayerList().getPlayers())if(region.equals(regionIdForBlock("foundations",player.serverLevel().dimension().location().toString(),player.blockPosition())))player.closeContainer();
                try{
                    flushCapturedBlocks();scanAllBlockEntities(true);flushPendingBlockEntityWrites(true);
                    s.saveEverything(false,true,true);
                    CompletableFuture<Void> tail;synchronized(persistencePostLock){tail=persistenceTail;}
                    tail.orTimeout(20000,TimeUnit.MILLISECONDS).whenComplete((v,error)->ackCheckpoint(id,epoch,error==null&&failedWriteCount.get()==0?"":"Unconfirmed durable writes; source retained"));
                }catch(Exception ex){ackCheckpoint(id,epoch,ex.getMessage());}
            }
            sealedRegions.keySet().removeIf(region->!current.contains(region));
        });
    }
    private void ackCheckpoint(String id,long epoch,String error){
        post("/api/agent/region-checkpoint","{\"workerId\":\""+esc(workerId)+"\",\"persistenceInstance\":\""+persistenceInstance+"\",\"checkpointId\":\""+esc(id)+"\",\"epoch\":"+epoch+",\"error\":\""+esc(error==null?"Save failed":error)+"\"}");
    }

    private void awaitPersistencePosts(long timeoutMillis){
        CompletableFuture<Void> tail;
        synchronized(persistencePostLock){tail=persistenceTail;}
        try{tail.get(Math.max(1L,timeoutMillis),TimeUnit.MILLISECONDS);}
        catch(TimeoutException ex){LOG.warn("Persistent mutation commit queue still pending during shutdown after {}ms",timeoutMillis);}
        catch(InterruptedException ex){Thread.currentThread().interrupt();}
        catch(Exception ex){LOG.warn("Persistent mutation queue wait failed: {}",ex.getMessage());}
    }


    private void scanAllBlockEntities(boolean force){
        if(!persistenceEnabled||!blockEntityPersistenceEnabled||token.isBlank())return;
        MinecraftServer s=server;if(s==null)return;
        List<TrackedChunk> chunks=new ArrayList<>();
        for(var e:trackedChunks.entrySet())for(Long packed:e.getValue().keySet())chunks.add(new TrackedChunk(e.getKey(),packed));
        if(chunks.isEmpty())return;
        int start=(int)Math.floorMod(blockEntityScanCursor.getAndIncrement(),chunks.size());int used=0;
        for(int n=0;n<chunks.size();n++){
            if(!force&&used>=blockEntityScanBudget)break;
            TrackedChunk ref=chunks.get((start+n)%chunks.size());ServerLevel level=levelFor(ref.dimensionId());if(level==null)continue;
            try{
                LevelChunk chunk=level.getChunk(ChunkPos.getX(ref.packed()),ChunkPos.getZ(ref.packed()));
                if(!force&&!chunk.isUnsaved())continue;
                used+=scanChunkBlockEntities(level,chunk,force,force?Integer.MAX_VALUE:Math.max(1,blockEntityScanBudget-used),true);
            }catch(Exception ex){LOG.debug("BlockEntity scan skipped dim={} chunk={} error={}",ref.dimensionId(),ref.packed(),ex.getMessage());}
        }
        long cutoff=System.currentTimeMillis()-10000L;remoteBlockEntityPending.entrySet().removeIf(e->e.getValue()<cutoff);
    }

    private int scanChunkBlockEntities(ServerLevel level,LevelChunk chunk,boolean force,int budget,boolean detectRemovals){
        String region=regionIdForBlock("foundations",level.dimension().location().toString(),chunk.getPos().getWorldPosition());
        OwnedAuthority assigned=ownedAuthorities.get(region);
        if(assigned!=null&&!hydration.ready(region,assigned.epoch()))return 0;
        if(!force&&!chunk.isUnsaved())return 0;
        String dimension=level.dimension().location().toString();String chunkKey=blockEntityChunkKey(dimension,chunk.getPos().x,chunk.getPos().z);
        Map<BlockPos,BlockEntity> entities=new HashMap<>(chunk.getBlockEntities());Set<String> current=new HashSet<>();int processed=0;
        for(var entry:entities.entrySet()){
            if(!force&&processed>=budget)break;
            BlockPos pos=entry.getKey();BlockEntity be=entry.getValue();String key=blockEntityKey(dimension,pos);current.add(key);processed++;
            try{
                OwnedAuthority own=ownedAuthorityFor("foundations",dimension,pos);
                SimulationRoute route=own==null?routeForRegion(regionIdForBlock("foundations",dimension,pos),dimension):null;
                if(own==null&&(route==null||route.authorityWorkerId().equals(workerId)))continue;
                BlockEntityPayload payload=captureBlockEntityPayload(level,be);String digest=blockEntityFingerprint(payload);String previous=blockEntityFingerprints.get(key);
                if(own!=null){
                    if(force||!Objects.equals(previous,digest)){
                        if(force){pendingPersistentBlockEntities.remove(key);publishPersistentBlockEntity("","","foundations",dimension,own.regionId(),own.epoch(),payload);}
                        else queuePersistentBlockEntity("","","foundations",dimension,own.regionId(),own.epoch(),payload);
                        blockEntityFingerprints.put(key,digest);
                    }
                }else if(previous!=null&&!Objects.equals(previous,digest)){
                    long now=System.currentTimeMillis(),last=remoteBlockEntityPending.getOrDefault(key,0L);
                    if(now-last>=1000L){publishBlockEntityRequest(route,dimension,payload);remoteBlockEntityPending.put(key,now);}
                }
            }catch(Exception ex){LOG.warn("Could not capture BlockEntity {} at {}: {}",be.getClass().getName(),pos,ex.getMessage());}
        }
        if((force||processed>=entities.size())&&detectRemovals){
            Set<String> previous=blockEntityKeysByChunk.getOrDefault(chunkKey,Set.of());
            for(String missing:previous){
                if(current.contains(missing))continue;
                BlockPos pos=blockEntityPosFromKey(missing);if(pos==null)continue;
                String oldFingerprint=blockEntityFingerprints.get(missing);if(oldFingerprint==null)continue;
                OwnedAuthority own=ownedAuthorityFor("foundations",dimension,pos);
                SimulationRoute route=own==null?routeForRegion(regionIdForBlock("foundations",dimension,pos),dimension):null;
                BlockEntityPayload tombstone=new BlockEntityPayload(pos,false,"","","");
                if(own!=null){
                    if(force){pendingPersistentBlockEntities.remove(missing);publishPersistentBlockEntity("","","foundations",dimension,own.regionId(),own.epoch(),tombstone);}
                    else queuePersistentBlockEntity("","","foundations",dimension,own.regionId(),own.epoch(),tombstone);
                    blockEntityFingerprints.remove(missing);
                }
                else if(route!=null&&!route.authorityWorkerId().equals(workerId)){
                    long now=System.currentTimeMillis(),last=remoteBlockEntityPending.getOrDefault(missing,0L);
                    if(now-last>=1000L){publishBlockEntityRequest(route,dimension,tombstone);remoteBlockEntityPending.put(missing,now);}
                }
            }
            blockEntityKeysByChunk.put(chunkKey,Set.copyOf(current));
        }else if(force||processed>=entities.size())blockEntityKeysByChunk.put(chunkKey,Set.copyOf(current));
        return processed;
    }

    /** Runs on the server thread from the natural chunk-tick hook, before any random growth. */
    public static boolean allowNaturalChunkTick(ServerLevel level,LevelChunk chunk) {
        FoundationsClusterNode n=activeNode;
        if(n==null||n.token.isBlank()||!n.authorityRandomTicks)return true;
        String dimension=level.dimension().location().toString();
        String region=n.regionIdForBlock("foundations",dimension,chunk.getPos().getWorldPosition());
        OwnedAuthority own=n.ownedAuthorities.get(region);
        if(own==null||!n.growthAuthorityReady(region,own.epoch())) {n.suppressedGrowthTicks++;return false;}
        n.allowedGrowthTicks++;
        n.naturalChunkTicks.merge(n.replicaKey(dimension,chunk.getPos().x,chunk.getPos().z),1L,Long::sum);
        return true;
    }

    private boolean growthAuthorityReady(String region,long epoch) {
        OwnedAuthority own=ownedAuthorities.get(region);long now=System.nanoTime();
        return own!=null&&ownsActiveAuthority(region,epoch)
            &&own.leaseExpiresAtMillis()>System.currentTimeMillis()
            &&lastGrowthControlNanos!=0&&now-lastGrowthControlNanos<GROWTH_CONTROL_TIMEOUT_NANOS
            &&sealedRegions.getOrDefault(region,0L)<=System.currentTimeMillis();
    }

    private void retainRemoteGrowthTicket(ServerLevel level,int x,int z,String region,long epoch) {
        if(!authorityRandomTicks||token.isBlank()||!growthAuthorityReady(region,epoch))return;
        releaseExpiredRemoteGrowthTickets();
        var key=new RemoteTickLeases.Key(level.dimension().location().toString(),x,z);
        var result=remoteTickLeases.renew(key,region,epoch,System.nanoTime());
        if(result==RemoteTickLeases.Result.FULL) {
            long now=System.currentTimeMillis();
            if(now-lastGrowthCapacityLog>10000L){lastGrowthCapacityLog=now;LOG.warn("Remote growth ticket budget exhausted ({} chunks); snapshots continue but extra chunks cannot be kept ticking",remoteTickLimit);}
        } else if(result==RemoteTickLeases.Result.ADDED) {
            ChunkPos pos=new ChunkPos(x,z);
            try {
                // NeoForge forceTicks bypasses the nearby-player gate. Native ticking
                // remains single-pass even when real players also occupy the chunk.
                level.getChunkSource().addRegionTicket(REMOTE_GROWTH_TICKET,pos,2,pos,true);
                LOG.debug("Remote growth ticket acquired {} {},{} region={} epoch={}",key.dimension(),x,z,region,epoch);
            } catch(RuntimeException ex){remoteTickLeases.remove(key);throw ex;}
        }
    }

    private void releaseExpiredRemoteGrowthTickets() {
        if(server==null)return;
        for(var lease:remoteTickLeases.expire(System.nanoTime(),l->growthAuthorityReady(l.region(),l.epoch())))releaseRemoteGrowthTicket(lease);
    }
    private void releaseAllRemoteGrowthTickets() {
        for(var lease:remoteTickLeases.clear())releaseRemoteGrowthTicket(lease);
    }
    private void releaseRemoteGrowthTicket(RemoteTickLeases.Lease lease) {
        ServerLevel level=levelFor(lease.key().dimension());if(level==null)return;
        ChunkPos pos=new ChunkPos(lease.key().x(),lease.key().z());
        level.getChunkSource().removeRegionTicket(REMOTE_GROWTH_TICKET,pos,2,pos,true);
    }

    /** Read-only per-chunk evidence. Never loads a chunk or creates a ticket. */
    static String growthStatus(ServerLevel level,BlockPos pos) {
        FoundationsClusterNode n=activeNode;
        if(n==null)return "FOUNDATIONS_GROWTH unavailable";
        String dimension=level.dimension().location().toString();int x=Math.floorDiv(pos.getX(),16),z=Math.floorDiv(pos.getZ(),16);
        String region=n.regionIdForBlock("foundations",dimension,pos);OwnedAuthority own=n.ownedAuthorities.get(region);
        SimulationRoute remote=n.routeForRegion(region,dimension);
        var interest=n.remoteTickLeases.get(new RemoteTickLeases.Key(dimension,x,z));
        String key=n.replicaKey(dimension,x,z);
        boolean loaded=level.getChunkSource().getChunkNow(x,z)!=null;
        return "FOUNDATIONS_GROWTH node="+VERSION+" worker="+n.workerId+" region="+region
            +" role="+(n.token.isBlank()?"STANDALONE":own!=null?"AUTHORITY":remote!=null?"REPLICA":"UNASSIGNED")
            +" owner="+(own!=null?n.workerId:remote!=null?remote.authorityWorkerId():"unknown")
            +" epoch="+(own!=null?own.epoch():remote!=null?remote.authorityEpoch():0)
            +" authorityReady="+(own!=null&&n.growthAuthorityReady(region,own.epoch()))
            +" loaded="+loaded+" tickTicket="+(interest!=null)+" ticketChunks="+n.remoteTickLeases.size()
            +" randomTickSpeed="+level.getGameRules().getInt(net.minecraft.world.level.GameRules.RULE_RANDOMTICKING)
            +" chunkTicks="+n.naturalChunkTicks.getOrDefault(key,0L)
            +" chunkCaptured="+n.capturedChunkMutations.getOrDefault(key,0L)
            +" allowedTicks="+n.allowedGrowthTicks+" suppressedTicks="+n.suppressedGrowthTicks
            +" pendingWrites="+(n.pendingWriteCount.get()+n.changedBlocks.size()+n.pendingPersistentBlockEntities.size())
            +" failedWrites="+n.failedWriteCount.get()+" lastDurableWrite="+n.lastDurableWrite
            +" block="+(loaded?level.getBlockState(pos):"unloaded");
    }

    private OwnedAuthority ownedAuthorityFor(String worldId,String dimensionId,BlockPos pos){
        OwnedAuthority authority=ownedAuthorities.get(regionIdForBlock(worldId,dimensionId,pos));return authority!=null&&ownsActiveAuthority(authority.regionId(),authority.epoch())?authority:null;
    }

    private boolean ownsActiveAuthority(String regionId,long epoch){
        OwnedAuthority authority=ownedAuthorities.get(regionId);
        return authority!=null&&authority.active()&&authority.epoch()==epoch&&hydration.ready(regionId,epoch);
    }

    private void discardStalePendingPersistence(){
        int removed=0;
        for(var entry:new ArrayList<>(pendingPersistentBlockEntities.entrySet())){
            PendingPersistentBlockEntity pending=entry.getValue();
            if(ownsActiveAuthority(pending.regionId(),pending.authorityEpoch()))continue;
            if(pendingPersistentBlockEntities.remove(entry.getKey(),pending))removed++;
        }
        if(removed>0)LOG.debug("Discarded {} coalesced persistence write(s) fenced by an authority transition",removed);
    }

    private SimulationRoute routeForRegion(String regionId,String dimensionId){
        for(SimulationRoute route:simulationRoutes.values())if(route.regionId().equals(regionId)&&(route.dimensionId().isBlank()||route.dimensionId().equals(dimensionId)))return route;
        return null;
    }

    private String regionIdForBlock(String worldId,String dimensionId,BlockPos pos){
        int chunkX=Math.floorDiv(pos.getX(),16),chunkZ=Math.floorDiv(pos.getZ(),16);int rx=Math.floorDiv(chunkX,authorityRegionChunks),rz=Math.floorDiv(chunkZ,authorityRegionChunks);
        String world=worldId==null||worldId.isBlank()?"foundations":worldId;String dim=dimensionId==null||dimensionId.isBlank()?"minecraft:overworld":dimensionId;
        return world+"/"+dim+"/r."+rx+"."+rz;
    }

    private void updateOwnedAuthorities(String token){
        Map<String,OwnedAuthority> next=new HashMap<>();
        if(token!=null&&!token.isBlank())for(String row:token.split(";")){
            try{String[] p=row.split(",",-1);if(p.length<8)continue;OwnedAuthority a=new OwnedAuthority(unb64(p[0]),unb64(p[1]),Integer.parseInt(p[2]),Integer.parseInt(p[3]),Long.parseLong(p[4]),Long.parseLong(p[6]),p[7]);next.put(a.regionId(),a);}catch(Exception ignored){}
        }
        ownedAuthorities=Map.copyOf(next);
    }

    private static String blockEntityKey(String dimension,BlockPos pos){return dimension+"|"+pos.getX()+"|"+pos.getY()+"|"+pos.getZ();}
    private static String blockEntityChunkKey(String dimension,int chunkX,int chunkZ){return dimension+"|"+chunkX+"|"+chunkZ;}
    private static BlockPos blockEntityPosFromKey(String key){try{String[] p=key.split("\\|",-1);if(p.length<4)return null;return new BlockPos(Integer.parseInt(p[p.length-3]),Integer.parseInt(p[p.length-2]),Integer.parseInt(p[p.length-1]));}catch(Exception ex){return null;}}
    private static String blockEntityFingerprint(BlockEntityPayload payload){
        String material=(payload==null?"":payload.blockState()+"\n"+payload.nbtPayload());
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception ex){return Integer.toHexString(Objects.hashCode(material));}
    }

    private boolean chunkRegionMatches(String world,String dimension,String region,int chunkX,int chunkZ){
        return region.equals(world+"/"+dimension+"/r."+Math.floorDiv(chunkX,authorityRegionChunks)+"."+Math.floorDiv(chunkZ,authorityRegionChunks));
    }

    private void requestReplicaChunksSafe(){
        if(!chunkReplicationEnabled||token.isBlank())return;
        MinecraftServer s=server;if(s==null)return;
        long now=System.currentTimeMillis();
        if(now-lastChunkRequestSweepMillis<chunkRequestIntervalMillis)return;
        lastChunkRequestSweepMillis=now;
        int budget=maxChunkRequestsPerCycle;
        for(ServerPlayer player:s.getPlayerList().getPlayers()){
            if(budget<=0)break;
            SimulationRoute r=routeFor(player.getGameProfile().getName());
            if(r==null||r.authorityWorkerId().isBlank()||r.authorityWorkerId().equals(workerId))continue;
            String dimension=player.serverLevel().dimension().location().toString();
            if(!r.dimensionId().isBlank()&&!r.dimensionId().equals(dimension))continue;
            int centerX=Math.floorDiv((int)Math.floor(player.getX()),16),centerZ=Math.floorDiv((int)Math.floor(player.getZ()),16);
            int regionX=Math.floorDiv(centerX,authorityRegionChunks),regionZ=Math.floorDiv(centerZ,authorityRegionChunks);
            List<int[]> offsets=new ArrayList<>();
            for(int dx=-chunkSnapshotRadius;dx<=chunkSnapshotRadius;dx++)for(int dz=-chunkSnapshotRadius;dz<=chunkSnapshotRadius;dz++)offsets.add(new int[]{dx,dz});
            offsets.sort(Comparator.comparingInt(a->Math.abs(a[0])+Math.abs(a[1])));
            for(int[] off:offsets){
                if(budget<=0)break;
                int cx=centerX+off[0],cz=centerZ+off[1];
                if(Math.floorDiv(cx,authorityRegionChunks)!=regionX||Math.floorDiv(cz,authorityRegionChunks)!=regionZ||!chunkRegionMatches("foundations",dimension,r.regionId(),cx,cz))continue;
                String key=replicaKey(dimension,cx,cz);
                long applied=replicaChunkApplied.getOrDefault(key,0L),requested=replicaChunkRequested.getOrDefault(key,0L);
                long appliedEpoch=replicaChunkEpoch.getOrDefault(key,0L);
                if(appliedEpoch==r.authorityEpoch()&&now-applied<chunkCacheTtlMillis)continue;
                if(now-requested<chunkRequestIntervalMillis)continue;
                String payload=cx+"|"+cz+"|"+(int)Math.floor(player.getY())+"|"+chunkVerticalRadius;
                String body="{\"kind\":\"CHUNK_REQUEST\",\"sourceWorkerId\":\""+esc(workerId)+"\",\"targetWorkerId\":\""+esc(r.authorityWorkerId())+"\",\"sessionId\":\""+esc(r.sessionId())+"\",\"playerId\":\""+esc(r.playerId())+"\",\"worldId\":\"foundations\",\"dimensionId\":\""+esc(dimension)+"\",\"regionId\":\""+esc(r.regionId())+"\",\"authorityEpoch\":"+r.authorityEpoch()+",\"payload\":\""+esc(payload)+"\"}";
                post("/api/agent/dataplane/publish",body);
                replicaChunkRequested.put(key,now);budget--;
            }
        }
        long cutoff=now-Math.max(chunkCacheTtlMillis*6,60000);
        replicaChunkRequested.entrySet().removeIf(e->e.getValue()<cutoff);
        replicaChunkApplied.entrySet().removeIf(e->e.getValue()<cutoff);
        replicaChunkFingerprint.keySet().removeIf(k->!replicaChunkApplied.containsKey(k));
    }

    private void applyAuthorityChunkRequest(DataFrame f){
        if(!ownsActiveAuthority(f.regionId(),f.authorityEpoch())){LOG.debug("Ignoring fenced {} for region={} epoch={}",f.kind(),f.regionId(),f.authorityEpoch());return;}
        String[] p=f.payload().split("\\|",-1);if(p.length<4)throw new IllegalArgumentException("bad chunk request payload");
        int chunkX=Integer.parseInt(p[0]),chunkZ=Integer.parseInt(p[1]),centerY=Integer.parseInt(p[2]),verticalRadius=Math.max(16,Integer.parseInt(p[3]));
        if(!chunkRegionMatches(f.worldId(),f.dimensionId(),f.regionId(),chunkX,chunkZ))throw new IllegalArgumentException("Chunk request coordinates outside claimed authority region");
        ServerLevel level=levelFor(f.dimensionId());if(level==null)throw new IllegalStateException("dimension unavailable: "+f.dimensionId());
        retainRemoteGrowthTicket(level,chunkX,chunkZ,f.regionId(),f.authorityEpoch());
        level.getChunk(chunkX,chunkZ);
        int minY=Math.max(level.getMinBuildHeight(),centerY-verticalRadius),maxY=Math.min(level.getMaxBuildHeight(),centerY+verticalRadius+1);
        String payload=encodeChunkSnapshot(level,chunkX,chunkZ,minY,maxY);
        String body="{\"kind\":\"CHUNK_SNAPSHOT\",\"sourceWorkerId\":\""+esc(workerId)+"\",\"targetWorkerId\":\""+esc(f.sourceWorkerId())+"\",\"sessionId\":\""+esc(f.sessionId())+"\",\"playerId\":\""+esc(f.playerId())+"\",\"worldId\":\""+esc(f.worldId())+"\",\"dimensionId\":\""+esc(f.dimensionId())+"\",\"regionId\":\""+esc(f.regionId())+"\",\"authorityEpoch\":"+f.authorityEpoch()+",\"payload\":\""+esc(payload)+"\"}";
        post("/api/agent/dataplane/publish",body);
        LOG.debug("Published authoritative chunk snapshot {},{} y={}..{} for {} -> {}",chunkX,chunkZ,minY,maxY,f.playerId(),f.sourceWorkerId());
    }

    private String encodeChunkSnapshot(ServerLevel level,int chunkX,int chunkZ,int minY,int maxY){
        try{
            StringBuilder raw=new StringBuilder();raw.append(chunkX).append('\t').append(chunkZ).append('\t').append(minY).append('\t').append(maxY).append('\n');
            String last=null;int run=0;
            for(int y=minY;y<maxY;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++){
                String state=encodeBlockState(level.getBlockState(new BlockPos(chunkX*16+x,y,chunkZ*16+z)));
                if(Objects.equals(state,last))run++;else{if(last!=null)raw.append(run).append('\t').append(b64(last)).append('\n');last=state;run=1;}
            }
            if(last!=null)raw.append(run).append('\t').append(b64(last)).append('\n');
            List<BlockEntityPayload> blockEntities=new ArrayList<>();LevelChunk chunk=level.getChunk(chunkX,chunkZ);
            if(blockEntityPersistenceEnabled)for(BlockEntity be:chunk.getBlockEntities().values()){
                int y=be.getBlockPos().getY();if(y<minY||y>=maxY)continue;
                try{blockEntities.add(captureBlockEntityPayload(level,be));}catch(Exception ex){LOG.warn("Could not add BlockEntity {} to chunk snapshot: {}",be.getBlockPos(),ex.getMessage());}
            }
            raw.append("@BE\t").append(blockEntities.size()).append('\n');
            for(BlockEntityPayload be:blockEntities)raw.append("BE\t").append(be.pos().getX()).append('\t').append(be.pos().getY()).append('\t').append(be.pos().getZ()).append('\t').append(b64(be.typeId())).append('\t').append(b64(be.blockState())).append('\t').append(be.nbtPayload()).append('\n');
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(GZIPOutputStream gz=new GZIPOutputStream(bytes)){gz.write(raw.toString().getBytes(StandardCharsets.UTF_8));}
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
        }catch(IOException ex){throw new IllegalStateException("chunk snapshot compression failed",ex);}
    }

    private void applyReplicaChunkSnapshot(DataFrame f){
        ServerLevel level=levelFor(f.dimensionId());if(level==null)throw new IllegalStateException("dimension unavailable: "+f.dimensionId());
        String raw=decodeCompressedSnapshot(f.payload());String[] lines=raw.split("\\n");if(lines.length<2)throw new IllegalArgumentException("empty chunk snapshot");
        String[] h=lines[0].split("\\t");if(h.length<4)throw new IllegalArgumentException("bad chunk snapshot header");
        int chunkX=Integer.parseInt(h[0]),chunkZ=Integer.parseInt(h[1]),minY=Integer.parseInt(h[2]),maxY=Integer.parseInt(h[3]);
        if(!chunkRegionMatches(f.worldId(),f.dimensionId(),f.regionId(),chunkX,chunkZ)||ownedAuthorities.containsKey(f.regionId()))throw new IllegalArgumentException("Snapshot coordinates outside remote authority region");
        if(minY<level.getMinBuildHeight()||maxY>level.getMaxBuildHeight()||maxY<=minY)throw new IllegalArgumentException("Invalid snapshot height bounds");
        String replicaKey=replicaKey(f.dimensionId(),chunkX,chunkZ);
        String fingerprint=Integer.toHexString(raw.hashCode());
        if(replicaChunkEpoch.getOrDefault(replicaKey,0L)==f.authorityEpoch()&&Objects.equals(replicaChunkFingerprint.get(replicaKey),fingerprint)){
            replicaChunkApplied.put(replicaKey,System.currentTimeMillis());
            LOG.debug("Skipped unchanged authoritative replica chunk {},{} from {} epoch={}",chunkX,chunkZ,f.sourceWorkerId(),f.authorityEpoch());
            return;
        }
        int expected=(maxY-minY)*256,index=0,changed=0,lineIndex=1;level.getChunk(chunkX,chunkZ);
        while(lineIndex<lines.length&&index<expected){
            String line=lines[lineIndex++];if(line.isBlank())continue;if(line.startsWith("@BE"))throw new IllegalArgumentException("chunk snapshot ended block runs early");
            String[] run=line.split("\\t",2);if(run.length<2)throw new IllegalArgumentException("bad snapshot run");
            int count=Integer.parseInt(run[0]);BlockState state=decodeBlockState(unb64(run[1]));
            for(int n=0;n<count&&index<expected;n++,index++){
                int y=minY+(index/256),rem=index%256,z=rem/16,x=rem%16;BlockPos pos=new BlockPos(chunkX*16+x,y,chunkZ*16+z);
                if(!level.getBlockState(pos).equals(state)){level.setBlock(pos,state,2|16|32);changed++;}
            }
        }
        if(index!=expected)throw new IllegalArgumentException("chunk snapshot voxel count mismatch expected="+expected+" got="+index);
        int blockEntityCount=0;Set<String> snapshotKeys=new HashSet<>();
        if(lineIndex<lines.length&&lines[lineIndex].startsWith("@BE")){
            String[] marker=lines[lineIndex++].split("\\t",-1);int declared=marker.length>1?Integer.parseInt(marker[1]):0;
            for(int n=0;n<declared&&lineIndex<lines.length;n++){
                String line=lines[lineIndex++];if(line.isBlank()){n--;continue;}String[] p=line.split("\\t",-1);if(p.length<6||!p[0].equals("BE"))throw new IllegalArgumentException("bad BlockEntity snapshot row");
                BlockPos pos=new BlockPos(Integer.parseInt(p[1]),Integer.parseInt(p[2]),Integer.parseInt(p[3]));BlockEntityPayload be=p.length>=7?new BlockEntityPayload(pos,true,unb64(p[4]),unb64(p[5]),p[6]):new BlockEntityPayload(pos,true,unb64(p[4]),"",p[5]);
                if(Math.floorDiv(pos.getX(),16)!=chunkX||Math.floorDiv(pos.getZ(),16)!=chunkZ||pos.getY()<minY||pos.getY()>=maxY)throw new IllegalArgumentException("BlockEntity outside snapshot chunk");
                applyBlockEntityPayload(level,be);snapshotKeys.add(blockEntityKey(f.dimensionId(),pos));blockEntityCount++;
            }
        }
        String beChunkKey=blockEntityChunkKey(f.dimensionId(),chunkX,chunkZ);Set<String> previous=blockEntityKeysByChunk.getOrDefault(beChunkKey,Set.of());
        for(String old:previous)if(!snapshotKeys.contains(old)){BlockPos pos=blockEntityPosFromKey(old);if(pos!=null&&pos.getY()>=minY&&pos.getY()<maxY)applyBlockEntityPayload(level,new BlockEntityPayload(pos,false,"","",""));}
        blockEntityKeysByChunk.put(beChunkKey,Set.copyOf(snapshotKeys));
        String key=replicaKey(f.dimensionId(),chunkX,chunkZ);replicaChunkApplied.put(key,System.currentTimeMillis());replicaChunkEpoch.put(key,f.authorityEpoch());replicaChunkFingerprint.put(key,fingerprint);
        if(changed>0)LOG.info("Applied authoritative replica chunk {},{} from {} epoch={} changedBlocks={} blockEntities={} y={}..{}",chunkX,chunkZ,f.sourceWorkerId(),f.authorityEpoch(),changed,blockEntityCount,minY,maxY);
        else LOG.debug("Replica chunk {},{} already block-current from {} epoch={} blockEntities={}",chunkX,chunkZ,f.sourceWorkerId(),f.authorityEpoch(),blockEntityCount);
    }

    private String decodeCompressedSnapshot(String payload){
        try{
            byte[] compressed=Base64.getUrlDecoder().decode(payload);ByteArrayOutputStream out=new ByteArrayOutputStream();
            try(GZIPInputStream gz=new GZIPInputStream(new ByteArrayInputStream(compressed))){gz.transferTo(out);}
            return out.toString(StandardCharsets.UTF_8);
        }catch(Exception ex){throw new IllegalArgumentException("chunk snapshot decode failed: "+ex.getMessage(),ex);}
    }

    private void applyReplicaChunkInvalidate(DataFrame f){
        String[] p=f.payload().split("\\|",-1);if(p.length<2)return;int chunkX=Integer.parseInt(p[0]),chunkZ=Integer.parseInt(p[1]);
        String key=replicaKey(f.dimensionId(),chunkX,chunkZ);replicaChunkApplied.remove(key);replicaChunkEpoch.remove(key);replicaChunkFingerprint.remove(key);
        String beChunkKey=blockEntityChunkKey(f.dimensionId(),chunkX,chunkZ);Set<String> oldKeys=blockEntityKeysByChunk.remove(beChunkKey);
        if(oldKeys!=null)for(String old:oldKeys){blockEntityFingerprints.remove(old);remoteBlockEntityPending.remove(old);blockEntityLastPersistedAt.remove(old);}
    }

    private static String replicaKey(String dimension,int chunkX,int chunkZ){return dimension+"|"+chunkX+"|"+chunkZ;}

    private ServerLevel levelFor(String dimension){
        MinecraftServer s=server;if(s==null)return null;ResourceLocation rl=ResourceLocation.tryParse(dimension);return rl==null?s.overworld():s.getLevel(ResourceKey.create(Registries.DIMENSION,rl));
    }

    private void ackDataPlane(String frameId,boolean ok,String error){
        post("/api/agent/dataplane/ack","{\"workerId\":\""+esc(workerId)+"\",\"frameId\":\""+esc(frameId)+"\",\"ok\":"+ok+",\"error\":\""+esc(error)+"\"}");
    }

    private String encodeBlockState(BlockState state){
        StringBuilder b=new StringBuilder(String.valueOf(BuiltInRegistries.BLOCK.getKey(state.getBlock())));
        for(Property<?> property:state.getProperties())b.append(';').append(property.getName()).append('=').append(propertyValueName(state,property));
        return b.toString();
    }

    private BlockState decodeBlockState(String text){
        if(text==null||text.isBlank())throw new IllegalArgumentException("Empty block state");
        String[] fields=text.split(";",-1);ResourceLocation id=ResourceLocation.tryParse(fields[0]);
        if(id==null||!BuiltInRegistries.BLOCK.containsKey(id))throw new IllegalArgumentException("Unknown block: "+fields[0]);
        BlockState state=BuiltInRegistries.BLOCK.get(id).defaultBlockState();Set<String> names=new HashSet<>();
        for(int i=1;i<fields.length;i++){
            int eq=fields[i].indexOf('=');if(eq<1)throw new IllegalArgumentException("Invalid block property: "+fields[i]);
            String name=fields[i].substring(0,eq),value=fields[i].substring(eq+1);Property<?> property=state.getBlock().getStateDefinition().getProperty(name);
            if(property==null||!names.add(name)||property.getValue(value).isEmpty())throw new IllegalArgumentException("Unknown/duplicate block property: "+fields[i]);
            state=withProperty(state,property,value);
        }
        return state;
    }
    private static <T extends Comparable<T>> String propertyValueName(BlockState state,Property<T> property){return property.getName(state.getValue(property));}
    private static <T extends Comparable<T>> BlockState withProperty(BlockState state,Property<T> property,String value){return property.getValue(value).map(v->state.setValue(property,v)).orElse(state);}
    private static String b64(String s){return Base64.getUrlEncoder().withoutPadding().encodeToString((s==null?"":s).getBytes(StandardCharsets.UTF_8));}
    private static String unb64(String s){if(s==null||s.isBlank())return "";return new String(Base64.getUrlDecoder().decode(s),StandardCharsets.UTF_8);}

    private void calculatePercentiles() {
        if (tickSampleCount == 0) return;
        double[] copy = Arrays.copyOf(tickSamples, tickSampleCount);
        Arrays.sort(copy);
        p95Mspt = percentile(copy, 0.95);
        p99Mspt = percentile(copy, 0.99);
    }

    private static double percentile(double[] values, double p) {
        if (values.length == 0) return 0;
        int index = (int)Math.ceil(p * values.length) - 1;
        return values[Math.max(0, Math.min(values.length - 1, index))];
    }

    private String registerBody() {
        long seed = currentWorldSeed();
        return "{\"workerId\":\""+esc(workerId)+"\",\"port\":"+port+",\"warm\":"+warm+",\"ready\":true"
                +",\"nodeVersion\":\""+VERSION+"\",\"minecraftVersion\":\""+esc(minecraftVersion())+"\",\"javaVersion\":\""+esc(System.getProperty("java.version", ""))+"\""
                +",\"players\":"+players+",\"maxPlayers\":"+maxPlayers+",\"loadedChunks\":"+loadedChunks+",\"dimensions\":"+dimensions
                +",\"worldSeed\":"+seed+",\"worldSeedMatch\":"+(!expectedWorldSeedKnown || seed==expectedWorldSeed)+",\"worldIdentity\":\""+esc(actualWorldIdentity)+"\",\"worldIdentitySchema\":"+WORLD_IDENTITY_SCHEMA+",\"worldIdentityMatch\":"+(expectedWorldIdentity.isBlank() || expectedWorldIdentitySchema!=WORLD_IDENTITY_SCHEMA || expectedWorldIdentity.equals(actualWorldIdentity))
                +",\"playerStates\":\""+esc(playerStateBlob)+"\""+worldEnvironment.reportJson()+",\"hydratedRegions\":\""+hydration.report()+"\",\"persistenceInstance\":\""+persistenceInstance+"\",\"pendingWrites\":"+(pendingWriteCount.get()+changedBlocks.size()+pendingPersistentBlockEntities.size())+",\"failedWrites\":"+failedWriteCount.get()+",\"lastDurableWrite\":"+lastDurableWrite+"}";
    }

    private void heartbeat() {
        if (server == null) return;
        Runtime r = Runtime.getRuntime();
        long used = r.totalMemory() - r.freeMemory();
        long max = r.maxMemory();
        long uptime = ManagementFactory.getRuntimeMXBean().getUptime();
        int threads = ManagementFactory.getThreadMXBean().getThreadCount();
        long seed = currentWorldSeed();
        String body = "{\"workerId\":\""+esc(workerId)+"\",\"port\":"+port+",\"players\":"+players+",\"maxPlayers\":"+maxPlayers
                +",\"mspt\":"+String.format(Locale.ROOT,"%.3f",emaMspt)+",\"msptP95\":"+String.format(Locale.ROOT,"%.3f",p95Mspt)+",\"msptP99\":"+String.format(Locale.ROOT,"%.3f",p99Mspt)
                +",\"heapUsed\":"+used+",\"heapMax\":"+max+",\"loadedChunks\":"+loadedChunks+",\"dimensions\":"+dimensions+",\"threadCount\":"+threads+",\"uptimeMillis\":"+uptime
                +",\"javaVersion\":\""+esc(System.getProperty("java.version", ""))+"\",\"minecraftVersion\":\""+esc(minecraftVersion())+"\",\"nodeVersion\":\""+VERSION+"\""
                +",\"warm\":"+warm+",\"ready\":true,\"worldSeed\":"+seed+",\"worldSeedMatch\":"+(!expectedWorldSeedKnown || seed==expectedWorldSeed)+",\"worldIdentity\":\""+esc(actualWorldIdentity)+"\",\"worldIdentitySchema\":"+WORLD_IDENTITY_SCHEMA+",\"worldIdentityMatch\":"+(expectedWorldIdentity.isBlank() || expectedWorldIdentitySchema!=WORLD_IDENTITY_SCHEMA || expectedWorldIdentity.equals(actualWorldIdentity))
                +",\"playerStates\":\""+esc(playerStateBlob)+"\""+worldEnvironment.reportJson()+",\"hydratedRegions\":\""+hydration.report()+"\",\"persistenceInstance\":\""+persistenceInstance+"\",\"pendingWrites\":"+(pendingWriteCount.get()+changedBlocks.size()+pendingPersistentBlockEntities.size())+",\"failedWrites\":"+failedWriteCount.get()+",\"lastDurableWrite\":"+lastDurableWrite+"}";
        post("/api/agent/heartbeat", body);
    }

    /**
     * Stable, cheap world-compatibility fingerprint. It intentionally avoids hashing every mod JAR.
     * Mod IDs+versions, selected datapacks, seed, Minecraft version, and worldgen-relevant config files
     * are enough to detect the accidental divergence this guard is designed to stop.
     */
    private String calculateWorldIdentity() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digestText(digest, "seed=" + currentWorldSeed());
            digestText(digest, "minecraft=" + minecraftVersion());

            try {
                List<String> mods = ModList.get().getMods().stream()
                        .filter(info -> !isRuntimeInfrastructureMod(info.getModId()))
                        .map(info -> info.getModId() + "=" + String.valueOf(info.getVersion()))
                        .sorted()
                        .toList();
                for (String mod : mods) digestText(digest, "mod=" + mod);
            } catch (Throwable t) {
                LOG.warn("Could not include loaded mod versions in cluster world identity: {}", t.getMessage());
            }

            MinecraftServer s = server;
            if (s != null) {
                try {
                    List<String> packs = new ArrayList<>(s.getPackRepository().getSelectedIds());
                    Collections.sort(packs);
                    for (String pack : packs) digestText(digest, "pack=" + pack);
                } catch (Throwable t) {
                    LOG.warn("Could not include selected datapacks in cluster world identity: {}", t.getMessage());
                }

                try { hashIdentityTree(digest, s.getWorldPath(LevelResource.DATAPACK_DIR), true); }
                catch (Throwable t) { LOG.warn("Could not fingerprint world datapack files: {}", t.getMessage()); }
                try { hashIdentityTree(digest, s.getWorldPath(LevelResource.ROOT).resolve("serverconfig"), false); }
                catch (Throwable t) { LOG.warn("Could not fingerprint per-world serverconfig files: {}", t.getMessage()); }
            }

            try { hashIdentityTree(digest, java.nio.file.Path.of("config").toAbsolutePath().normalize(), false); }
            catch (Throwable t) { LOG.warn("Could not fingerprint worldgen config files: {}", t.getMessage()); }

            String hex = java.util.HexFormat.of().formatHex(digest.digest());
            return hex.substring(0, Math.min(32, hex.length()));
        } catch (Exception e) {
            LOG.warn("Could not calculate cluster world identity: {}", e.getMessage());
            return "identity-error-" + Long.toHexString(currentWorldSeed());
        }
    }

    private void hashIdentityTree(MessageDigest digest, java.nio.file.Path root, boolean includeAll) throws IOException {
        if (root == null || !java.nio.file.Files.exists(root)) return;
        Set<String> keywords = new HashSet<>();
        for (String raw : worldIdentityKeywords.split(",")) {
            String k = raw.trim().toLowerCase(Locale.ROOT);
            if (!k.isBlank()) keywords.add(k);
        }
        try (var stream = java.nio.file.Files.walk(root)) {
            List<java.nio.file.Path> files = stream.filter(java.nio.file.Files::isRegularFile).sorted().toList();
            for (java.nio.file.Path file : files) {
                String rel = root.relativize(file).toString().replace('\\', '/');
                String lower = rel.toLowerCase(Locale.ROOT);
                if (!includeAll && keywords.stream().noneMatch(lower::contains)) continue;
                long size = java.nio.file.Files.size(file);
                digestText(digest, "file=" + rel + ":" + size);
                // Config/datapack files are normally small. Hash content up to 16 MiB; for a larger
                // archive the name+size still gives a useful compatibility signal without delaying boot.
                if (size <= 16L * 1024L * 1024L) {
                    try (InputStream in = java.nio.file.Files.newInputStream(file)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) digest.update(buf, 0, n);
                    }
                }
            }
        }
    }

    private static boolean isRuntimeInfrastructureMod(String modId) {
        if (modId == null) return false;
        String id = modId.toLowerCase(Locale.ROOT);
        return id.equals(MOD_ID) || id.equals("proxy_compatible_forge") || id.equals("proxy-compatible-forge")
                || id.equals("velocity_support") || id.equals("velocity-support");
    }

    private static void digestText(MessageDigest digest, String text) {
        digest.update(text.getBytes(StandardCharsets.UTF_8));
        digest.update((byte)'\n');
    }

    private long currentWorldSeed() {
        MinecraftServer s = server;
        if (s == null) return 0L;
        try { return s.overworld().getSeed(); } catch (Exception e) { return 0L; }
    }

    private String minecraftVersion() {
        MinecraftServer s = server;
        try { return s == null ? "1.21.1" : s.getServerVersion(); }
        catch (Exception e) { return "1.21.1"; }
    }

    private void post(String path, String body) {
        postForBody(path, body, response -> {});
    }

    private void postForBody(String path, String body, Consumer<String> success) {
        long sequence=(path.endsWith("heartbeat")||path.endsWith("register"))?controlRequestSequence.incrementAndGet():0;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(manager + path))
                    .timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/json")
                    .header("X-Foundations-Agent-Token", token)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            client.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(resp -> {
                        if (resp.statusCode() >= 300) {
                            if(resp.statusCode()==409&&path.endsWith("/dataplane/publish")){
                                long n=dataplaneFenceRejects.incrementAndGet(),now=System.currentTimeMillis();
                                if(now-lastDataplaneFenceLogMillis>=10000L){lastDataplaneFenceLogMillis=now;LOG.debug("Data-plane fencing rejected {} stale publish(es) during authority transition",n);dataplaneFenceRejects.set(0L);}
                            } else LOG.warn("Cluster Manager {} returned HTTP {}", path, resp.statusCode());
                        } else {
                            if (path.endsWith("heartbeat") || path.endsWith("register")) applyControl(resp.body(),sequence);
                            success.accept(resp.body());
                        }
                    })
                    .exceptionally(ex -> { LOG.warn("Cluster Manager {} failed: {}", path, ex.getMessage()); return null; });
        } catch (Exception ex) { LOG.warn("Cluster Manager request failed: {}", ex.getMessage()); }
    }

    private void applyControl(String body,long sequence) {
        MinecraftServer current=server;if(current==null)return;
        if(!current.isSameThread()){current.execute(()->applyControl(body,sequence));return;}
        if(sequence<=lastControlSequence)return;lastControlSequence=sequence;
        lastGrowthControlNanos=System.nanoTime();
        worldEnvironment.accept(jsonBoolean(body,"worldEnvironmentEnabled",false),jsonString(body,"worldEnvironmentOwner"),
            (long)jsonDouble(body,"worldEnvironmentEpoch",0),(long)jsonDouble(body,"worldEnvironmentRevision",0),
            (long)jsonDouble(body,"worldEnvironmentLeaseMillis",0),jsonString(body,"worldEnvironment"));
        boolean newDraining = jsonBoolean(body,"draining",false);
        boolean newMaintenance = jsonBoolean(body,"maintenance",false);
        if (newDraining != draining) { draining = newDraining; LOG.info("Cluster routing state changed: draining={}", draining); }
        if (newMaintenance != maintenance) { maintenance = newMaintenance; LOG.info("Cluster routing state changed: maintenance={}", maintenance); }
        String newLayerIds = jsonString(body, "layerIds");
        if (!Objects.equals(newLayerIds, assignedLayerIds)) {
            assignedLayerIds = newLayerIds;
            LOG.info("Cluster population layers assigned to this worker: {}", assignedLayerIds.isBlank() ? "none" : assignedLayerIds);
        }
        String newAuthority = jsonString(body,"authorityRegions");
        String authorityFingerprint=authorityFingerprint(newAuthority);
        assignedAuthorityRegions=newAuthority;
        updateOwnedAuthorities(newAuthority);
        if(!Objects.equals(authorityFingerprint,assignedAuthorityFingerprint)&&!pendingPersistentBlockEntities.isEmpty())discardStalePendingPersistence();
        if(!Objects.equals(authorityFingerprint,assignedAuthorityFingerprint)){
            assignedAuthorityFingerprint=authorityFingerprint;
            pendingBlockEntityApplies.clear();
            LOG.info("Region Authority assignments changed: {}",newAuthority.isBlank()?"none":authorityFingerprint);
        }
        Map<String,Long> epochs=new HashMap<>();ownedAuthorities.forEach((id,owner)->epochs.put(id,owner.epoch()));
        hydration.assign(epochs);
        if(!persistenceEnabled&&!hydration.pending().isEmpty())hydration.complete(hydration.pending());
        if(!hydration.pending().isEmpty())requestPersistenceReplay();
        boolean enforced=jsonBoolean(body,"writeFencingEnforced",false);
        if(enforced!=writeFencingEnforced){writeFencingEnforced=enforced;LOG.info("Region write fencing enforced={}",writeFencingEnforced);}
        String routeToken=jsonString(body,"simulationRoutes");
        if(!Objects.equals(routeToken,simulationRouteToken)){simulationRouteToken=routeToken;updateSimulationRoutes(routeToken);}
        handleCheckpointControl(jsonString(body,"regionCheckpoints"));
        handleHandoffControl(body);
    }

    private static String authorityFingerprint(String token){
        if(token==null||token.isBlank())return "";List<String> rows=new ArrayList<>();
        for(String row:token.split(";")){
            String[] p=row.split(",",-1);if(p.length<8)continue;
            try{rows.add(unb64(p[0])+"/"+unb64(p[1])+"/r."+p[2]+"."+p[3]+"@"+p[4]);}catch(Exception ignored){}
        }
        Collections.sort(rows);return String.join(";",rows);
    }

    private static String field(String s){return Base64.getUrlEncoder().withoutPadding().encodeToString((s==null?"":s).getBytes(StandardCharsets.UTF_8));}
    private static String jsonString(String body, String key) {
        if (body == null) return "";
        String needle = "\"" + key + "\":\"";
        int start = body.indexOf(needle);
        if (start < 0) return "";
        start += needle.length();
        StringBuilder out = new StringBuilder(); boolean esc = false;
        for (int i=start;i<body.length();i++) {
            char c=body.charAt(i);
            if (esc) { out.append(c); esc=false; }
            else if (c=='\\') esc=true;
            else if (c=='\"') break;
            else out.append(c);
        }
        return out.toString();
    }
    private static double jsonDouble(String body,String key,double def){
        if(body==null)return def;String needle="\""+key+"\":";int i=body.indexOf(needle);if(i<0)return def;i+=needle.length();int j=i;while(j<body.length()&&"-+.0123456789Ee".indexOf(body.charAt(j))>=0)j++;try{return Double.parseDouble(body.substring(i,j));}catch(Exception e){return def;}
    }
    private static boolean jsonBoolean(String body,String key,boolean def){if(body==null)return def;String needle="\""+key+"\":";int i=body.indexOf(needle);if(i<0)return def;i+=needle.length();return body.startsWith("true",i)?true:body.startsWith("false",i)?false:def;}
    private static String env(String k, String d) { String v = System.getenv(k); return v == null || v.isBlank() ? d : v; }
    private static int parseInt(String s, int d) { try { return Integer.parseInt(s); } catch (Exception e) { return d; } }
    private static long parseLong(String s, long d) { try { return Long.parseLong(s); } catch (Exception e) { return d; } }
    private static String esc(String s) { return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n","\\n").replace("\r","\\r"); }
}
