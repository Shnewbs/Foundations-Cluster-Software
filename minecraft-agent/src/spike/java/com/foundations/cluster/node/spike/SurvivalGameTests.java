package com.foundations.cluster.node.spike;

import com.mojang.authlib.GameProfile;
import com.foundations.cluster.node.interaction.AuthorityBreakExecutor;
import com.foundations.cluster.node.interaction.AuthorityBreakExecutor.*;
import com.foundations.cluster.replication.*;
import com.foundations.cluster.replication.PlayerOperationLedger.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.*;
import net.minecraft.server.network.*;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.level.*;
import net.neoforged.neoforge.gametest.*;

/** Isolated test mod: never included in the distributed Node JAR. */
@GameTestHolder("foundations_cluster_spike")
@PrefixGameTestTemplate(false)
public final class SurvivalGameTests {
    private static final BlockPos TARGET = new BlockPos(2, 2, 2);
    private static final UUID CLUSTER = UUID.fromString("caa7f5ba-e22b-4c90-a48d-531ab5e13bde");

    private static ServerPlayer actor(GameTestHelper h, boolean fake, ItemStack tool) {
        var level = h.getLevel();
        var profile = new GameProfile(UUID.randomUUID(), fake ? "spike-fake" : "spike-detached");
        ServerPlayer p;
        if (fake) p = new FakePlayer(level, profile);
        else {
            p = new ServerPlayer(level.getServer(), level, profile, ClientInformation.createDefault());
            // No login, network socket, player-list entry or player save. This is a
            // comparison actor, NOT approved for use with a real player's UUID.
            p.connection = new ServerGamePacketListenerImpl(level.getServer(), new Connection(PacketFlow.SERVERBOUND), p,
                    CommonListenerCookie.createInitial(profile, false)) {
                @Override public void send(Packet<?> packet) {}
                @Override public void send(Packet<?> packet, PacketSendListener listener) {}
            };
        }
        p.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        var pos = h.absolutePos(TARGET);
        p.moveTo(pos.getX() + .5, pos.getY(), pos.getZ() + 2, 180, 0);
        p.setItemInHand(InteractionHand.MAIN_HAND, tool.copy());
        h.assertTrue(!level.players().contains(p) && !level.getServer().getPlayerList().getPlayers().contains(p), "Actor must not become an online player");
        return p;
    }
    private static PreparedBreak prepared(GameTestHelper h, ServerPlayer p) {
        return new PreparedBreak(h.absolutePos(TARGET), h.getBlockState(TARGET), p.getMainHandItem(), p.getInventory().selected);
    }
    private static void dispose(ServerPlayer p) { p.getAdvancements().stopListening(); }

