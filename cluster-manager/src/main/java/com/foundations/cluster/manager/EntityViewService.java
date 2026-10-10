package com.foundations.cluster.manager;

import com.foundations.cluster.protocol.EntityViewsWire;
import com.foundations.cluster.protocol.EntityViewsWire.*;
import java.io.*;
import java.util.*;

/** Authenticated agent-facing presentation service. Uses the cluster's existing
 * trusted-worker token model; a process instance is a replay fence, not a secret. */
final class EntityViewService implements AutoCloseable {
    private final ManagerConfig cfg;
    private final WorkerManager workers;
    private final SessionManager sessions;
    private final RegionAuthorityManager regions;
    private final EntityViewRegistry registry;
    EntityViewService(ManagerConfig cfg,WorkerManager workers,SessionManager sessions,RegionAuthorityManager regions)throws IOException {
        this.cfg=cfg;this.workers=workers;this.sessions=sessions;this.regions=regions;
        registry=new EntityViewRegistry(cfg.home().resolve("runtime"));
    }
    private EntityViewRegistry.Peer peer(Map<String,String> m) {
        String id=m.getOrDefault("workerId","");WorkerRecord w=workers.get(id);
        if(w==null||!healthy(w)||!w.persistenceInstance.equals(m.getOrDefault("instance","")))
            throw new SecurityException("Worker telemetry/identity/version or process instance is not current");
        return new EntityViewRegistry.Peer(id,w.persistenceInstance,w.worldIdentity,w.worldSeed);
    }
    private boolean healthy(WorkerRecord w) {
        return w.usableForExistingSession() && w.heartbeatAgeSeconds()>=0 && w.heartbeatAgeSeconds()<8 &&
            w.worldSeedKnown && w.worldSeedMatch && w.worldIdentityMatch && !w.worldIdentity.isBlank() &&
            w.minecraftVersion.equals("1.21.1") && w.nodeVersion.equals(ClusterVersion.NODE_VERSION) && !w.persistenceInstance.isBlank();
    }
    private boolean healthy(EntityViewRegistry.Peer p) {
        WorkerRecord w=workers.get(p.worker());return w!=null&&healthy(w)&&w.persistenceInstance.equals(p.instance())&&
            w.worldIdentity.equals(p.identity())&&w.worldSeed==p.seed();
    }
    private SessionRecord anchored(String worker,String name) {
        SessionRecord s=sessions.findByPlayer(name);
        if(s==null||!worker.equals(s.anchorWorkerId))throw new SecurityException("Connected operator must be on the requesting worker");
        return s;
    }
    String handle(Map<String,String> m)throws IOException {
        cfg.lifecycle.requireRunning();var peer=peer(m);
        registry.invalidateUnavailable(this::healthy);
        switch(m.getOrDefault("action","")) {
            case "exchange": {
                var request=EntityViewsWire.decodeRequest(unbase64(m.getOrDefault("bundle","")));
                Set<String> names=new HashSet<>();
                for(Player p:request.players()) {
                    SessionRecord s=sessions.findByPlayer(p.name());
                    if(s!=null&&peer.worker().equals(s.anchorWorkerId)&&s.worldId.equals("foundations"))names.add(p.name().toLowerCase(Locale.ROOT));
                }
                Response response=registry.exchange(peer,request,names);
                return "{\"bundle\":"+Json.q(Base64.getUrlEncoder().withoutPadding().encodeToString(EntityViewsWire.encodeResponse(response)))+"}";
            }
            case "pair": {
                anchored(peer.worker(),m.getOrDefault("operatorName",""));
                var request=EntityViewsWire.decodeRequest(unbase64(m.getOrDefault("bundle","")));
                if(request.states().size()!=1||!request.players().isEmpty())throw new IllegalArgumentException("Pair needs one source entity");
                State state=request.states().getFirst();
                RegionRecord region=regions.get(regions.forBlock("foundations",state.dimension(),state.x(),state.z()).id());
                if(region==null||region.state!=RegionState.ACTIVE||!peer.worker().equals(region.ownerWorkerId)||System.currentTimeMillis()>=region.leaseExpiresAtMillis)
                    throw new IllegalStateException("Pair the entity on its ACTIVE region owner first");
                Pair p=registry.pair(peer,state);
                return "{\"ok\":true,\"binding\":"+Json.q(p.binding().toString())+",\"entity\":"+Json.q(p.entity().toString())+"}";
            }
            case "unpair": {
                anchored(peer.worker(),m.getOrDefault("operatorName",""));
                return "{\"ok\":true,\"removed\":"+registry.unpair(peer,UUID.fromString(m.getOrDefault("entity","")))+"}";
            }
            case "view": {
                anchored(peer.worker(),m.getOrDefault("operatorName",""));
                String enabled=m.getOrDefault("enabled","");if(!enabled.equals("true")&&!enabled.equals("false"))throw new IllegalArgumentException("Invalid view switch");
                registry.view(peer,UUID.fromString(m.getOrDefault("playerUuid","")),Boolean.parseBoolean(enabled));
                return "{\"ok\":true,\"displayOnly\":true}";
            }
            case "list": {
                anchored(peer.worker(),m.getOrDefault("operatorName",""));
                var pairs=registry.pairs().stream().filter(peer::sameWorld).toList();
                return "{\"pairs\":"+Json.q(Base64.getUrlEncoder().withoutPadding().encodeToString(EntityViewsWire.encodePairs(pairs)))+"}";
            }
            default:throw new IllegalArgumentException("Unknown entity-view action");
        }
    }
    static byte[] unbase64(String text)throws IOException {
        if(text.length()>(EntityViewsWire.MAX_BYTES*4L+2)/3)throw new IOException("Entity-view body exceeds limit");
        try{return Base64.getUrlDecoder().decode(text);}catch(IllegalArgumentException e){throw new IOException("Invalid entity-view base64",e);}
    }
    String status()throws IOException {
        registry.invalidateUnavailable(this::healthy);
        return "{\"scope\":\"paired-display-only\",\"entityOwnershipTransfer\":false,\"remotePickup\":false,\"paired\":"+registry.pairs().size()+",\"fresh\":"+registry.freshCount()+",\"optedInViewers\":"+registry.viewerCount()+"}";
    }
    @Override public void close(){registry.close();}
}
