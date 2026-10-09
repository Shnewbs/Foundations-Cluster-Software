package com.foundations.cluster.node.spike;

import com.mojang.authlib.GameProfile;
import com.foundations.cluster.node.interaction.*;
import com.foundations.cluster.protocol.PlayerInventoryJournal;
import com.foundations.cluster.protocol.PlayerInventoryJournal.WorldReceipt;
import java.nio.file.*;
import java.util.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.*;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.*;

/** Reopened-session memory publication regression; not a real client reconnect. */
@GameTestHolder("foundations_cluster_spike")
@PrefixGameTestTemplate(false)
public final class InventoryRecoveryGameTests {
    @GameTest(template="empty") public static void duplicateRequiresLatestInventoryRestore(GameTestHelper h) throws Exception {
        Path root=Files.createTempDirectory("foundations-inventory-publication-");
        UUID cluster=UUID.randomUUID();
        var player=new FakePlayer(h.getLevel(),new GameProfile(UUID.randomUUID(),"restore-fixture"));
        try {
            player.getInventory().setItem(0,new ItemStack(Items.DIRT,2));
            var registries=h.getLevel().registryAccess();
            AnchorInventorySession.SlotDelta delta;
            var receipt=new WorldReceipt("test-only-synthetic-receipt",1,1,"a".repeat(64));
            try(var journal=new PlayerInventoryJournal(root.resolve("inventory"),cluster,player.getUUID())) {
                journal.initialize(1,InventoryImageCodec.encode(InventoryImageCodec.capture(player),registries));
                var session=new AnchorInventorySession(player,journal,1);
                var reserved=session.reserve(0);delta=session.prepareSlotDelta(reserved.id(),new ItemStack(Items.DIRT));
                session.apply(delta,receipt);
                player.getInventory().setItem(9,new ItemStack(Items.DIAMOND,17));session.recordLocalChange();
            }
            player.getInventory().clearContent();
            try(var journal=new PlayerInventoryJournal(root.resolve("inventory"),cluster,player.getUUID())) {
                journal.claim(2);var resumed=new AnchorInventorySession(player,journal,2);
                boolean rejected=false;
                try { resumed.apply(delta,receipt); } catch(IllegalStateException expected) { rejected=true; }
                h.assertTrue(rejected,"Dedup must not report completion before memory restoration");
                h.assertTrue(player.getInventory().getItem(0).isEmpty(),"Failed retry does not auto-overwrite memory");
                rejected=false;
                try { resumed.recordLocalChange(); } catch(IllegalStateException expected) { rejected=true; }
                h.assertTrue(rejected,"Missing publication fences local changes until explicit restore");
                resumed.restore();
                h.assertTrue(player.getInventory().getItem(0).getCount()==1&&player.getInventory().getItem(9).getCount()==17,"Restore uses newest image, never old delta");
                h.assertTrue(!resumed.apply(delta,receipt)&&journal.view().revision()==2,"Retry remains idempotent after explicit restore");
            }
        } finally {
            player.getAdvancements().stopListening();
            try(var paths=Files.walk(root)){for(Path file:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}
        }
        h.succeed();
    }
}
