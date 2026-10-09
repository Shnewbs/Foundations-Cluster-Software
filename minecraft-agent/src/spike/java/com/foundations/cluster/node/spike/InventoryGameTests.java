package com.foundations.cluster.node.spike;

import com.mojang.authlib.GameProfile;
import com.foundations.cluster.node.interaction.*;
import com.foundations.cluster.node.interaction.AnchorInventorySession.SlotDelta;
import com.foundations.cluster.protocol.PlayerInventoryJournal;
import com.foundations.cluster.protocol.PlayerInventoryJournal.*;
import com.foundations.cluster.replication.AuthorityResultJournal;
import com.foundations.cluster.replication.PlayerOperationLedger;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.*;
import net.minecraft.world.item.enchantment.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.*;

/** Real ItemStack/inventory and break/place paths. Synthetic FakePlayer identities
 * only; not connected-player, protection-mod, .dat reconciliation or seam proof.
 */
@GameTestHolder("foundations_cluster_spike")
@PrefixGameTestTemplate(false)
public final class InventoryGameTests {
    private static final UUID CLUSTER=UUID.fromString("ffffaaaa-0000-0000-0000-000000000001");
    private static final BlockPos TARGET=new BlockPos(2,2,2);
    private static ServerPlayer actor(GameTestHelper h,ItemStack hand) {
        var p=new FakePlayer(h.getLevel(),new GameProfile(UUID.randomUUID(),"inv-fixture"));
        p.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        var at=h.absolutePos(TARGET);p.moveTo(at.getX()+.5,at.getY(),at.getZ()+2,180,0);
        p.setItemInHand(InteractionHand.MAIN_HAND,hand.copy());return p;
    }
    private static final class Fixture implements AutoCloseable {
        final GameTestHelper h;final ServerPlayer p;final Path root,path;
        final PlayerInventoryJournal journal;final AnchorInventorySession session;
        Fixture(GameTestHelper h,ItemStack hand)throws Exception {
            this.h=h;this.p=actor(h,hand);root=Files.createTempDirectory("foundations-real-inventory-");path=root.resolve("inventory.wal");
            journal=new PlayerInventoryJournal(path,CLUSTER,p.getUUID());
            journal.initialize(1,InventoryImageCodec.encode(InventoryImageCodec.capture(p),h.getLevel().registryAccess()));
            session=new AnchorInventorySession(p,journal,1);
        }
        @Override public void close()throws Exception {
            journal.close();p.getAdvancements().stopListening();
            try(var paths=Files.walk(root)){for(Path file:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}
        }
    }
    private static WorldReceipt receipt(Fixture f,SlotDelta delta,List<ItemEntity> outputs)throws Exception {
        var result=new CompoundTag();result.putLong("operation",delta.id().counter());result.putInt("slot",delta.slot());
        result.putByteArray("beforeInventory",delta.before());result.putByteArray("afterInventory",delta.after());
        result.putLong("position",f.h.absolutePos(TARGET).asLong());result.put("block",NbtUtils.writeBlockState(f.h.getBlockState(TARGET)));
        result.put("blockAbove",NbtUtils.writeBlockState(f.h.getBlockState(TARGET.above())));
        result.putString("dimension",f.h.getLevel().dimension().location().toString());
        var entities=new ListTag();for(var entity:outputs){var tag=new CompoundTag();entity.save(tag);entities.add(tag);}result.put("entities",entities);
        var bytes=new ByteArrayOutputStream();NbtIo.write(result,new DataOutputStream(bytes));
        try(var authority=new AuthorityResultJournal(f.root.resolve("authority.wal"),CLUSTER,"fixture-cell")) {
            var key=new PlayerOperationLedger.OperationId(CLUSTER,f.p.getUUID(),delta.id().counter());
            var durable=authority.commit(key,7,bytes.toByteArray());
            return new WorldReceipt(durable.cell(),durable.epoch(),durable.frame(),durable.digest());
        }
    }
    private interface Attempt {void run()throws Exception;}
    private static void rejects(GameTestHelper h,Attempt action)throws Exception {
        try{action.run();}catch(IOException|IllegalStateException|IllegalArgumentException expected){return;}
        h.fail("Unsafe inventory operation was accepted");
    }
    @GameTest(template="empty") public static void realBreakWearsAnchorToolOnce(GameTestHelper h)throws Exception {
        try(var f=new Fixture(h,new ItemStack(Items.IRON_PICKAXE))) {
            h.setBlock(TARGET,Blocks.STONE);var reservation=f.session.reserve(0);
            var authority=actor(h,f.p.getMainHandItem());
            try(var events=new SurvivalGameTests.Events(authority,h.absolutePos(TARGET))) {
                events.id=new PlayerOperationLedger.OperationId(CLUSTER,f.p.getUUID(),reservation.id().counter());
                var request=new AuthorityBreakExecutor.PreparedBreak(h.absolutePos(TARGET),h.getBlockState(TARGET),authority.getMainHandItem(),0);
                h.assertTrue(AuthorityBreakExecutor.execute(authority,request,()->true)==AuthorityBreakExecutor.Outcome.EXECUTED,"Actual owner-side break executes");
                h.assertTrue(f.p.getMainHandItem().getDamageValue()==0,"Anchor is unchanged before durable world result");
                var delta=f.session.prepareSlotDelta(reservation.id(),authority.getMainHandItem());
                var proof=receipt(f,delta,events.drops);
                h.assertTrue(f.session.apply(delta,proof),"First durable player delta applies");
                h.assertTrue(f.p.getMainHandItem().getDamageValue()==1,"Real anchor tool wears once");
                h.assertTrue(!f.session.apply(delta,proof)&&f.p.getMainHandItem().getDamageValue()==1,"Retry cannot wear tool again");
                f.p.getInventory().setItem(9,new ItemStack(Items.DIAMOND,17));f.session.recordLocalChange();
                h.assertTrue(!f.session.apply(delta,proof)&&f.p.getInventory().getItem(9).getCount()==17,"Old retry cannot overwrite later inventory");
                h.assertTrue(events.breaks==1&&events.drops.size()==1,"No second game action or loot spawn");
                f.journal.close();f.p.getInventory().clearContent();
                try(var recovered=new PlayerInventoryJournal(f.path,CLUSTER,f.p.getUUID())) {
                    recovered.claim(2);var anchor=new AnchorInventorySession(f.p,recovered,2);anchor.restore();
                    h.assertTrue(f.p.getMainHandItem().getDamageValue()==1&&f.p.getInventory().getItem(9).getCount()==17,"Full real inventory restored with marker");
                    h.assertTrue(!anchor.apply(delta,proof),"Reopened new-epoch anchor rejects duplicate delta");
                    h.assertTrue(recovered.view().nextCounter()==1&&recovered.view().appliedCount()==1,"Persistent operation identity retained");
                }
            }
        }h.succeed();
    }
    @GameTest(template="empty") public static void realPlacementConsumesAnchorItemOnce(GameTestHelper h)throws Exception {
        try(var f=new Fixture(h,new ItemStack(Items.DIRT,2))) {
            h.setBlock(TARGET,Blocks.STONE);var reservation=f.session.reserve(0);var authority=actor(h,f.p.getMainHandItem());
            try {
                var pos=h.absolutePos(TARGET);var hit=new BlockHitResult(Vec3.atCenterOf(pos).add(0,.5,0),Direction.UP,pos,false);
                h.assertTrue(authority.gameMode.useItemOn(authority,h.getLevel(),authority.getMainHandItem(),InteractionHand.MAIN_HAND,hit).consumesAction(),"Actual Survival placement path");
                h.assertBlockPresent(Blocks.DIRT,TARGET.above());
                var delta=f.session.prepareSlotDelta(reservation.id(),authority.getMainHandItem());var proof=receipt(f,delta,List.of());
                f.session.apply(delta,proof);f.session.apply(delta,proof);
                h.assertTrue(f.p.getMainHandItem().getCount()==1&&f.journal.view().revision()==1,"Placement consumes exactly one anchor item");
            }finally{authority.getAdvancements().stopListening();}
        }h.succeed();
    }
    @GameTest(template="empty") public static void finalDurabilityCommitsEmptySlot(GameTestHelper h)throws Exception {
        var tool=new ItemStack(Items.IRON_PICKAXE);tool.setDamageValue(tool.getMaxDamage()-1);
        try(var f=new Fixture(h,tool)) {
            h.setBlock(TARGET,Blocks.STONE);var op=f.session.reserve(0);var authority=actor(h,tool);
            try(var events=new SurvivalGameTests.Events(authority,h.absolutePos(TARGET))) {
                var req=new AuthorityBreakExecutor.PreparedBreak(h.absolutePos(TARGET),h.getBlockState(TARGET),tool,0);
                h.assertTrue(AuthorityBreakExecutor.execute(authority,req,()->true)==AuthorityBreakExecutor.Outcome.EXECUTED,"Last tool use runs");
                h.assertTrue(authority.getMainHandItem().isEmpty(),"Actual game path consumed tool");
                var delta=f.session.prepareSlotDelta(op.id(),authority.getMainHandItem());var proof=receipt(f,delta,events.drops);
                f.session.apply(delta,proof);h.assertTrue(f.p.getMainHandItem().isEmpty(),"Atomic empty-slot result retained");
                f.session.restore();h.assertTrue(f.p.getMainHandItem().isEmpty(),"Restore does not resurrect broken tool");
            }
        }h.succeed();
    }
    @GameTest(template="empty") public static void changedToolAndSlotAreRejected(GameTestHelper h)throws Exception {
        try(var f=new Fixture(h,new ItemStack(Items.IRON_PICKAXE))) {
            var op=f.session.reserve(0);var worn=f.p.getMainHandItem().copy();worn.setDamageValue(1);
            var delta=f.session.prepareSlotDelta(op.id(),worn);var proof=receipt(f,delta,List.of());
            f.p.getMainHandItem().setDamageValue(7);rejects(h,()->f.session.apply(delta,proof));
            h.assertTrue(f.journal.view().pending()!=null&&f.journal.view().appliedCount()==0,"Illicit mutation cannot consume reservation/commit");
            f.p.getMainHandItem().setDamageValue(0);f.p.getInventory().selected=1;
            rejects(h,()->f.session.apply(delta,proof));f.p.getInventory().selected=0;
            f.session.apply(delta,proof);h.assertTrue(f.p.getMainHandItem().getDamageValue()==1,"Valid retry finishes once");
        }h.succeed();
    }
    @GameTest(template="empty") public static void duplicateAndLocalMutationGuards(GameTestHelper h)throws Exception {
        try(var f=new Fixture(h,new ItemStack(Items.DIRT,2))) {
            var op=f.session.reserve(0);
            h.assertTrue(!f.session.inventoryMutationAllowed(),"Reservation guards inventory mutation");
            rejects(h,()->f.session.reserve(40));rejects(h,f.session::recordLocalChange);
            var delta=f.session.prepareSlotDelta(op.id(),new ItemStack(Items.DIRT));var proof=receipt(f,delta,List.of());
            f.session.apply(delta,proof);h.assertTrue(f.session.inventoryMutationAllowed(),"Committed reservation is released");
            rejects(h,()->f.session.apply(delta,new WorldReceipt("wrong-cell",7,1,"a".repeat(64))));
        }h.succeed();
    }
    @GameTest(template="empty") public static void staleAnchorCannotApply(GameTestHelper h)throws Exception {
        try(var f=new Fixture(h,new ItemStack(Items.DIRT,2))) {
            var op=f.session.reserve(0);var delta=f.session.prepareSlotDelta(op.id(),new ItemStack(Items.DIRT));var proof=receipt(f,delta,List.of());
            f.journal.claim(2);rejects(h,()->f.session.apply(delta,proof));
            var resumed=new AnchorInventorySession(f.p,f.journal,2);resumed.apply(delta,proof);
            h.assertTrue(f.p.getMainHandItem().getCount()==1,"New anchor epoch completes same operation");
            var wrong=actor(h,ItemStack.EMPTY);
            try{rejects(h,()->new AnchorInventorySession(wrong,f.journal,2));}finally{wrong.getAdvancements().stopListening();}
        }h.succeed();
    }
    @GameTest(template="empty") public static void componentsArmorOffhandRoundTrip(GameTestHelper h)throws Exception {
        try(var f=new Fixture(h,new ItemStack(Items.IRON_PICKAXE))) {
            var tool=f.p.getMainHandItem();tool.setDamageValue(12);tool.set(DataComponents.CUSTOM_NAME,Component.literal("Foundations test pick"));
            tool.set(DataComponents.LORE,new ItemLore(List.of(Component.literal("Keep this component"))));
            var custom=new CompoundTag();custom.putString("Aa","first");custom.putString("BB","second");tool.set(DataComponents.CUSTOM_DATA,CustomData.of(custom));
            EnchantmentHelper.updateEnchantments(tool, enchants -> enchants.set(h.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.UNBREAKING),3));
            f.p.getInventory().setItem(36,new ItemStack(Items.DIAMOND_BOOTS));f.p.getInventory().setItem(40,new ItemStack(Items.TORCH,37));
            f.p.getInventory().setItem(20,new ItemStack(Items.DIAMOND,17));
            var before=InventoryImageCodec.capture(f.p);byte[] image=InventoryImageCodec.encode(before,h.getLevel().registryAccess());
            f.p.getInventory().clearContent();InventoryImageCodec.decode(image,h.getLevel().registryAccess()).install(f.p);
            for(int i=0;i<41;i++)h.assertTrue(ItemStack.matches(before.stack(i),f.p.getInventory().getItem(i)),"All real stack components/counts retained: "+i);
            var reordered=new CompoundTag();reordered.putString("BB","second");reordered.putString("Aa","first");f.p.getMainHandItem().set(DataComponents.CUSTOM_DATA,CustomData.of(reordered));
            h.assertTrue(Arrays.equals(image,InventoryImageCodec.encode(InventoryImageCodec.capture(f.p),h.getLevel().registryAccess())),"Canonical nested keys ignore insertion order");
        }h.succeed();
    }
    @GameTest(template="empty") public static void nonpersistentComponentsAreRejected(GameTestHelper h)throws Exception {
        try(var f=new Fixture(h,new ItemStack(Items.IRON_PICKAXE))) {
            f.p.getMainHandItem().set(SurvivalSpike.TRANSIENT_COMPONENT.get(),"not-persisted");
            rejects(h,()->InventoryImageCodec.encode(InventoryImageCodec.capture(f.p),h.getLevel().registryAccess()));
            h.assertTrue(f.p.getMainHandItem().has(SurvivalSpike.TRANSIENT_COMPONENT.get()),"Rejected encode does not strip transient component");
        }h.succeed();
    }
    @GameTest(template="empty") public static void cursorAndOversizedImagesAreRejected(GameTestHelper h)throws Exception {
        try(var f=new Fixture(h,new ItemStack(Items.DIRT,2))) {
            f.p.containerMenu.setCarried(new ItemStack(Items.DIAMOND));
            try{rejects(h,()->f.session.reserve(0));}finally{f.p.containerMenu.setCarried(ItemStack.EMPTY);}
            rejects(h,()->InventoryImageCodec.decode(new byte[PlayerInventoryJournal.MAX_IMAGE_BYTES+1],h.getLevel().registryAccess()));
            rejects(h,()->InventoryImageCodec.decode(new byte[]{10,0,0},h.getLevel().registryAccess()));
            h.assertTrue(f.journal.view().pending()==null,"Rejected cursor does not create reservation");
        }h.succeed();
    }
    @GameTest(template="empty") public static void malformedLastSlotCannotPartiallyApply(GameTestHelper h)throws Exception {
        try(var f=new Fixture(h,new ItemStack(Items.DIRT,2))) {
            var op=f.session.reserve(0);var delta=f.session.prepareSlotDelta(op.id(),new ItemStack(Items.DIRT));var proof=receipt(f,delta,List.of());
            var tag=NbtIo.read(new DataInputStream(new ByteArrayInputStream(delta.after())),NbtAccounter.create(16*1024*1024));
            var bad=new CompoundTag();bad.putString("id","foundations_cluster_spike:missing_item");bad.putInt("count",1);
            tag.getList("slots",Tag.TAG_COMPOUND).getCompound(40).put("stack",bad);
            var bytes=new ByteArrayOutputStream();NbtIo.write(tag,new DataOutputStream(bytes));
            var malformed=new SlotDelta(delta.id(),delta.beforeRevision(),delta.slot(),delta.before(),bytes.toByteArray());
            rejects(h,()->f.session.apply(malformed,proof));
            h.assertTrue(f.p.getMainHandItem().getCount()==2&&f.journal.view().appliedCount()==0,"Late decode failure cannot partially mutate early slot");
            h.assertTrue(f.journal.view().pending()!=null,"Durable reservation stays available for valid recovery");
        }h.succeed();
    }
    @GameTest(template="empty") public static void unreservedSlotChangeIsRejected(GameTestHelper h)throws Exception {
        try(var f=new Fixture(h,new ItemStack(Items.DIRT,2))) {
            var op=f.session.reserve(0);var delta=f.session.prepareSlotDelta(op.id(),new ItemStack(Items.DIRT));var proof=receipt(f,delta,List.of());
            var changed=InventoryImageCodec.decode(delta.after(),h.getLevel().registryAccess()).withSlot(9,new ItemStack(Items.DIAMOND));
            var invalid=new SlotDelta(delta.id(),0,0,delta.before(),InventoryImageCodec.encode(changed,h.getLevel().registryAccess()));
            rejects(h,()->f.session.apply(invalid,proof));h.assertTrue(f.p.getInventory().getItem(9).isEmpty(),"Unreserved grant rejected before journal write");
        }h.succeed();
    }
    @GameTest(template="empty") public static void offhandAndEmptyStackDeltas(GameTestHelper h)throws Exception {
        try(var f=new Fixture(h,ItemStack.EMPTY)) {
            f.p.getInventory().setItem(40,new ItemStack(Items.TORCH,2));f.session.recordLocalChange();
            var op=f.session.reserve(40);var delta=f.session.prepareSlotDelta(op.id(),ItemStack.EMPTY);var proof=receipt(f,delta,List.of());
            f.session.apply(delta,proof);f.session.restore();
            h.assertTrue(f.p.getOffhandItem().isEmpty()&&f.p.getMainHandItem().isEmpty(),"Offhand slot and empty main hand restore correctly");
        }h.succeed();
    }
}
