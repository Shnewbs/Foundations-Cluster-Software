package com.foundations.cluster.protocol;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import com.foundations.cluster.protocol.PlayerInventoryJournal.*;

/** File/journal evidence, not a Minecraft inventory or two-worker acceptance claim. */
public final class PlayerInventoryJournalSelfTest {
    private static final UUID CLUSTER = UUID.fromString("aaaa0000-0000-0000-0000-000000000001");
    private static final UUID PLAYER = UUID.fromString("aaaa0000-0000-0000-0000-000000000002");
    private static final byte[] BEFORE = {1,2,3,4}, AFTER = {1,2,3,3}, LATER = {1,2,3,2};
    private static final WorldReceipt RECEIPT = new WorldReceipt("overworld/cell/0/0", 3, 12, "a".repeat(64));
    private static int checks;
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--child")) { child(Path.of(args[1]), Integer.parseInt(args[2])); return; }
        Path root = Files.createTempDirectory("foundations-inventory-journal-");
        try {
            semantics(root); corruption(root); atomicTails(root); identityAndUnavailable(root);
            for (int stage = 0; stage < 4; stage++) killed(root, stage);
            System.out.println("Atomic player inventory journal: PASS ("+checks+" checks; 4 killed JVMs; no Minecraft worker certification)");
        } finally { try (var stream = Files.walk(root)) {
            for (Path p : stream.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        } }
    }
    private static PlayerInventoryJournal open(Path path) throws IOException { return new PlayerInventoryJournal(path, CLUSTER, PLAYER); }
    private static OperationId id() { return new OperationId(CLUSTER, PLAYER, 0); }
    private static void semantics(Path root) throws Exception {
        Path path = root.resolve("semantics");
        try (var journal = open(path)) {
            check(!journal.initialized(), "new file does not invent player state");
            rejects(journal::view); rejects(() -> journal.claim(1));
            rejects(() -> journal.initialize(-1, BEFORE));
            rejects(() -> journal.initialize(1, new byte[0]));
            journal.initialize(1, BEFORE);
            rejects(() -> journal.initialize(2, AFTER));
            rejects(() -> journal.reserve(0, 0, 0, BEFORE));
            rejects(() -> journal.reserve(1, 1, 0, BEFORE));
            rejects(() -> journal.reserve(1, 0, 0, AFTER));
            rejects(() -> journal.reserve(1, 0, 41, BEFORE));
            rejects(() -> journal.reserve(1, 0, -1, BEFORE));
            var reservation = journal.reserve(1, 0, 0, BEFORE);
            check(reservation.id().equals(id()) && journal.view().nextCounter()==1, "persistent counter allocated with reservation");
            rejects(() -> journal.reserve(1, 0, 1, BEFORE));
            rejects(() -> journal.observe(1, 0, AFTER));
            rejects(() -> journal.commit(1, new OperationId(CLUSTER, PLAYER, 1), RECEIPT, AFTER));
            journal.claim(2);
            check(journal.view().pending().equals(reservation), "new anchor retains unfinished operation");
            rejects(() -> journal.commit(1, id(), RECEIPT, AFTER));
            var result = journal.commit(2, id(), RECEIPT, AFTER);
            check(result.newCommit() && result.applied().revision()==1, "first commit increments inventory revision");
            check(journal.view().pending()==null && journal.view().appliedCount()==1, "marker and inventory committed together");
            byte[] exposed = journal.view().image(); exposed[0]=100;
            check(Arrays.equals(journal.view().image(), AFTER), "view never leaks mutable inventory buffer");
            long size = Files.size(path);
            check(!journal.commit(2,id(),RECEIPT,AFTER).newCommit() && Files.size(path)==size, "identical retry does not append");
            journal.observe(2,1,LATER);
            check(!journal.commit(2,id(),RECEIPT,AFTER).newCommit(), "old result remains idempotent after local change");
            check(Arrays.equals(journal.view().image(),LATER) && journal.view().revision()==2, "duplicate cannot roll back a later inventory");
            rejects(() -> journal.commit(2,id(),RECEIPT,LATER));
            rejects(() -> journal.commit(2,id(),new WorldReceipt("other",3,12,"a".repeat(64)),AFTER));
            rejects(() -> journal.applied(new OperationId(UUID.randomUUID(),PLAYER,0)));
            rejects(() -> journal.claim(0));
            rejects(() -> journal.observe(2,0,BEFORE));
        }
        try (var journal = open(path)) {
            check(journal.view().anchorEpoch()==2 && journal.view().revision()==2, "anchor/revision survive reopen");
            check(journal.applied(id()).orElseThrow().receipt().equals(RECEIPT), "authority receipt survives reopen");
            check(Arrays.equals(journal.view().image(),LATER), "newest inventory survives reopen");
            check(journal.reserve(2,2,40,LATER).id().counter()==1, "counter survives reconnect; offhand slot accepted");
        }
        try (var journal = open(path)) {
            check(journal.view().pending().slot()==40, "reservation survives a second reopen");
            journal.claim(9); check(journal.view().pending().id().counter()==1, "anchor change does not allocate a second intent");
        }
        try (var journal = open(root.resolve("copies"))) {
            byte[] init=BEFORE.clone(); journal.initialize(1,init); init[0]=99;
            check(journal.view().image()[0]==1, "bootstrap clones caller bytes");
            var op=journal.reserve(1,0,0,BEFORE).id();byte[] after=AFTER.clone();
            journal.commit(1,op,RECEIPT,after);after[0]=99;
            check(journal.view().image()[0]==1, "commit clones caller bytes");
            rejects(() -> journal.observe(1,1,new byte[PlayerInventoryJournal.MAX_IMAGE_BYTES+1]));
        }
        rejects(() -> new WorldReceipt("cell",1,0,"a".repeat(64)));
        rejects(() -> new OperationId(CLUSTER,PLAYER,Long.MAX_VALUE));
    }
    private static byte[] history(Path path) throws Exception {
        try (var journal = open(path)) {
            journal.initialize(1,BEFORE);journal.reserve(1,0,0,BEFORE);journal.commit(1,id(),RECEIPT,AFTER);
        }
        return Files.readAllBytes(path);
    }
    private static List<byte[]> records(byte[] data) {
        var records=new ArrayList<byte[]>();
        for(int off=0;off<data.length;) {
            int len=ByteBuffer.wrap(data,off+4,4).getInt()+16;
            records.add(Arrays.copyOfRange(data,off,off+len));off+=len;
        }return records;
    }
    private static void corruption(Path root) throws Exception {
        byte[] good=history(root.resolve("corruption-source"));
        var frames=records(good);int base=frames.get(0).length+frames.get(1).length;
        for(int index=0;index<12;index++)for(int bit=0;bit<8;bit++) {
            byte[] bad=good.clone();bad[base+index]^=(byte)(1<<bit);
            preserved(root.resolve("corrupt-header-"+index+"-"+bit),bad);
        }
        for(int index=12;index<frames.get(2).length;index++) {
            byte[] bad=good.clone();bad[base+index]^=1;preserved(root.resolve("corrupt-body-"+index),bad);
        }
        for(int[] order:new int[][]{{1},{0,2},{0,0},{0,1,2,2},{0,2,1}}) {
            var bytes=new ByteArrayOutputStream();for(int i:order)bytes.write(frames.get(i));
            preserved(root.resolve("semantic-"+Arrays.toString(order)),bytes.toByteArray());
        }
        for(byte[] garbage:List.of(new byte[]{0},new byte[]{0,0,0},new byte[]{1,2,3,4})) {
            var bytes=new ByteArrayOutputStream();bytes.write(good);bytes.write(garbage);
            preserved(root.resolve("garbage-"+garbage.length),bytes.toByteArray());
        }
        Path wrong=root.resolve("wrong-owner");Files.write(wrong,good);
        rejects(() -> { try(var journal=new PlayerInventoryJournal(wrong,CLUSTER,UUID.randomUUID())) {} });
        check(Arrays.equals(good,Files.readAllBytes(wrong)),"wrong identity preserves full journal");
    }
    private static void atomicTails(Path root) throws Exception {
        byte[] good=history(root.resolve("tails-source"));var frames=records(good);
        int prefix=frames.get(0).length+frames.get(1).length;
        for(int cut=0;cut<frames.get(2).length;cut++) {
            Path path=root.resolve("cut-"+cut);Files.write(path,Arrays.copyOf(good,prefix+cut));
            try(var journal=open(path)) {
                check(Arrays.equals(journal.view().image(),BEFORE),"partial delta cannot change inventory");
                check(journal.view().pending()!=null && journal.applied(id()).isEmpty(),"partial delta cannot publish dedup marker");
                check(journal.view().revision()==0 && journal.view().nextCounter()==1,"partial delta preserves reservation/counter");
            }
            check(Arrays.equals(Files.readAllBytes(path),Arrays.copyOf(good,prefix)),"repair retains exact durable prefix");
            try(var journal=open(path)){check(journal.commit(1,id(),RECEIPT,AFTER).newCommit(),"retry after interrupted append commits once");}
            try(var journal=open(path)){check(!journal.commit(1,id(),RECEIPT,AFTER).newCommit(),"second reopen cannot repeat delta");}
        }
    }
    private static void identityAndUnavailable(Path root) throws Exception {
        Path path=root.resolve("availability");var journal=open(path);journal.initialize(1,BEFORE);
        rejects(() -> { try(var other=open(path)) {} });
        journal.close();journal.close();
        rejects(journal::view);rejects(journal::initialized);rejects(() -> journal.claim(1));
        rejects(() -> journal.reserve(1,0,0,BEFORE));
        Path link=root.resolve("link");
        Files.createSymbolicLink(link,path);
        rejects(() -> {try(var other=open(link)) {}});
        check(Files.isSymbolicLink(link),"symlink rejection does not modify link");
    }
    private static void child(Path path,int stage) throws Exception {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {try{Files.writeString(path.resolveSibling(path.getFileName()+".graceful"),"ran");}catch(IOException ignored){}}));
        try(var journal=open(path)) {
            journal.initialize(1,BEFORE);
            if(stage>=1)journal.reserve(1,0,0,BEFORE);
            if(stage>=2)journal.commit(1,id(),RECEIPT,AFTER);
            if(stage>=3)journal.observe(1,1,LATER);
            System.out.println("READY");System.out.flush();new CountDownLatch(1).await();
        }
    }
    private static void killed(Path root,int stage) throws Exception {
        Path path=root.resolve("killed-"+stage);
        String executable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        Process child=new ProcessBuilder(executable,"-cp",System.getProperty("java.class.path"),PlayerInventoryJournalSelfTest.class.getName(),"--child",path.toString(),Integer.toString(stage)).redirectErrorStream(true).start();
        ExecutorService reader=Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> ready=reader.submit(() -> {try(var lines=child.inputReader()) {String line;while((line=lines.readLine())!=null)if(line.equals("READY"))return true;return false;}});
            check(ready.get(30,TimeUnit.SECONDS),"child reached durable boundary");
            child.destroyForcibly();check(child.waitFor(15,TimeUnit.SECONDS)&&child.exitValue()!=0,"child was forcibly terminated");
            check(!Files.exists(path.resolveSibling(path.getFileName()+".graceful")),"shutdown hook did not run");
        } finally {if(child.isAlive()){child.destroyForcibly();child.waitFor(15,TimeUnit.SECONDS);}reader.shutdownNow();}
        PlayerInventoryJournal recovered=null;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(recovered==null) {
            try{recovered=open(path);}catch(IOException ex){if(!ex.getMessage().equals("Inventory journal already open")||System.nanoTime()>=deadline)throw ex;Thread.sleep(20);}
        }
        try(var journal=recovered) {
            check(Arrays.equals(journal.view().image(),stage==3?LATER:stage==2?AFTER:BEFORE),"killed process restores exact inventory revision");
            check((journal.view().pending()!=null)==(stage==1),"only unfinished reservation remains");
            check(journal.view().appliedCount()==(stage>=2?1:0),"dedup marker matches inventory after forced termination");
            check(journal.view().nextCounter()==(stage>=1?1:0),"counter survives process termination");
            journal.claim(2);
            if(stage>=2)check(!journal.commit(2,id(),RECEIPT,AFTER).newCommit(),"new anchor deduplicates committed operation");
            if(stage==1){journal.commit(2,id(),RECEIPT,AFTER);check(journal.view().revision()==1,"new anchor finishes reserved operation once");}
        }
    }
    private static void preserved(Path path,byte[] data) throws Exception {
        Files.write(path,data);rejects(() -> {try(var journal=open(path)){}});
        check(Arrays.equals(data,Files.readAllBytes(path)),"corruption rejection preserves every byte");
    }
    private interface Attempt {void run() throws Exception;}
    private static void rejects(Attempt action) throws Exception {
        try{action.run();}catch(IOException|IllegalStateException|IllegalArgumentException expected){checks++;return;}
        throw new AssertionError("Expected rejection");
    }
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
}
