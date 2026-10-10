package com.foundations.cluster.protocol;
import com.foundations.cluster.protocol.PickupTransferLedger.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** The state-machine proof is not a live Minecraft inventory/pickup guarantee. */
public final class PickupTransferLedgerSelfTest {
    private static final Id ID=new Id(new UUID(2,3),new UUID(4,5),8,new UUID(6,7),12);
    private static final String ITEM="0".repeat(64),WORLD="1".repeat(64),PLAYER="2".repeat(64);
    private static int checks;
    private interface Action{void run()throws Exception;}
    private static void ok(boolean valid,String context){checks++;if(!valid)throw new AssertionError(context);}
    private static void rejected(Action action)throws Exception {boolean block=false;
        try{action.run();}catch(IllegalArgumentException|IllegalStateException|IOException ex){block=true;}
        ok(block,"Invalid pickup transition accepted");}
    public static void main(String[] args)throws Exception {
        if(args.length==2 && args[0].equals("child")){
            Path d=Path.of(args[1]);try(var ledger=new PickupTransferLedger(d)){
                ledger.propose(ID,4,17,ITEM);ledger.reserve(ID,4,11,13);ledger.commitWorld(ID,12,4,WORLD);
                System.out.println("PICKUP_WORLD_DURABLE");System.out.flush();Thread.sleep(30000);
            }return;
        }
        Path dir=Files.createTempDirectory("foundations-pickup-test-");
        try(var l=new PickupTransferLedger(dir)){
            ok(l.propose(ID,4,17,ITEM).phase()==Phase.PROPOSED,"Offer durable");
            ok(l.propose(ID,4,17,ITEM).offered()==17,"Offer idempotent");
            rejected(()->l.propose(ID,4,18,ITEM));rejected(()->l.reserve(ID,5,11,13));
            rejected(()->l.reserve(ID,4,11,18));rejected(()->l.commitWorld(ID,12,4,WORLD));
            Id other=new Id(ID.world(),ID.itemEntity(),ID.ownerGeneration(),UUID.randomUUID(),20);
            rejected(()->l.propose(other,4,17,ITEM));
            ok(l.reserve(ID,4,11,13).accepted()==13,"Partial inventory reserve");
            ok(l.reserve(ID,4,11,13).phase()==Phase.RESERVED,"Reservation retry idempotent");
            rejected(()->l.reserve(ID,4,12,13));rejected(()->l.commitWorld(ID,14,4,WORLD));
            rejected(()->l.commitWorld(ID,12,5,WORLD));
            rejected(()->l.commitPlayer(ID,12,WORLD,PLAYER));
            ok(l.view(ID).get().phase()==Phase.RESERVED,"Rejected commits preserve reserved phase");
        }
        try(var l=new PickupTransferLedger(dir)){
            ok(l.view(ID).get().accepted()==13,"Reservation durable across restart");
            ok(l.commitWorld(ID,12,4,WORLD).phase()==Phase.WORLD_COMMITTED,"World entity reduction committed");
            ok(l.commitWorld(ID,12,4,WORLD).committed()==12,"World duplicate exact no-op");
            rejected(()->l.abort(ID));rejected(()->l.commitWorld(ID,13,4,WORLD));
            rejected(()->l.commitPlayer(ID,11,WORLD,PLAYER));
            rejected(()->l.commitPlayer(ID,12,"3".repeat(64),PLAYER));
            ok(l.view(ID).get().phase()==Phase.WORLD_COMMITTED,"Grant has not yet occurred");
        }
        try(var l=new PickupTransferLedger(dir)){
            ok(l.view(ID).get().committed()==12,"Recover exact pending grant after world commit");
            ok(l.commitPlayer(ID,12,WORLD,PLAYER).phase()==Phase.PLAYER_COMMITTED,"Commit idempotent player grant identity");
            ok(l.commitPlayer(ID,12,WORLD,PLAYER).inventoryRevision()==12,"Identical grant retried no-op");
            rejected(()->l.commitPlayer(ID,13,WORLD,PLAYER));
            rejected(()->l.abort(ID));
            ok(l.clear(ID).phase()==Phase.CLEARED,"Clear after player durability");
            ok(l.clear(ID).phase()==Phase.CLEARED,"Repeat clear is no-op");
            Id partial=new Id(ID.world(),ID.itemEntity(),ID.ownerGeneration(),UUID.randomUUID(),30);
            rejected(()->l.propose(partial,4,5,ITEM));
            ok(l.propose(partial,5,5,ITEM).phase()==Phase.PROPOSED,"New remaining-entity revision can be picked up after clear");
        }
        try(var l=new PickupTransferLedger(dir)){
            ok(l.view(ID).get().phase()==Phase.CLEARED,"Committed effect tombstone survives reboot");
            rejected(()->l.commitPlayer(ID,13,WORLD,PLAYER));
            rejected(()->l.propose(ID,3,17,ITEM));
            rejected(()->new PickupTransferLedger(dir));
        }
        Path file=dir.resolve("pickup-transfers-v1.bin");byte[] good=Files.readAllBytes(file),bad=good.clone();bad[bad.length-1]^=127;
        Files.write(file,bad);rejected(()->new PickupTransferLedger(dir));Files.write(file,good);
        Path killed=Files.createTempDirectory("foundations-pickup-killed-");kill(killed);
        try(var l=reopenAfterKilledChild(killed)){
            var e=l.view(ID).orElseThrow();ok(e.phase()==Phase.WORLD_COMMITTED&&e.committed()==12,"Killed JVM cannot undo durable world consumption");
            rejected(()->l.abort(ID));
            ok(l.commitPlayer(ID,12,WORLD,PLAYER).phase()==Phase.PLAYER_COMMITTED,"Recovery reconciles unfinished player grant");
            ok(l.clear(ID).phase()==Phase.CLEARED,"Recovered grant clears once");
        }
        Id early=new Id(ID.world(),UUID.randomUUID(),1,ID.player(),19);
        try(var l=new PickupTransferLedger(Files.createTempDirectory("foundations-pickup-abort-"))){
            l.propose(early,2,8,ITEM);l.reserve(early,2,10,2);ok(l.abort(early).phase()==Phase.ABORTED,"Explicit precommit abort");
            rejected(()->l.commitWorld(early,1,2,WORLD));
        }
        System.out.println("Pickup effect durable reference: PASS ("+checks+" checks; 1 killed JVM; no live inventory or entity adapter)");
    }
    private static PickupTransferLedger reopenAfterKilledChild(Path directory)throws Exception {
        IOException busy=null;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        do {
            try{return new PickupTransferLedger(directory);}
            catch(IOException e){
                if(!String.valueOf(e.getMessage()).contains("already open"))throw e;
                busy=e;Thread.sleep(40);
            }
        }while(System.nanoTime()<deadline);
        throw busy;
    }
    private static void kill(Path dir)throws Exception {
        String java=Path.of(System.getProperty("java.home"),"bin","java").toString();
        var p=new ProcessBuilder(java,"-cp",System.getProperty("java.class.path"),PickupTransferLedgerSelfTest.class.getName(),"child",dir.toString())
                .redirectErrorStream(true).start();
        try(var reader=new BufferedReader(new InputStreamReader(p.getInputStream()))){
            boolean ready=false;long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            while(System.nanoTime()<until){if(reader.ready()){String s=reader.readLine();if(s!=null&&s.contains("PICKUP_WORLD_DURABLE")){ready=true;break;}}else Thread.sleep(25);}
            ok(ready,"Child produced durable world receipt");p.destroyForcibly();ok(p.waitFor(5,TimeUnit.SECONDS),"Child killed");
        }finally{if(p.isAlive())p.destroyForcibly();}
    }
}
