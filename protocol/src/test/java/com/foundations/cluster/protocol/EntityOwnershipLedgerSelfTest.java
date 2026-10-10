package com.foundations.cluster.protocol;
import com.foundations.cluster.protocol.EntityOwnershipLedger.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Manager-level model only. No gameplay entity is loaded or handed off. */
public final class EntityOwnershipLedgerSelfTest {
    private static int checks;
    private static void check(boolean test,String text){checks++;if(!test)throw new AssertionError(text);}
    private interface Throwing{void run()throws Exception;}
    private static void rejects(Throwing r)throws Exception{
        boolean blocked=false;try{r.run();}catch(IOException|IllegalArgumentException|IllegalStateException e){blocked=true;}
        check(blocked,"Stale/invalid ownership write accepted");
    }
    private static final Key KEY=new Key(new UUID(10,20),new UUID(30,40));
    private static final byte[] STATE="minecraft:cow full persistent state #1".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    public static void main(String[] args)throws Exception {
        if(args.length==2&&args[0].equals("child")){
            Path dir=Path.of(args[1]);try(var l=new EntityOwnershipLedger(dir)){
                if(l.view(KEY).isEmpty())l.initialize(KEY,"minecraft:overworld","minecraft:cow","worker-A",9);
                UUID transfer=new UUID(5,6);
                if(l.view(KEY).get().staged()==null&&l.view(KEY).get().committedTransfer()==null)
                    l.stage(KEY,transfer,1,"worker-A",9,"worker-B",STATE);
                System.out.println("CHILD_STAGE_DURABLE");System.out.flush();
                if(Boolean.getBoolean("commit")){
                    l.commit(KEY,transfer,1,"worker-A","worker-B",10,EntityOwnershipLedger.hash(STATE));
                    System.out.println("CHILD_COMMIT_DURABLE");System.out.flush();
                }
                Thread.sleep(30000);
            }return;
        }
        Path temp=Files.createTempDirectory("foundations-ownership-");
        UUID t=new UUID(1,2),another=new UUID(3,4);
        try(var ledger=new EntityOwnershipLedger(temp)){
            check(ledger.size()==0,"New ledger empty");
            var initial=ledger.initialize(KEY,"minecraft:overworld","minecraft:cow","worker-A",9);
            check(initial.owner().worker().equals("worker-A")&&initial.owner().generation()==1,"Durable initial authority");
            check(ledger.initialize(KEY,"minecraft:overworld","minecraft:cow","worker-A",9).equals(initial),"Safe identical bootstrap");
            rejects(()->ledger.initialize(KEY,"minecraft:overworld","minecraft:cow","worker-B",9));
            rejects(()->ledger.stage(KEY,t,0,"worker-A",9,"worker-B",STATE));
            rejects(()->ledger.stage(KEY,t,1,"worker-A",8,"worker-B",STATE));
            var prepared=ledger.stage(KEY,t,1,"worker-A",9,"worker-B",STATE);
            check(prepared.staged().digest().equals(EntityOwnershipLedger.hash(STATE)),"Full state digest persisted with stage");
            check(Arrays.equals(prepared.staged().image(),STATE),"Full staged bytes available");
            byte[] modified=prepared.staged().image();modified[0]^=1;
            check(Arrays.equals(ledger.view(KEY).get().staged().image(),STATE),"Caller cannot mutate stored stage data");
            check(ledger.stage(KEY,t,1,"worker-A",9,"worker-B",STATE).equals(prepared),"Identical stage retry deduplicates");
            rejects(()->ledger.stage(KEY,another,1,"worker-A",9,"worker-B",STATE));
            rejects(()->ledger.stage(KEY,t,1,"worker-A",9,"worker-C",STATE));
            rejects(()->ledger.commit(KEY,t,1,"worker-A","worker-B",10,"0".repeat(64)));
            rejects(()->ledger.commit(KEY,t,2,"worker-A","worker-B",10,EntityOwnershipLedger.hash(STATE)));
            check(ledger.view(KEY).get().owner().worker().equals("worker-A"),"Premature commit cannot take authority");
            check(ledger.abort(KEY,t),"Precommit abort allowed");
            check(ledger.view(KEY).get().staged()==null,"Abort cleared staging");
            ledger.stage(KEY,another,1,"worker-A",9,"worker-B",STATE);
        }
        try(var ledger=new EntityOwnershipLedger(temp)){
            check(ledger.view(KEY).get().staged()!=null,"Prepared state survives clean reopen");
            var commit=ledger.commit(KEY,another,1,"worker-A","worker-B",10,EntityOwnershipLedger.hash(STATE));
            check(commit.committedNow()&&commit.view().owner().generation()==2,"Durable CAS increments generation");
            check(!ledger.commit(KEY,another,1,"worker-A","worker-B",10,EntityOwnershipLedger.hash(STATE)).committedNow(),"Same transfer ID only applies once");
            check(commit.view().oldOwner().equals("worker-A")&&commit.view().oldGeneration()==1,"Source tombstone generation retained");
            rejects(()->ledger.abort(KEY,another));
            rejects(()->ledger.stage(KEY,UUID.randomUUID(),1,"worker-A",9,"worker-C",STATE));
            rejects(()->ledger.stage(KEY,another,2,"worker-B",10,"worker-A",STATE));
            UUID next=new UUID(7,8);ledger.stage(KEY,next,2,"worker-B",10,"worker-A",STATE);
            ledger.commit(KEY,next,2,"worker-B","worker-A",11,EntityOwnershipLedger.hash(STATE));
            check(ledger.view(KEY).get().owner().generation()==3,"Returning to original worker still advances generation");
            rejects(()->ledger.commit(KEY,another,1,"worker-A","worker-B",10,EntityOwnershipLedger.hash(STATE)));
            rejects(()->new EntityOwnershipLedger(temp));
        }
        try(var reopened=new EntityOwnershipLedger(temp)){
            check(reopened.view(KEY).get().owner().generation()==3,"Latest committed CAS survives reopening");
            check(reopened.view(KEY).get().staged()==null,"No orphaned stage after durable CAS");
        }
        Path snapshot=temp.resolve("entity-ownership-v1.bin");byte[] original=Files.readAllBytes(snapshot);
        byte[] corrupt=original.clone();corrupt[corrupt.length-3]^=1;Files.write(snapshot,corrupt);
        rejects(()->new EntityOwnershipLedger(temp));Files.write(snapshot,original);
        Path killStage=Files.createTempDirectory("foundations-stage-kill-");
        forceStopChild(killStage,false,"CHILD_STAGE_DURABLE");
        try(var ledger=reopenAfterKilledChild(killStage)){
            check(ledger.view(KEY).orElseThrow().owner().worker().equals("worker-A"),"Killed JVM after stage retains A owner");
            check(ledger.view(KEY).get().staged()!=null,"Killed JVM leaves stage for retry/reconcile");
        }
        Path killCommit=Files.createTempDirectory("foundations-commit-kill-");
        forceStopChild(killCommit,true,"CHILD_COMMIT_DURABLE");
        try(var ledger=reopenAfterKilledChild(killCommit)){
            check(ledger.view(KEY).orElseThrow().owner().worker().equals("worker-B"),"Killed JVM after CAS retains B owner");
            check(ledger.view(KEY).get().owner().generation()==2,"Killed JVM cannot restore stale generation");
        }
        check(!EntityOwnershipLedger.hash(STATE).isBlank(),"Digest is stable");
        System.out.println("Entity ownership CAS recovery prototype PASS ("+checks+" checks; 2 forcibly killed child JVMs; not live entity transfer)");
    }
    private static EntityOwnershipLedger reopenAfterKilledChild(Path directory)throws Exception {
        // Windows may release the killed JVM's kernel file lock slightly after
        // Process.waitFor reports termination. Never bypass or steal the lock.
        IOException busy=null;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        do {
            try{return new EntityOwnershipLedger(directory);}
            catch(IOException e){
                if(!String.valueOf(e.getMessage()).contains("already open"))throw e;
                busy=e;Thread.sleep(40);
            }
        }while(System.nanoTime()<deadline);
        throw busy;
    }
    private static void forceStopChild(Path directory,boolean commit,String marker)throws Exception {
        String java=Path.of(System.getProperty("java.home"),"bin","java").toString();
        List<String> cmd=new ArrayList<>(List.of(java));if(commit)cmd.add("-Dcommit=true");
        cmd.addAll(List.of("-cp",System.getProperty("java.class.path"),EntityOwnershipLedgerSelfTest.class.getName(),"child",directory.toString()));
        Process process=new ProcessBuilder(cmd).redirectErrorStream(true).start();
        try(var reader=new BufferedReader(new InputStreamReader(process.getInputStream()))){
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            boolean confirmed=false;
            while(System.nanoTime()<until){
                if(reader.ready()){String line=reader.readLine();if(line!=null&&line.contains(marker)){confirmed=true;break;}}
                else Thread.sleep(30);
            }
            check(confirmed,"Child passed durable checkpoint before kill: "+marker);
            process.destroyForcibly();check(process.waitFor(5,TimeUnit.SECONDS),"Child forcibly stopped");
        }finally{if(process.isAlive())process.destroyForcibly();}
    }
}
