package com.foundations.cluster.manager;

import com.foundations.cluster.protocol.EntityViewsWire;
import com.foundations.cluster.protocol.EntityViewsWire.*;
import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.function.LongSupplier;
import java.util.function.BiPredicate;

/** Durable *pair selections*, transient presentation frames. NOT entity storage,
 * an ownership arbiter, a failover source, or permission to recreate an entity.
 * The Manager's existing process lock protects this registry's single writer. */
final class EntityViewRegistry implements AutoCloseable {
    record Peer(String worker, String instance, String identity, long seed) {
        Peer { EntityViewsWire.token(worker,"worker");UUID.fromString(instance);EntityViewsWire.token(identity,"identity"); }
        boolean sameWorld(Pair p) { return identity.equals(p.identity()) && seed==p.seed(); }
        String realm(){return identity+":"+seed;}
    }
    private record Live(Pair pair,State state,Peer source,long sequence,long received) {}
    private record Seen(Peer peer,Player player,long at) {}
    private record Consent(String realm,long expires) {}
    private record Counter(String instance,long sequence) {}
    private final Path file;
    private final LongSupplier clock;
    private final BiPredicate<Peer,State> validatesActiveAuthority;
    private final UUID boot=UUID.randomUUID();
    private Map<UUID,Pair> pairs=new LinkedHashMap<>();
    private final Map<UUID,Live> live=new HashMap<>();
    private final Map<UUID,Consent> consent=new HashMap<>();
    private final Map<String,List<Seen>> positions=new HashMap<>();
    private final Map<String,Counter> counters=new HashMap<>();
    private boolean closed,failed;
    EntityViewRegistry(Path directory)throws IOException { this(directory,()->System.nanoTime()/1000000L,(peer,state)->false); }
    EntityViewRegistry(Path directory,LongSupplier clock)throws IOException {this(directory,clock,(peer,state)->false);}
    EntityViewRegistry(Path directory,LongSupplier clock,BiPredicate<Peer,State> authority)throws IOException {
        this.clock=clock;this.validatesActiveAuthority=Objects.requireNonNull(authority);
        if(Files.isSymbolicLink(directory))throw new IOException("Entity-view directory cannot be a symbolic link");
        Files.createDirectories(directory);file=directory.resolve("paired-entity-views.bin");
        if(Files.exists(file,LinkOption.NOFOLLOW_LINKS)) {
            byte[] all;
            try(var channel=FileChannel.open(file,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
                if(channel.size()<32||channel.size()>32768)throw new IOException("Invalid paired-view registry size");
                var buffer=ByteBuffer.allocate((int)channel.size());while(buffer.hasRemaining())if(channel.read(buffer)<=0)throw new EOFException();all=buffer.array();
            }
            byte[] data=Arrays.copyOf(all,all.length-32);
            if(!MessageDigest.isEqual(digest(data),Arrays.copyOfRange(all,all.length-32,all.length)))throw new IOException("Paired-view registry checksum mismatch; original preserved");
            for(Pair pair:EntityViewsWire.decodePairs(data)) {
                if(pair.interested() || pairs.values().stream().anyMatch(p->p.entity().equals(pair.entity())&&p.identity().equals(pair.identity())&&p.seed()==pair.seed()))
                    throw new IOException("Duplicate persisted entity pair");
                pairs.put(pair.binding(),pair);
            }
            for(Pair pair:pairs.values())if(pairs.values().stream().filter(p->p.worker().equals(pair.worker())).count()>EntityViewsWire.MAX_SOURCE_PAIRS)
                throw new IOException("Persisted source pair limit exceeded");
        }
    }
    synchronized Pair pair(Peer peer,State initial)throws IOException {
        available();
        Pair old=pairs.values().stream().filter(p->peer.sameWorld(p)&&p.entity().equals(initial.entity())).findFirst().orElse(null);
        if(old!=null){
            if(!old.worker().equals(peer.worker)||!old.dimension().equals(initial.dimension())||old.kind()!=initial.kind())
                throw new IllegalStateException("UUID already paired to a different source/dimension; unpair explicitly, never guess or merge");
            return old;
        }
        if(pairs.size()>=EntityViewsWire.MAX_PAIRS || pairs.values().stream().filter(p->p.worker().equals(peer.worker)).count()>=EntityViewsWire.MAX_SOURCE_PAIRS)
            throw new IllegalStateException("Pair limit reached (32 total; 8 per source)");
        Pair p=new Pair(UUID.randomUUID(),initial.entity(),peer.worker,peer.identity,peer.seed,initial.dimension(),initial.kind(),false);
        var next=new LinkedHashMap<>(pairs);next.put(p.binding(),p);persist(next);pairs=next;return p;
    }
    synchronized boolean unpair(Peer peer,UUID entity)throws IOException {
        available();var next=new LinkedHashMap<>(pairs);boolean removed=next.values().removeIf(p->peer.sameWorld(p)&&p.entity().equals(entity));
        if(removed){persist(next);pairs=next;live.keySet().retainAll(pairs.keySet());}return removed;
    }
    synchronized void view(Peer peer,UUID player,boolean enabled)throws IOException {
        available();prune(clock.getAsLong());
        if(enabled){if(!consent.containsKey(player)&&consent.size()>=64)throw new IllegalStateException("Viewer opt-in limit reached");consent.put(player,new Consent(peer.realm(),clock.getAsLong()+EntityViewsWire.OPT_IN_MILLIS));}
        else consent.remove(player);
    }
    synchronized Response exchange(Peer peer,Request request,Set<String> validAnchoredPlayers)throws IOException {
        available();long now=clock.getAsLong();prune(now);
        Counter old=counters.get(peer.worker);
        if(old!=null&&old.instance.equals(peer.instance)&&request.sequence()<=old.sequence)throw new IllegalStateException("Duplicate/stale entity-view exchange");
        // Automatic observations are EPHEMERAL, never persisted pair records or
        // authorization to create a world entity. Both Node and Manager independently
        // check the current region authority; a client-supplied identity is insufficient.
        int automaticCount=0;
        var verified=new ArrayList<Pair>();
        for(State state:request.states()) {
            Pair manual=pairs.get(state.binding());
            if(manual!=null) {
                if(!manual.worker().equals(peer.worker)||!peer.sameWorld(manual)||!EntityViewsWire.matches(manual,state))
                    throw new SecurityException("Paired source/identity mismatch");
                verified.add(manual);continue;
            }
            if(!request.automatic()){verified.add(null);continue;} // legacy unpaired in-flight sample cannot resurrect a removed pair
            if(!EntityViewsWire.autoBinding(peer.worker(),state.entity()).equals(state.binding())
                ||!validatesActiveAuthority.test(peer,state))
                throw new SecurityException("Automatic entity is not from its ACTIVE region owner");
            if(pairs.values().stream().anyMatch(p->peer.sameWorld(p)&&p.entity().equals(state.entity())))
                throw new SecurityException("Automatic entity conflicts with a persisted manual pairing");
            if(++automaticCount>EntityViewsWire.MAX_AUTO_STATES)
                throw new IllegalArgumentException("Automatic source sample budget exceeded");
            verified.add(new Pair(state.binding(),state.entity(),peer.worker(),peer.identity(),peer.seed(),state.dimension(),state.kind(),false));
        }
        var observations=request.players().stream().filter(p->validAnchoredPlayers.contains(p.name().toLowerCase(Locale.ROOT)))
            .map(p->new Seen(peer,p,now)).toList();
        // A full viewer list must not prevent this worker publishing its own entities.
        // Deterministic selection bounds output; excess opted-in viewers receive no view.
        var viewers=observations.stream().filter(s->allowed(peer,s.player.uuid(),now))
            .sorted(Comparator.comparing(s->s.player.uuid().toString())).limit(EntityViewsWire.MAX_VIEWERS).toList();
        live.values().removeIf(l->l.source.worker.equals(peer.worker));
        for(int i=0;i<verified.size();i++) {
            Pair p=verified.get(i);State state=request.states().get(i);
            if(p!=null)live.put(p.binding(),new Live(p,state,peer,request.sequence(),now));
        }
        counters.put(peer.worker,new Counter(peer.instance,request.sequence()));positions.put(peer.worker,observations);
        var owned=new ArrayList<Pair>();
        for(Pair p:pairs.values())if(p.worker().equals(peer.worker)&&peer.sameWorld(p))owned.add(p.interest(interested(p,now)));
        for(Pair p:verified)if(p!=null&&!pairs.containsKey(p.binding()))owned.add(p.interest(interested(p,now)));
        var output=new ArrayList<Viewer>();
        for(Seen seen:viewers) {
            Player viewer=seen.player;if(!allowed(peer,viewer.uuid(),now))continue;
            var samples=new ArrayList<Sample>();
            // Nearest-first guarantees bounded fanout in busy areas, without
            // depending on the hash table's insertion order or a worker's schedule.
            var candidates=live.values().stream().filter(l->!l.source.worker.equals(peer.worker) &&
                peer.sameWorld(l.pair) && viewer.near(l.state) && now-l.received<EntityViewsWire.FRESH_MILLIS)
                .sorted(Comparator.comparingDouble((Live l)->distance(viewer,l.state))
                    .thenComparing(l->l.pair.entity().toString())).toList();
            Set<UUID> visibleIds=new HashSet<>();
            for(Live l:candidates) {
                if(samples.size()>=EntityViewsWire.MAX_PAIRS)break;
                if(!visibleIds.add(l.pair.entity()))continue;
                long remaining=EntityViewsWire.FRESH_MILLIS-(now-l.received);
                samples.add(new Sample(l.pair,l.state,UUID.fromString(l.source.instance),l.sequence,remaining));
            }
            output.add(new Viewer(viewer.uuid(),samples));
        }
        var interests=request.automatic()?positions.entrySet().stream()
            .filter(e->!e.getKey().equals(peer.worker()))
            .flatMap(e->e.getValue().stream())
            .filter(s->s.peer.realm().equals(peer.realm()))
            .filter(s->now-s.at<EntityViewsWire.FRESH_MILLIS && allowed(s.peer,s.player.uuid(),now))
            .sorted(Comparator.comparing(s->s.player.uuid().toString()))
            .limit(EntityViewsWire.MAX_INTERESTS).map(Seen::player).toList():List.<Player>of();
        return new Response(request.sequence(),boot,owned,output,interests);
    }
    /** Remove presentations immediately when the Manager sees an offline/replaced/mismatched source. */
    synchronized void invalidateUnavailable(java.util.function.Predicate<Peer> healthy) {
        live.values().removeIf(l->!healthy.test(l.source));positions.values().removeIf(v->!v.isEmpty()&&!healthy.test(v.getFirst().peer));
    }
    private static double distance(Player p,State s) {
        double dx=p.x()-s.x(),dy=p.y()-s.y(),dz=p.z()-s.z();return dx*dx+dy*dy+dz*dz;
    }
    private boolean allowed(Peer peer,UUID id,long now){Consent c=consent.get(id);return c!=null&&c.realm.equals(peer.realm())&&now<c.expires;}
    private boolean interested(Pair p,long now) {
        Live l=live.get(p.binding());if(l==null)return false;
        for(var list:positions.values())for(Seen s:list)
            if(now-s.at<EntityViewsWire.FRESH_MILLIS&&s.peer.sameWorld(p)&&allowed(s.peer,s.player.uuid(),now)&&s.player.near(l.state))return true;
        return false;
    }
    private void prune(long now) {
        consent.values().removeIf(c->now>=c.expires);positions.values().removeIf(v->v.isEmpty()||now-v.getFirst().at>=EntityViewsWire.FRESH_MILLIS);
        live.values().removeIf(l->now-l.received>=EntityViewsWire.FRESH_MILLIS);
    }
    synchronized List<Pair> pairs()throws IOException{available();return List.copyOf(pairs.values());}
    synchronized int freshCount()throws IOException{available();prune(clock.getAsLong());return live.size();}
    synchronized long discoveredCount()throws IOException{available();prune(clock.getAsLong());return live.values().stream().filter(l->!pairs.containsKey(l.pair.binding())).count();}
    synchronized int viewerCount()throws IOException{available();prune(clock.getAsLong());return consent.size();}
    private void available()throws IOException{if(closed||failed)throw new IOException("Paired-view registry unavailable; restart to validate metadata");}
    private static byte[] digest(byte[] data) {try{return MessageDigest.getInstance("SHA-256").digest(data);}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}}
    private void persist(Map<UUID,Pair> next)throws IOException {
        byte[] data=EntityViewsWire.encodePairs(List.copyOf(next.values()));
        if(data.length+32>32768)throw new IOException("Pair metadata exceeds bound");
        Path temp=file.resolveSibling("paired-entity-views-"+UUID.randomUUID()+".tmp");
        try {
            try(var channel=FileChannel.open(temp,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer b=ByteBuffer.allocate(data.length+32).put(data).put(digest(data));b.flip();while(b.hasRemaining())channel.write(b);channel.force(true);
            }
            if(Files.isSymbolicLink(file))throw new IOException("Pair registry cannot be a symbolic link");
            Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } catch(IOException e){failed=true;throw e;} finally{Files.deleteIfExists(temp);}
    }
    @Override public synchronized void close(){closed=true;live.clear();positions.clear();consent.clear();}
}
