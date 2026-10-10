package com.foundations.cluster.manager;

import com.foundations.cluster.protocol.EntityViewsWire;
import com.foundations.cluster.protocol.EntityViewsWire.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Synthetic trusted peers and authority state, not two running Minecraft workers. */
public final class AutoEntityRegistrySelfTest {
    private static int checks;
    private static void ok(boolean b,String description){checks++;if(!b)throw new AssertionError(description);}
    private static void reject(Throwing x)throws Exception {
        try{x.run();}catch(IOException|IllegalStateException|SecurityException|IllegalArgumentException e){checks++;return;}
        throw new AssertionError("Expected rejection");
    }
    interface Throwing{void run()throws Exception;}
    private static State state(String worker,UUID uuid,double x){return new State(EntityViewsWire.autoBinding(worker,uuid),uuid,Kind.COW,
        "minecraft:overworld",x,65,0,0,0,0,0,0,0,true,new byte[]{1,2,3});}
    public static void main(String[] args)throws Exception {
        var directory=Files.createTempDirectory("auto-entities-");AtomicLong time=new AtomicLong(1000);
        AtomicReference<String> owner=new AtomicReference<>("worker-001");
        var a=new EntityViewRegistry.Peer("worker-001",UUID.randomUUID().toString(),"identity",123);
        var b=new EntityViewRegistry.Peer("worker-002",UUID.randomUUID().toString(),"identity",123);
        var foreign=new EntityViewRegistry.Peer("worker-003",UUID.randomUUID().toString(),"other",123);
        UUID id=UUID.randomUUID();Player player=new Player(UUID.randomUUID(),"Viewer","minecraft:overworld",2,65,0);
        try {
            try(var reg=new EntityViewRegistry(directory,time::get,(peer,s)->peer.worker().equals(owner.get()) && s.x()<100)){
                checkInitial(reg);
                var preview=state(a.worker(),id,2);
                reg.exchange(a,new Request(1,List.of(preview),List.of()),Set.of());
                ok(reg.discoveredCount()==0,"Unknown legacy pairing never becomes automatic without explicit mode");
                // Subscriber opts into the same world. No manual pairing of the cow.
                reg.view(b,player.uuid(),true);
                var bResponse=reg.exchange(b,new Request(1,List.of(),List.of(player),true),Set.of("viewer"));
                ok(bResponse.interests().isEmpty(),"No remote viewers from own worker included");
                var aResponse=reg.exchange(a,new Request(2,List.of(),List.of(),true),Set.of());
                ok(aResponse.interests().equals(List.of(player)),"Interested remote player transmitted to source");
                ok(aResponse.owned().isEmpty(),"Source has no auto entities before reporting them");
                aResponse=reg.exchange(a,new Request(3,List.of(preview),List.of(),true),Set.of());
                ok(aResponse.owned().size()==1 && aResponse.owned().getFirst().interested(),"Ephemeral cow causes ticket interest");
                var received=reg.exchange(b,new Request(2,List.of(),List.of(player),true),Set.of("viewer"));
                ok(received.viewers().size()==1 && received.viewers().getFirst().samples().size()==1,"A's cow visible at B");
                var sample=received.viewers().getFirst().samples().getFirst();
                ok(sample.state().x()==2 && sample.state().entity().equals(id),"Exact UUID and coordinates preserved");
                ok(reg.pairs().isEmpty(),"Automatic previews never persist a manual pairing");
                ok(reg.discoveredCount()==1,"Automatic live count accurate");
                reject(()->reg.exchange(a,new Request(4,List.of(new State(UUID.randomUUID(),id,Kind.COW,"minecraft:overworld",2,65,0,0,0,0,0,0,0,true,new byte[]{1})),List.of(),true),Set.of()));
                reject(()->reg.exchange(b,new Request(3,List.of(state(b.worker(),id,2)),List.of(),true),Set.of()));
                reject(()->reg.exchange(foreign,new Request(1,List.of(state(foreign.worker(),id,2)),List.of(),true),Set.of()));
                reject(()->reg.exchange(a,new Request(4,List.of(state(a.worker(),UUID.randomUUID(),1000)),List.of(),true),Set.of()));
                var next=reg.exchange(a,new Request(4,List.of(state(a.worker(),id,3)),List.of(),true),Set.of());
                ok(next.owned().getFirst().entity().equals(id),"New source frame does not create a new identity");
                owner.set("worker-002");
                reject(()->reg.exchange(a,new Request(5,List.of(state(a.worker(),id,4)),List.of(),true),Set.of()));
                reg.invalidateUnavailable(p->!p.worker().equals(a.worker()));
                ok(reg.discoveredCount()==0,"Loss of old owner invalidates old source");
                var move=state(b.worker(),id,4);
                reg.exchange(b,new Request(3,List.of(move),List.of(player),true),Set.of("viewer"));
                ok(reg.discoveredCount()==1,"New owner may publish same logical UUID after authority changes");
                time.addAndGet(2000);reg.exchange(a,new Request(5,List.of(),List.of(),true),Set.of());
                ok(reg.discoveredCount()==0,"Freshness timeout removes ephemeral observations");
            }
            try(var reopened=new EntityViewRegistry(directory,time::get,(peer,s)->true)){
                ok(reopened.pairs().isEmpty()&&reopened.discoveredCount()==0,"Auto discovery has no persistent resurrection instructions");
            }
            System.out.println("Automatic entity discovery registry: PASS ("+checks+" checks; ephemeral only)");
        }finally{try(var paths=Files.walk(directory)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
    }
    private static void checkInitial(EntityViewRegistry r)throws Exception{ok(r.pairs().isEmpty()&&r.discoveredCount()==0,"No precreated entity pairing");}
}
