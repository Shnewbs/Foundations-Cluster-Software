package com.foundations.cluster.node.spike;

import com.foundations.cluster.node.FoundationsClusterNode;
import com.foundations.cluster.node.entity.*;
import com.foundations.cluster.protocol.EntityViewsWire.*;
import com.mojang.authlib.GameProfile;
import java.io.IOException;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.*;

/** Real 1.21.1 entity metadata/vanilla packet tests. Packet sinks are in memory;
 * these are NOT joined-client or two-live-worker acceptance. */
@GameTestHolder("foundations_cluster_spike")
@PrefixGameTestTemplate(false)
public final class EntityViewGameTests {
    private static Cow cow(GameTestHelper h) {
        Cow cow=EntityType.COW.create(h.getLevel());cow.setNoAi(true);
        var p=h.absolutePos(new BlockPos(1,2,1));cow.setPos(p.getX()+.125,p.getY(),p.getZ()+.25);return cow;
    }
    private static Pair pair(Entity e,UUID binding,Kind kind){return new Pair(binding,e.getUUID(),"worker-001","identity",123,e.level().dimension().location().toString(),kind,false);}
    private static Sample sample(Pair p,State s,long seq){return new Sample(p,s,new UUID(0,1),seq,1500);}
    private interface Attempt{void run()throws Exception;}
    private static void rejects(GameTestHelper h,Attempt action)throws Exception {
        try{action.run();}catch(IOException|IllegalArgumentException|IllegalStateException ex){return;}h.fail("Invalid paired-entity operation was accepted");
    }
    private static long count(ServerLevel level){long n=0;for(Entity ignored:level.getAllEntities())n++;return n;}
    @GameTest(template="empty") public static void autoEntityDiscoveryFindsLoadedCowWithoutPair(GameTestHelper h)throws Exception {
        var c=cow(h);h.assertTrue(h.getLevel().addFreshEntity(c),"Cow fixture is admitted to the real world");
        // Fresh entities only enter the native spatial index after the entity-tick pass.
        h.runAfterDelay(2,()->{
            var viewer=new Player(UUID.randomUUID(),"Observer",c.level().dimension().location().toString(),c.getX(),c.getY(),c.getZ());
            long before=count(h.getLevel());
            var data=AutomaticEntityDiscovery.collect(h.getLevel().getServer(),"worker-001",List.of(viewer),Set.of(),e->e.getUUID().equals(c.getUUID()),4);
            h.assertTrue(data.states().size()==1&&data.states().getFirst().entity().equals(c.getUUID()),
                "Loaded cow found by spatial index: results="+data.states().size()+" indexed="+(h.getLevel().getEntity(c.getUUID())==c));
            h.assertTrue(data.states().getFirst().binding().equals(com.foundations.cluster.protocol.EntityViewsWire.autoBinding("worker-001",c.getUUID())),"Auto UUID stable from worker/UUID");
            h.assertTrue(count(h.getLevel())==before&&!c.isRemoved(),"Discovery cannot spawn, remove or mutate live entities");h.succeed();
        });
    }
    @GameTest(template="empty") public static void autoEntityDiscoveryEnforcesAuthorityAndDistance(GameTestHelper h)throws Exception {
        var c=cow(h);h.assertTrue(h.getLevel().addFreshEntity(c),"Authority fixture cow admitted");
        h.runAfterDelay(2,()->{
            var origin=new Player(UUID.randomUUID(),"Observer",c.level().dimension().location().toString(),c.getX(),c.getY(),c.getZ());
            h.assertTrue(AutomaticEntityDiscovery.collect(h.getLevel().getServer(),"worker-001",List.of(origin),Set.of(),e->e.getUUID().equals(c.getUUID()),4).states().size()==1,"Positive source-index control");
            h.assertTrue(AutomaticEntityDiscovery.collect(h.getLevel().getServer(),"worker-001",List.of(origin),Set.of(),e->false,4).states().isEmpty(),"Unowned entities never sent");
            h.assertTrue(AutomaticEntityDiscovery.collect(h.getLevel().getServer(),"worker-001",List.of(origin),Set.of(c.getUUID()),e->true,4).states().stream().noneMatch(s->s.entity().equals(c.getUUID())),"Manual pairing takes precedence");
            var far=new Player(UUID.randomUUID(),"Far","minecraft:overworld",c.getX()+1000,c.getY(),c.getZ());
            h.assertTrue(AutomaticEntityDiscovery.collect(h.getLevel().getServer(),"worker-001",List.of(far),Set.of(),e->e.getUUID().equals(c.getUUID()),4).states().isEmpty(),"Far entity is not disclosed");
            var other=new Player(UUID.randomUUID(),"Dimension","minecraft:the_nether",c.getX(),c.getY(),c.getZ());
            h.assertTrue(AutomaticEntityDiscovery.collect(h.getLevel().getServer(),"worker-001",List.of(other),Set.of(),e->e.getUUID().equals(c.getUUID()),4).states().isEmpty(),"Dimension isolation");h.succeed();
        });
    }
    @GameTest(template="empty") public static void autoEntityDiscoveryDoesNotPairUnsupportedPig(GameTestHelper h)throws Exception {
        var c=cow(h);h.assertTrue(h.getLevel().addFreshEntity(c),"Cow fixture admitted");
        var pig=EntityType.PIG.create(h.getLevel());pig.setPos(c.getX()+1,c.getY(),c.getZ());h.assertTrue(h.getLevel().addFreshEntity(pig),"Pig fixture admitted");
        var fixtures=Set.of(c.getUUID(),pig.getUUID());
        h.runAfterDelay(2,()->{
            var center=new Player(UUID.randomUUID(),"Observer",c.level().dimension().location().toString(),c.getX(),c.getY(),c.getZ());
            var views=AutomaticEntityDiscovery.collect(h.getLevel().getServer(),"worker-001",List.of(center),Set.of(),e->fixtures.contains(e.getUUID()),4);
            h.assertTrue(views.states().size()==1&&views.states().getFirst().entity().equals(c.getUUID()),
                "Unsupported pig stays unpaired: actual views="+views.states().size());
            h.assertTrue(h.getLevel().getEntity(pig.getUUID())==pig,"Pig remains an independent native entity");h.succeed();
        });
    }
    @GameTest(template="empty") public static void autoEntityDiscoveryBoundedNearestStable(GameTestHelper h)throws Exception {
        var one=cow(h);h.assertTrue(h.getLevel().addFreshEntity(one),"Near cow admitted");
        var two=EntityType.COW.create(h.getLevel());two.setNoAi(true);two.setPos(one.getX()+3,one.getY(),one.getZ());h.assertTrue(h.getLevel().addFreshEntity(two),"Far cow admitted");
        var fixtures=Set.of(one.getUUID(),two.getUUID());
        h.runAfterDelay(2,()->{
            var pos=new Player(UUID.randomUUID(),"Observer",one.level().dimension().location().toString(),one.getX(),one.getY(),one.getZ());
            var a=AutomaticEntityDiscovery.collect(h.getLevel().getServer(),"worker-001",List.of(pos),Set.of(),e->fixtures.contains(e.getUUID()),1);
            var b=AutomaticEntityDiscovery.collect(h.getLevel().getServer(),"worker-001",List.of(pos),Set.of(),e->fixtures.contains(e.getUUID()),1);
            // State contains a byte[]; Java record/list equals would compare that array by reference.
            h.assertTrue(a.states().size()==1&&b.states().size()==1&&a.skipped()==1&&b.skipped()==1,
                "Two loaded authoritative cows produce one bounded nearest result, not zero");
            var s=a.states().getFirst(),t=b.states().getFirst();
            h.assertTrue(s.entity().equals(one.getUUID())&&t.entity().equals(one.getUUID())&&s.binding().equals(t.binding())&&
                Arrays.equals(s.metadata(),t.metadata()),"Nearest UUID and metadata bytes are stable across scans");h.succeed();
        });
    }
    @GameTest(template="empty") public static void entityViewCapturePreservesSourceAndDirtyTracker(GameTestHelper h)throws Exception {
        var cow=cow(h);cow.getEntityData().packDirty();cow.setCustomName(Component.literal("R2 cow"));cow.setAge(-1200);cow.setHealth(7);cow.setYRot(90);cow.setXRot(15);cow.setYHeadRot(45);cow.setDeltaMovement(.1,.2,.3);
        var state=EntityViewPackets.capture(cow,UUID.randomUUID());
        h.assertTrue(state.entity().equals(cow.getUUID())&&state.x()==cow.getX()&&state.yaw()==90&&state.vz()==.3,"UUID/position/rotation/velocity exact");
        h.assertTrue(cow.getEntityData().isDirty(),"Capture never drains native dirty metadata");
        h.assertTrue(cow.getHealth()==7&&cow.getAge()==-1200&&cow.getCustomName().getString().equals("R2 cow"),"Source gameplay state unchanged");h.succeed();
    }
    @GameTest(template="empty") public static void entityViewVanillaPacketCoordinatesAndIds(GameTestHelper h)throws Exception {
        var cow=cow(h);cow.setYRot(90);cow.setXRot(45);cow.setDeltaMovement(.25,0,0);
        var state=EntityViewPackets.capture(cow,UUID.randomUUID());int id=EntityViewPackets.allocateId();
        var add=EntityViewPackets.spawn(state,id);var move=EntityViewPackets.teleport(state,id);
        h.assertTrue(add.getId()==id&&add.getUUID().equals(cow.getUUID())&&add.getType()==EntityType.COW,"Spawn packet uses reserved ID and logical UUID");
        h.assertTrue(move.getId()==id&&move.getX()==cow.getX()&&move.getY()==cow.getY()&&move.getZ()==cow.getZ(),"Pinned teleport codec preserves doubles and target ID");
        h.assertTrue(move.getyRot()==64&&move.getxRot()==32,"Pinned rotation wire units are correct");
        h.assertTrue(EntityViewPackets.metadata(state,id,h.getLevel().registryAccess()).id()==id,"Metadata remapped to view ID");h.succeed();
    }
    @GameTest(template="empty") public static void entityViewRendererCreatesNoWorldObject(GameTestHelper h)throws Exception {
        var cow=cow(h);UUID binding=UUID.randomUUID();var pair=pair(cow,binding,Kind.COW);var state=EntityViewPackets.capture(cow,binding);
        var packets=new ArrayList<Packet<?>>();var renderer=new EntityViewRenderer(packets::add);long before=count(h.getLevel());
        renderer.apply(h.getLevel(),cow.position(),List.of(sample(pair,state,1)),1000,0);
        h.assertTrue(renderer.size()==1&&packets.stream().filter(p->p instanceof ClientboundAddEntityPacket).count()==1,"A packet view is published");
        for(int id:renderer.ids())h.assertTrue(h.getLevel().getEntity(id)==null,"No world object exists for view ID");
        h.assertTrue(h.getLevel().getEntity(cow.getUUID())==null&&count(h.getLevel())==before,"UUID registry and live entity count unchanged");renderer.clear();h.succeed();
    }
    @GameTest(template="empty") public static void entityViewMetadataResetsWithoutRespawn(GameTestHelper h)throws Exception {
        var cow=cow(h);cow.setCustomName(Component.literal("R2 cow"));cow.setAge(-1200);UUID binding=UUID.randomUUID();var pair=pair(cow,binding,Kind.COW);
        var first=EntityViewPackets.capture(cow,binding);var firstData=EntityViewPackets.metadata(first,0,h.getLevel().registryAccess()).packedItems();
        int nameId=firstData.stream().filter(v->v.value() instanceof Optional<?> o&&o.isPresent()&&o.get() instanceof Component c&&c.getString().equals("R2 cow")).findFirst().orElseThrow().id();
        var packets=new ArrayList<Packet<?>>();var renderer=new EntityViewRenderer(packets::add);renderer.apply(h.getLevel(),cow.position(),List.of(sample(pair,first,1)),1000,0);var ids=renderer.ids();packets.clear();
        cow.setCustomName(null);cow.setAge(0);cow.setPos(cow.getX()+1,cow.getY(),cow.getZ());var second=EntityViewPackets.capture(cow,binding);
        renderer.apply(h.getLevel(),cow.position(),List.of(sample(pair,second,2)),1100,0);
        var metadata=(ClientboundSetEntityDataPacket)packets.stream().filter(p->p instanceof ClientboundSetEntityDataPacket).findFirst().orElseThrow();
        h.assertTrue(metadata.packedItems().stream().anyMatch(v->v.id()==nameId&&Optional.empty().equals(v.value())),"Default-valued name clear is sent");
        h.assertTrue(ids.equals(renderer.ids())&&packets.stream().noneMatch(p->p instanceof ClientboundAddEntityPacket||p instanceof ClientboundRemoveEntitiesPacket),"Motion/metadata update keeps the same client view object");
        h.assertTrue(packets.stream().anyMatch(p->p instanceof ClientboundTeleportEntityPacket),"Motion update sent");renderer.clear();h.succeed();
    }
    @GameTest(template="empty") public static void entityViewItemCountAndComponentsArePresentationOnly(GameTestHelper h)throws Exception {
        var pos=h.absolutePos(new BlockPos(1,2,1));var stack=new ItemStack(Items.DIAMOND,17);stack.set(DataComponents.CUSTOM_NAME,Component.literal("Paired diamonds"));
        var item=new ItemEntity(h.getLevel(),pos.getX(),pos.getY(),pos.getZ(),stack);UUID binding=UUID.randomUUID();var pair=pair(item,binding,Kind.ITEM);var state=EntityViewPackets.capture(item,binding);
        var data=EntityViewPackets.metadata(state,9,h.getLevel().registryAccess());
        h.assertTrue(data.packedItems().stream().anyMatch(v->v.value() instanceof ItemStack s&&s.is(Items.DIAMOND)&&s.getCount()==17&&s.getHoverName().getString().equals("Paired diamonds")),"Item count/components survive actual packet codec");
        var packets=new ArrayList<Packet<?>>();var renderer=new EntityViewRenderer(packets::add);renderer.apply(h.getLevel(),item.position(),List.of(sample(pair,state,1)),1000,0);
        for(int id:renderer.ids())h.assertTrue(h.getLevel().getEntity(id)==null,"Remote item has no pickup-capable server object");
        h.assertTrue(item.getItem().getCount()==17&&!item.isRemoved(),"Source item remains untouched");renderer.clear();h.succeed();
    }
    @GameTest(template="empty") public static void entityViewRejectsUnsupportedEntitiesAndPassengers(GameTestHelper h)throws Exception {
        var pig=EntityType.PIG.create(h.getLevel());rejects(h,()->EntityViewPackets.capture(pig,UUID.randomUUID()));
        var cow=cow(h);var boat=EntityType.BOAT.create(h.getLevel());h.assertTrue(cow.startRiding(boat,true),"Passenger fixture mounted");
        rejects(h,()->EntityViewPackets.capture(cow,UUID.randomUUID()));h.assertTrue(cow.getVehicle()==boat,"Unsupported relation is never silently detached");h.succeed();
    }
    @GameTest(template="empty") public static void entityViewExpiryDuplicateCannotKeepStaleCopy(GameTestHelper h)throws Exception {
        var cow=cow(h);UUID id=UUID.randomUUID();var pair=pair(cow,id,Kind.COW);var state=EntityViewPackets.capture(cow,id);var packets=new ArrayList<Packet<?>>();var r=new EntityViewRenderer(packets::add);
        r.apply(h.getLevel(),cow.position(),List.of(sample(pair,state,1)),1000,0);r.apply(h.getLevel(),cow.position(),List.of(sample(pair,state,1)),2000,0);r.expire(2500);
        h.assertTrue(r.size()==0&&packets.stream().filter(p->p instanceof ClientboundRemoveEntitiesPacket).count()==1,"Duplicate sequence does not extend stale TTL");h.succeed();
    }
    @GameTest(template="empty") public static void entityViewDistanceAndEmptyBatchRemoveViews(GameTestHelper h)throws Exception {
        var cow=cow(h);UUID id=UUID.randomUUID();var pair=pair(cow,id,Kind.COW);var s=EntityViewPackets.capture(cow,id);var packets=new ArrayList<Packet<?>>();var r=new EntityViewRenderer(packets::add);
        r.apply(h.getLevel(),cow.position().add(1000,0,0),List.of(sample(pair,s,1)),1000,0);h.assertTrue(r.size()==0&&packets.isEmpty(),"Outside interest radius is not spawned");
        r.apply(h.getLevel(),cow.position(),List.of(sample(pair,s,2)),1100,0);r.apply(h.getLevel(),cow.position(),List.of(),1200,0);
        h.assertTrue(r.size()==0,"Absent source entry removes the display, never respawns an item");h.succeed();
    }
    @GameTest(template="empty") public static void entityViewOldSequenceAndProcessReset(GameTestHelper h)throws Exception {
        var cow=cow(h);UUID id=UUID.randomUUID();var pair=pair(cow,id,Kind.COW);var first=EntityViewPackets.capture(cow,id);var packets=new ArrayList<Packet<?>>();var r=new EntityViewRenderer(packets::add);
        r.apply(h.getLevel(),cow.position(),List.of(sample(pair,first,10)),1000,0);var ids=r.ids();packets.clear();cow.setPos(cow.getX()+1,cow.getY(),cow.getZ());var changed=EntityViewPackets.capture(cow,id);
        r.apply(h.getLevel(),cow.position(),List.of(sample(pair,changed,9)),1100,0);h.assertTrue(packets.isEmpty(),"Late sequence cannot roll back presentation");
        r.apply(h.getLevel(),cow.position(),List.of(new Sample(pair,changed,new UUID(0,2),1,1500)),1200,0);
        h.assertTrue(ids.equals(r.ids())&&packets.stream().anyMatch(p->p instanceof ClientboundTeleportEntityPacket),"Manager-validated new source instance resets sequence without duplicate view");r.clear();h.succeed();
    }
    @GameTest(template="empty") public static void entityViewNativeUuidCollisionNeverDeletesCow(GameTestHelper h)throws Exception {
        var cow=cow(h);UUID id=UUID.randomUUID();var pair=pair(cow,id,Kind.COW);var state=EntityViewPackets.capture(cow,id);var packets=new ArrayList<Packet<?>>();var r=new EntityViewRenderer(packets::add);
        r.apply(h.getLevel(),cow.position(),List.of(sample(pair,state,1)),1000,0);int viewId=r.ids().iterator().next();
        h.assertTrue(h.getLevel().addFreshEntity(cow),"Native collision fixture added");int realId=cow.getId();
        r.apply(h.getLevel(),cow.position(),List.of(sample(pair,state,2)),1100,0);
        h.assertTrue(r.size()==0&&r.collisions()==1&&realId!=viewId,"Conflicting visual hidden, native ID not reused");
        h.assertTrue(h.getLevel().getEntity(cow.getUUID())==cow&&!cow.isRemoved()&&cow.getX()==state.x(),"Real entity remains alive at its original location");cow.discard();h.succeed();
    }
    @GameTest(template="empty") public static void entityViewMalformedBatchHasNoPartialPublication(GameTestHelper h)throws Exception {
        var a=cow(h);var b=cow(h);UUID one=UUID.randomUUID(),two=UUID.randomUUID();var p1=pair(a,one,Kind.COW);var p2=pair(b,two,Kind.COW);var s=EntityViewPackets.capture(b,two);
        var bad=new State(two,b.getUUID(),Kind.COW,s.dimension(),s.x(),s.y(),s.z(),0,0,0,0,0,0,false,new byte[]{(byte)255});
        var packets=new ArrayList<Packet<?>>();var r=new EntityViewRenderer(packets::add);
        rejects(h,()->r.apply(h.getLevel(),a.position(),List.of(sample(p1,EntityViewPackets.capture(a,one),1),sample(p2,bad,1)),1000,0));
        h.assertTrue(packets.isEmpty()&&r.size()==0,"Malformed later metadata cannot partially spawn a batch");h.succeed();
    }
    @GameTest(template="empty") public static void entityViewSlowResponseIsNeverShown(GameTestHelper h)throws Exception {
        var cow=cow(h);UUID id=UUID.randomUUID();var p=pair(cow,id,Kind.COW);var s=EntityViewPackets.capture(cow,id);var packets=new ArrayList<Packet<?>>();var r=new EntityViewRenderer(packets::add);
        r.apply(h.getLevel(),cow.position(),List.of(sample(p,s,1)),1000,1001);h.assertTrue(r.size()==0&&packets.isEmpty(),"Slow response is not freshly relabeled");h.succeed();
    }
    @GameTest(template="empty") public static void entityViewIdsDoNotCollideAcrossViewersOrNativeEntities(GameTestHelper h)throws Exception {
        var cow=cow(h);UUID id=UUID.randomUUID();var p=pair(cow,id,Kind.COW);var s=EntityViewPackets.capture(cow,id);var one=new EntityViewRenderer(packet->{});var two=new EntityViewRenderer(packet->{});
        one.apply(h.getLevel(),cow.position(),List.of(sample(p,s,1)),1000,0);two.apply(h.getLevel(),cow.position(),List.of(sample(p,s,1)),1000,0);var nativeAfter=cow(h);
        var first=one.ids();var second=two.ids();h.assertTrue(Collections.disjoint(first,second)&&!first.contains(nativeAfter.getId())&&!second.contains(nativeAfter.getId()),"All IDs share native allocator without world objects");one.clear();two.clear();h.succeed();
    }
    @GameTest(template="empty") public static void entityViewCommandRegistrationAndSyntheticRefusal(GameTestHelper h)throws Exception {
        var p=new FakePlayer(h.getLevel(),new GameProfile(UUID.randomUUID(),"entity-fixture"));
        try {
            var dispatcher=h.getLevel().getServer().getCommands().getDispatcher();var root=dispatcher.getRoot().getChild("foundations_entities");
            h.assertTrue(root!=null&&!root.canUse(p.createCommandSourceStack().withPermission(0)),"Node registers operator-only entity preview commands");
            h.assertTrue(dispatcher.execute("foundations_entities view on",p.createCommandSourceStack().withPermission(2))==0,"Synthetic actor cannot opt in as a real player");
            h.assertTrue(!FoundationsClusterNode.entityViewSourceReady(h.getLevel(),h.absolutePos(BlockPos.ZERO)),"Standalone test mod never bypasses the live authority fence");
        }finally{p.getAdvancements().stopListening();}h.succeed();
    }
}