    public static final class Events implements AutoCloseable {
        final ServerPlayer actor; final BlockPos pos;
        final List<ItemEntity> drops = new ArrayList<>();
        boolean cancelBreak, cancelDrops, rejectFake;
        int breaks, dropEvents;
        OperationId id;
        Events(ServerPlayer actor, BlockPos pos) { this.actor=actor;this.pos=pos;NeoForge.EVENT_BUS.register(this); }
        @SubscribeEvent public void breaking(BlockEvent.BreakEvent e) {
            if(e.getPlayer()!=actor || !e.getPos().equals(pos))return;
            breaks++;
            if(cancelBreak || (rejectFake && actor.isFakePlayer()))e.setCanceled(true);
        }
        @SubscribeEvent public void dropping(BlockDropsEvent e) {
            if(e.getBreaker()!=actor || !e.getPos().equals(pos))return;
            dropEvents++;
            if(cancelDrops){e.setCanceled(true);return;}
            for(var drop:e.getDrops()) {
                if(id!=null)drop.setUUID(id.outputEntityId(drops.size()));
                drops.add(drop);
            }
        }
        @Override public void close(){NeoForge.EVENT_BUS.unregister(this);dispose(actor);}
    }
    private static void breakCase(GameTestHelper h, boolean fake, ItemStack tool, Block block, Item drop, int expectedDamage) {
        h.setBlock(TARGET,block);var p=actor(h,fake,tool);
        try(var events=new Events(p,h.absolutePos(TARGET))) {
            var result=AuthorityBreakExecutor.execute(p,prepared(h,p),()->true);
            h.assertTrue(result==Outcome.BROKEN,"Actual Survival break: "+result);
            h.assertBlockPresent(Blocks.AIR,TARGET);
            h.assertTrue(events.breaks==1,"BreakEvent runs exactly once");
            h.assertTrue(p.getMainHandItem().getDamageValue()==expectedDamage,"Tool wear follows Survival semantics");
            h.assertTrue(events.drops.size()==(drop==null?0:1),"Harvest/drop count");
            if(drop!=null)h.assertTrue(events.drops.getFirst().getItem().is(drop),"Correct vanilla loot");
            int stats=p.getStats().getValue(Stats.BLOCK_MINED.get(block));
            h.assertTrue(stats==(fake?0:drop==null?0:1),"FakePlayer stats suppression must be explicit");
        }
        h.succeed();
    }
    @GameTest(template="empty") public static void fakeIronStone(GameTestHelper h){breakCase(h,true,new ItemStack(Items.IRON_PICKAXE),Blocks.STONE,Items.COBBLESTONE,1);}
    @GameTest(template="empty") public static void detachedIronStone(GameTestHelper h){breakCase(h,false,new ItemStack(Items.IRON_PICKAXE),Blocks.STONE,Items.COBBLESTONE,1);}
    @GameTest(template="empty") public static void fakeHandStone(GameTestHelper h){breakCase(h,true,ItemStack.EMPTY,Blocks.STONE,null,0);}
    @GameTest(template="empty") public static void detachedHandStone(GameTestHelper h){breakCase(h,false,ItemStack.EMPTY,Blocks.STONE,null,0);}
    @GameTest(template="empty") public static void fakeShovelDirt(GameTestHelper h){breakCase(h,true,new ItemStack(Items.IRON_SHOVEL),Blocks.DIRT,Items.DIRT,1);}
    @GameTest(template="empty") public static void detachedShovelDirt(GameTestHelper h){breakCase(h,false,new ItemStack(Items.IRON_SHOVEL),Blocks.DIRT,Items.DIRT,1);}

    private static void cancellation(GameTestHelper h,boolean fake,boolean dropsOnly) {
        h.setBlock(TARGET,Blocks.STONE);var p=actor(h,fake,new ItemStack(Items.IRON_PICKAXE));
        try(var events=new Events(p,h.absolutePos(TARGET))) {
            events.cancelBreak=!dropsOnly;events.cancelDrops=dropsOnly;
            h.assertTrue(AuthorityBreakExecutor.execute(p,prepared(h,p),()->true)==(dropsOnly?Outcome.BROKEN:Outcome.CANCELLED),"Cancellation outcome");
            h.assertBlockPresent(dropsOnly?Blocks.AIR:Blocks.STONE,TARGET);
            h.assertTrue(p.getMainHandItem().getDamageValue()==(dropsOnly?1:0),"Cancelled break cannot wear tool");
            h.assertTrue(h.getLevel().getEntitiesOfClass(ItemEntity.class,new AABB(h.absolutePos(TARGET)).inflate(.7)).isEmpty(),"No drops after cancellation");
        }h.succeed();
    }
    @GameTest(template="empty") public static void fakeCancelledBreak(GameTestHelper h){cancellation(h,true,false);}
    @GameTest(template="empty") public static void detachedCancelledBreak(GameTestHelper h){cancellation(h,false,false);}
    @GameTest(template="empty") public static void fakeCancelledDrops(GameTestHelper h){cancellation(h,true,true);}
    @GameTest(template="empty") public static void detachedCancelledDrops(GameTestHelper h){cancellation(h,false,true);}

