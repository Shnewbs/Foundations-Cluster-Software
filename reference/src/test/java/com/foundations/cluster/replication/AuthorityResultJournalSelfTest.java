package com.foundations.cluster.replication;

import java.nio.file.*;
import java.nio.ByteBuffer;
import java.util.*;
import com.foundations.cluster.replication.PlayerOperationLedger.*;

public final class AuthorityResultJournalSelfTest {
    private static int checks;
    static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); checks++; }
    interface Attempt { void run() throws Exception; }
    static void rejects(Attempt action) throws Exception {
        try { action.run(); } catch (java.io.IOException | IllegalStateException | IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError("Expected rejection");
    }
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("foundations-authority-journal-");
        UUID cluster = UUID.randomUUID(), player = UUID.randomUUID();
        OperationId id = new OperationId(cluster, player, 0);
        Path path = dir.resolve("cell.wal"); byte[] payload = {1,2,3}; Result receipt;
        try {
            try (var journal = new AuthorityResultJournal(path, cluster, "cell-0")) {
                var r = journal.commit(id, 7, payload); receipt = r.receipt();
                check(r.frame() == 1 && r.digest().length() == 64, "First durable frame");
                payload[0] = 9; check(r.payload()[0] == 1, "Commit copies payload");
                byte[] exposed = r.payload(); exposed[1] = 9; check(r.payload()[1] == 2, "Read copies payload");
                long size = Files.size(path);
                check(journal.commit(id, 7, new byte[]{1,2,3}).receipt().equals(receipt), "Retry reuses durable receipt");
                check(size == Files.size(path), "Retry does not append");
                rejects(()->journal.commit(id, 7, new byte[]{9}));
                rejects(()->journal.commit(id, 8, new byte[]{1,2,3}));
                rejects(()->journal.commit(new OperationId(UUID.randomUUID(),player,1),7,new byte[]{1}));
                rejects(()->journal.commit(new OperationId(cluster,player,1),6,new byte[]{1}));
                rejects(()->journal.commit(new OperationId(cluster,player,1),7,new byte[0]));
                rejects(()->journal.commit(new OperationId(cluster,player,1),7,new byte[2*1024*1024]));
                rejects(()->new AuthorityResultJournal(path,cluster,"cell-0"));
            }
            try (var journal = new AuthorityResultJournal(path,cluster,"cell-0")) {
                check(journal.find(id).orElseThrow().receipt().equals(receipt), "Recovery returns same receipt");
                check(journal.commit(new OperationId(cluster,player,1),8,new byte[]{4}).frame()==2, "Sequence survives reopen");
                check(journal.retained().size()==2, "No unsafe compaction");
            }
            rejects(()->new AuthorityResultJournal(path,cluster,"wrong-cell"));
            rejects(()->new AuthorityResultJournal(path,UUID.randomUUID(),"cell-0"));
            byte[] good=Files.readAllBytes(path);
            for(byte[] tail:List.of(new byte[]{0,0}, ByteBuffer.allocate(7).putInt(20).put(new byte[]{1,2,3}).array())) {
                Files.write(path,good); Files.write(path,tail,StandardOpenOption.APPEND);
                try(var journal=new AuthorityResultJournal(path,cluster,"cell-0")) {
                    check(journal.retained().size()==2,"Incomplete tail retains both results");
                    check(Files.size(path)==good.length,"Incomplete tail truncated");
                }
            }
            Files.write(path,good);byte[] corrupt=good.clone();corrupt[20]^=1;Files.write(path,corrupt);
            rejects(()->new AuthorityResultJournal(path,cluster,"cell-0"));
            check(Arrays.equals(Files.readAllBytes(path),corrupt),"Complete corrupt record never silently truncated");
            Files.write(path,good);
            // Actual authority receipt gates the player model after independent reopen.
            Path playerPath=dir.resolve("player.wal");
            try(var ledger=new PlayerOperationLedger(playerPath,cluster,player)) {
                var op=ledger.prepare(new Reservation(0,0,"minecraft:iron_pickaxe",1));
                check(op.id().equals(id),"Persistent identities agree");
                ledger.advance(id,Stage.WORLD_EXECUTED,null);
                ledger.advance(id,Stage.WORLD_RESULT_DURABLE,receipt);
            }
            try(var journal=new AuthorityResultJournal(path,cluster,"cell-0");
                var ledger=new PlayerOperationLedger(playerPath,cluster,player)) {
                var r=journal.find(id).orElseThrow().receipt();
                check(ledger.get(id).result().equals(r),"World and player recovery agree");
                ledger.advance(id,Stage.PLAYER_COMMITTED,r);
                rejects(()->ledger.acknowledge(id,"cell-0",7,0));
                ledger.acknowledge(id,"cell-0",7,1);
                check(ledger.unfinished().isEmpty(),"Recovered operation resolves");
            }
            var closed=new AuthorityResultJournal(path,cluster,"cell-0");closed.close();
            rejects(()->closed.commit(id,7,new byte[]{1,2,3}));
            rejects(()->closed.find(id));
            System.out.println("Authority result journal: PASS ("+checks+" checks)");
        } finally {try(var paths=Files.walk(dir)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
    }
}
