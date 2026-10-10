package com.foundations.cluster.manager;
import com.foundations.cluster.protocol.EntityViewsWire;
import com.foundations.cluster.protocol.EntityViewsWire.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
public final class EntityViewRegistrySelfTest {
    private static int checks;
    private interface Attempt{void run()throws Exception;}
    private static void check(boolean yes,String why){checks++;if(!yes)throw new AssertionError(why);}
    private static void rejects(Attempt a)throws Exception{boolean failed=false;try{a.run();}catch(IOException|IllegalArgumentException|IllegalStateException|SecurityException e){failed=true;}check(failed,"Invalid registry action accepted");}
    private static State state(Pair p,double x){return new State(p.binding(),p.entity(),p.kind(),p.dimension(),x,65,0,45,0,60,.12,0,0,true,new byte[]{1,2,3});}
    private static State initial(UUID id){return new State(new UUID(0,0),id,Kind.COW,"minecraft:overworld",0,65,0,0,0,0,0,0,0,true,new byte[]{1,2,3});}
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("entity-views-registry-");var clock=new AtomicLong(1000);
        var a=new EntityViewRegistry.Peer("worker-001",UUID.randomUUID().toString(),"identity",123);
        var b=new EntityViewRegistry.Peer("worker-002",UUID.randomUUID().toString(),"identity",123);
        var other=new EntityViewRegistry.Peer("worker-003",UUID.randomUUID().toString(),"other-identity",123);
        var viewer=new Player(UUID.randomUUID(),"Fixture","minecraft:overworld",1,65,0);
        Pair saved;
        try {
            try(var registry=new EntityViewRegistry(root,clock::get)) {
                check(registry.pairs().isEmpty(),"empty metadata registry");
                saved=registry.pair(a,initial(UUID.randomUUID()));
                check(registry.pair(a,initial(saved.entity())).equals(saved),"identical pairing reuses identity");
                rejects(()->registry.pair(b,initial(saved.entity())));
                check(registry.pairs().size()==1,"conflict cannot create a second logical pair");
                registry.exchange(a,new Request(1,List.of(state(saved,2)),List.of()),Set.of());
                check(registry.exchange(b,new Request(1,List.of(),List.of(viewer)),Set.of("fixture")).viewers().isEmpty(),"off until explicit viewer opt-in");
                registry.view(a,viewer.uuid(),true); // Consent survives the viewer moving to B.
                var r=registry.exchange(b,new Request(2,List.of(),List.of(viewer)),Set.of("fixture"));
                check(r.viewers().size()==1&&r.viewers().getFirst().samples().getFirst().state().x()==2,"paired location available on other anchor");
                check(r.viewers().getFirst().samples().getFirst().state().entity().equals(saved.entity()),"UUID is the pair key, not position");
                check(registry.exchange(a,new Request(2,List.of(state(saved,3)),List.of()),Set.of()).owned().getFirst().interested(),"remote viewer feeds bounded ticket interest");
                rejects(()->registry.exchange(a,new Request(2,List.of(state(saved,999)),List.of()),Set.of()));
                r=registry.exchange(b,new Request(3,List.of(),List.of(viewer)),Set.of("fixture"));check(r.viewers().getFirst().samples().getFirst().state().x()==3,"stale frame did not replace newer location");
                rejects(()->registry.exchange(b,new Request(4,List.of(state(saved,99)),List.of()),Set.of()));
                check(registry.exchange(b,new Request(4,List.of(),List.of(viewer)),Set.of()).viewers().isEmpty(),"unanchored player cannot subscribe");
                registry.view(other,viewer.uuid(),true);
                check(registry.exchange(other,new Request(1,List.of(),List.of(viewer)),Set.of("fixture")).viewers().getFirst().samples().isEmpty(),"different world identity cannot see entity");
                registry.view(b,viewer.uuid(),true);clock.addAndGet(2000);
                check(registry.exchange(b,new Request(5,List.of(),List.of(viewer)),Set.of("fixture")).viewers().getFirst().samples().isEmpty(),"stale source frames expire");
                var restarted=new EntityViewRegistry.Peer(a.worker(),UUID.randomUUID().toString(),a.identity(),a.seed());
                registry.exchange(restarted,new Request(1,List.of(state(saved,4)),List.of()),Set.of());
                r=registry.exchange(b,new Request(6,List.of(),List.of(viewer)),Set.of("fixture"));
                check(r.viewers().getFirst().samples().getFirst().instance().toString().equals(restarted.instance()),"new source process explicitly identifies counter reset");
                registry.invalidateUnavailable(peer->!peer.worker().equals(a.worker()));
                check(registry.freshCount()==0,"source unavailable/changed process clears cache");
                registry.exchange(restarted,new Request(2,List.of(state(saved,4)),List.of()),Set.of());
                registry.exchange(restarted,new Request(3,List.of(),List.of()),Set.of());
                check(registry.freshCount()==0&&registry.pairs().size()==1,"absent entity removes only presentation; pairing metadata is not a respawn instruction");
                clock.addAndGet(EntityViewsWire.OPT_IN_MILLIS);
                check(registry.exchange(b,new Request(7,List.of(),List.of(viewer)),Set.of("fixture")).viewers().isEmpty(),"viewer permission expires");
            }
            try(var reopened=new EntityViewRegistry(root,clock::get)) {
                check(reopened.pairs().getFirst().equals(saved),"persistent pair selection survives Manager restart");
                check(reopened.freshCount()==0&&reopened.viewerCount()==0,"old views/consent never replay as live state");
                reopened.close();rejects(reopened::pairs);rejects(()->reopened.pair(a,initial(UUID.randomUUID())));
            }
            Path file=root.resolve("paired-entity-views.bin");byte[] original=Files.readAllBytes(file);
            for(int i=0;i<original.length;i++){
                byte[] bad=original.clone();bad[i]^=1;Files.write(file,bad);
                rejects(()->{try(var ignored=new EntityViewRegistry(root,clock::get)) {}});
                check(Arrays.equals(bad,Files.readAllBytes(file)),"corrupt registry bytes preserved");
            }
            Files.write(file,original);
            try(var registry=new EntityViewRegistry(root,clock::get)){
                check(registry.unpair(b,saved.entity()),"explicit unpair from same-world operator");
                var newPair=registry.pair(b,initial(saved.entity()));check(!newPair.binding().equals(saved.binding()),"explicit source re-pair gets new binding generation");
                registry.exchange(a,new Request(1,List.of(state(saved,10)),List.of()),Set.of());
                check(registry.freshCount()==0,"old in-flight binding cannot resurrect an unpaired source");
                for(int i=1;i<EntityViewsWire.MAX_SOURCE_PAIRS;i++)registry.pair(b,initial(UUID.randomUUID()));
                rejects(()->registry.pair(b,initial(UUID.randomUUID())));
                check(registry.pairs().size()==EntityViewsWire.MAX_SOURCE_PAIRS,"source limit enforced without altering existing pairs");
            }
            System.out.println("Paired entity registry: PASS ("+checks+" checks; metadata only, no entity ownership or world writes)");
        }finally{try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
    }
}