    @GameTest(template="empty") public static void preconditions(GameTestHelper h) {
        h.setBlock(TARGET,Blocks.STONE);var p=actor(h,true,new ItemStack(Items.IRON_PICKAXE));
        try(var events=new Events(p,h.absolutePos(TARGET))) {
            var req=prepared(h,p);
            h.assertTrue(AuthorityBreakExecutor.execute(p,req,()->false)==Outcome.FENCED,"Stale epoch fenced");
            p.getMainHandItem().setDamageValue(1);
            h.assertTrue(AuthorityBreakExecutor.execute(p,req,()->true)==Outcome.STALE_TOOL,"Components form part of reservation");
            p.getMainHandItem().setDamageValue(0);p.getInventory().selected=1;
            h.assertTrue(AuthorityBreakExecutor.execute(p,req,()->true)==Outcome.STALE_TOOL,"Held slot swap rejected");
            p.getInventory().selected=0;h.setBlock(TARGET,Blocks.DIRT);
            h.assertTrue(AuthorityBreakExecutor.execute(p,req,()->true)==Outcome.STALE_BLOCK,"Changed world precondition rejected");
            h.setBlock(TARGET,Blocks.STONE);p.gameMode.changeGameModeForPlayer(GameType.CREATIVE);
            h.assertTrue(AuthorityBreakExecutor.execute(p,req,()->true)==Outcome.WRONG_MODE,"No Creative substitution");
            p.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);p.moveTo(p.getX()+12,p.getY(),p.getZ());
            h.assertTrue(AuthorityBreakExecutor.execute(p,req,()->true)==Outcome.OUT_OF_REACH,"Reach enforced");
            h.assertTrue(events.breaks==0 && events.drops.isEmpty(),"Rejected preconditions have no gameplay effects");
            h.assertBlockPresent(Blocks.STONE,TARGET);
        }h.succeed();
    }
    @GameTest(template="empty") public static void expiredLeaseAndUnloadedTarget(GameTestHelper h) {
        h.setBlock(TARGET,Blocks.STONE);var p=actor(h,true,new ItemStack(Items.IRON_PICKAXE));
        try(var events=new Events(p,h.absolutePos(TARGET))) {
            var checks=new java.util.concurrent.atomic.AtomicInteger();
            h.assertTrue(AuthorityBreakExecutor.execute(p,prepared(h,p),()->checks.getAndIncrement()==0)==Outcome.FENCED,
                "Lease checked again immediately before game action");
            var far=new BlockPos(27000000,64,27000000);
            p.moveTo(far.getX()+.5,far.getY(),far.getZ()+2);
            var request=new PreparedBreak(far,Blocks.STONE.defaultBlockState(),p.getMainHandItem(),0);
            h.assertTrue(!h.getLevel().hasChunkAt(far),"Fixture target must begin unloaded");
            h.assertTrue(AuthorityBreakExecutor.execute(p,request,()->true)==Outcome.UNLOADED,"Unloaded target rejected");
            h.assertTrue(!h.getLevel().hasChunkAt(far) && events.breaks==0,"Rejection cannot load or mutate the target");
        }h.succeed();
    }
    @GameTest(template="empty") public static void bedrockRejected(GameTestHelper h) {
        h.setBlock(TARGET,Blocks.BEDROCK);var p=actor(h,true,new ItemStack(Items.NETHERITE_PICKAXE));
        try(var events=new Events(p,h.absolutePos(TARGET))) {
            h.assertTrue(AuthorityBreakExecutor.execute(p,prepared(h,p),()->true)==Outcome.UNBREAKABLE,"Bedrock must stay unbreakable");
            h.assertTrue(events.breaks==0,"No break event for impossible intent");
        }h.succeed();
    }
    @GameTest(template="empty") public static void fakePlayerProtectionDivergence(GameTestHelper h) {
        h.setBlock(TARGET,Blocks.STONE);
        var fake=actor(h,true,new ItemStack(Items.IRON_PICKAXE));
        try(var events=new Events(fake,h.absolutePos(TARGET))) {
            events.rejectFake=true;
            h.assertTrue(AuthorityBreakExecutor.execute(fake,prepared(h,fake),()->true)==Outcome.CANCELLED,"Automation-denying protection must remain authoritative");
        }
        var detached=actor(h,false,new ItemStack(Items.IRON_PICKAXE));
        try(var events=new Events(detached,h.absolutePos(TARGET))) {
            events.rejectFake=true;
            h.assertTrue(AuthorityBreakExecutor.execute(detached,prepared(h,detached),()->true)==Outcome.BROKEN,"Record actor-policy divergence, never bypass it");
        }h.succeed();
    }
    @GameTest(template="empty") public static void nearlyBrokenTool(GameTestHelper h) {
        h.setBlock(TARGET,Blocks.STONE);ItemStack tool=new ItemStack(Items.IRON_PICKAXE);tool.setDamageValue(tool.getMaxDamage()-1);
        var p=actor(h,true,tool);
        try(var events=new Events(p,h.absolutePos(TARGET))) {
            h.assertTrue(AuthorityBreakExecutor.execute(p,prepared(h,p),()->true)==Outcome.BROKEN,"Last use breaks block");
            h.assertTrue(p.getMainHandItem().isEmpty(),"Last durability consumes tool");
            h.assertTrue(events.drops.size()==1,"Last tool use still produces valid harvest");
        }h.succeed();
    }
    private static void placeUse(GameTestHelper h,boolean fake) {
        h.setBlock(TARGET,Blocks.STONE);var p=actor(h,fake,new ItemStack(Items.DIRT,2));
        try {
            BlockPos pos=h.absolutePos(TARGET);
            var hit=new BlockHitResult(Vec3.atCenterOf(pos).add(0,.5,0),Direction.UP,pos,false);
            var result=p.gameMode.useItemOn(p,h.getLevel(),p.getMainHandItem(),InteractionHand.MAIN_HAND,hit);
            h.assertTrue(result.consumesAction(),"Placement enters actual NeoForge path");
            h.assertBlockPresent(Blocks.DIRT,TARGET.above());
            h.assertTrue(p.getMainHandItem().getCount()==1,"Survival placement consumes exactly one item");
            h.setBlock(TARGET.above(),Blocks.LEVER);
            p.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);
            var leverHit=new BlockHitResult(Vec3.atCenterOf(pos.above()),Direction.UP,pos.above(),false);
            p.gameMode.useItemOn(p,h.getLevel(),p.getMainHandItem(),InteractionHand.MAIN_HAND,leverHit);
            h.assertTrue(h.getBlockState(TARGET.above()).getValue(LeverBlock.POWERED),"Block use activates lever");
        } finally {dispose(p);}h.succeed();
    }
    @GameTest(template="empty") public static void fakePlaceAndUse(GameTestHelper h){placeUse(h,true);}
    @GameTest(template="empty") public static void detachedPlaceAndUse(GameTestHelper h){placeUse(h,false);}

    @GameTest(template="empty") public static void durableBreakReceipt(GameTestHelper h) throws Exception {
        h.setBlock(TARGET,Blocks.STONE);var p=actor(h,true,new ItemStack(Items.IRON_PICKAXE));
        Path folder=Files.createTempDirectory("foundations-real-break-");
        Path authority=folder.resolve("authority.wal"), player=folder.resolve("player.wal");
        OperationId id;Result receipt;byte[] worldResult;
        try(var events=new Events(p,h.absolutePos(TARGET))) {
            try(var ledger=new PlayerOperationLedger(player,CLUSTER,p.getUUID());var journal=new AuthorityResultJournal(authority,CLUSTER,"fixture")) {
                id=ledger.prepare(new Reservation(0,0,p.getMainHandItem().save(h.getLevel().registryAccess()).toString(),1)).id();
                events.id=id;
                h.assertTrue(AuthorityBreakExecutor.execute(p,prepared(h,p),()->true)==Outcome.BROKEN,"Execute real break before world receipt");
                ledger.advance(id,Stage.WORLD_EXECUTED,null);
                CompoundTag result=new CompoundTag();result.putInt("schema",1);
                result.put("afterTool",p.getMainHandItem().save(h.getLevel().registryAccess()));
                result.put("afterBlock",NbtUtils.writeBlockState(h.getBlockState(TARGET)));
                ListTag drops=new ListTag();for(var drop:events.drops){CompoundTag tag=new CompoundTag();drop.save(tag);drops.add(tag);}
                result.put("drops",drops);
                var bytes=new ByteArrayOutputStream();NbtIo.write(result,new DataOutputStream(bytes));worldResult=bytes.toByteArray();
                receipt=journal.commit(id,3,worldResult).receipt();
                ledger.advance(id,Stage.WORLD_RESULT_DURABLE,receipt);
                h.assertTrue(events.drops.getFirst().getUUID().equals(id.outputEntityId(0)),"Output identity assigned before entity joins world");
            }
            // No second game action on retry: find the immutable, persisted result.
            try(var journal=new AuthorityResultJournal(authority,CLUSTER,"fixture");var ledger=new PlayerOperationLedger(player,CLUSTER,p.getUUID())) {
                var recovered=journal.find(id).orElseThrow();
                h.assertTrue(Arrays.equals(recovered.payload(),worldResult),"Real tool/world/entity NBT survived independent reopen");
                h.assertTrue(journal.commit(id,3,worldResult).receipt().equals(receipt),"Retry has same receipt");
                h.assertTrue(ledger.get(id).stage()==Stage.WORLD_RESULT_DURABLE,"Player reservation survives restart");
                h.assertTrue(ledger.slotReserved(0),"Reservation stays until actual player commit");
                h.assertTrue(events.breaks==1 && events.drops.size()==1,"Journal retry never re-executes break");
            }
        }finally{try(var paths=Files.walk(folder)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
        h.succeed();
    }
}
