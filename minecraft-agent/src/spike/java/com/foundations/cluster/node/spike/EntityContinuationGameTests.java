package com.foundations.cluster.node.spike;

import com.foundations.cluster.node.entity.EntityContinuationCodec;
import com.foundations.cluster.protocol.EntityOwnershipLedger;
import java.io.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.core.component.DataComponents;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.minecraft.gametest.framework.GameTest;

/** Bounded real save-NBT capture/validation; never creates an entity in destination world. */
@GameTestHolder("foundations_cluster_spike")
@PrefixGameTestTemplate(false)
public final class EntityContinuationGameTests {
    private static Cow cow(GameTestHelper h) {
        Cow c=EntityType.COW.create(h.getLevel());
        c.setPos(h.absolutePos(new BlockPos(1,2,1)).getCenter());return c;
    }
    private static void rejected(GameTestHelper h,Throwing fn)throws Exception {
        boolean got=false;try{fn.run();}catch(IOException|IllegalArgumentException e){got=true;}
        h.assertTrue(got,"Invalid snapshot must fail closed");
    }
    @FunctionalInterface interface Throwing {void run()throws Exception;}
    @GameTest(template="empty") public static void fullCowStateRemainsCanonical(GameTestHelper h)throws Exception {
        var c=cow(h);c.setCustomName(Component.literal("Port candidate"));c.setHealth(7);c.setAge(-500);
        c.setYRot(37);c.setDeltaMovement(.2,-.1,.15);
        byte[] bytes=EntityContinuationCodec.capture(c);
        var image=EntityContinuationCodec.decode(bytes,c.getUUID(),"minecraft:cow","minecraft:overworld");
        var saved=image.savedEntity();
        h.assertTrue(saved.getUUID("UUID").equals(c.getUUID())&&saved.getString("id").equals("minecraft:cow"),"Persistent UUID/type round trip");
        h.assertTrue(saved.contains("Health")&&saved.contains("Age")&&saved.contains("Motion"),"Full gameplay NBT, not only position, staged");
        h.assertTrue(image.checksum().equals(EntityOwnershipLedger.hash(bytes))&&bytes.length<=EntityContinuationCodec.MAX_IMAGE,"Bounded checksum image");
        h.assertTrue(c.getHealth()==7&&c.getAge()==-500&&!c.isRemoved(),"Capture did not alter real source");
        var second=EntityType.COW.create(h.getLevel());second.load(saved);
        h.assertTrue(second.getUUID().equals(c.getUUID())&&second.getHealth()==7&&second.getAge()==-500,
            "Vanilla load restores saved identity, health and age into detached entity");
        h.assertTrue(h.getLevel().getEntity(c.getUUID())==null,"Detached load never inserts duplicate native entity");
        h.succeed();
    }
    @GameTest(template="empty") public static void fullItemNbtPreservesComponents(GameTestHelper h)throws Exception {
        var stack=new ItemStack(Items.DIAMOND,13);stack.set(DataComponents.CUSTOM_NAME,Component.literal("Persist me"));
        var p=h.absolutePos(new BlockPos(1,2,1));
        var item=new ItemEntity(h.getLevel(),p.getX()+.5,p.getY()+1,p.getZ()+.5,stack);
        var bytes=EntityContinuationCodec.capture(item);
        var image=EntityContinuationCodec.decode(bytes,item.getUUID(),"minecraft:item","minecraft:overworld");
        var next=EntityType.ITEM.create(h.getLevel());next.load(image.savedEntity());
        h.assertTrue(next.getUUID().equals(item.getUUID())&&next.getItem().getCount()==13&&
            next.getItem().getHoverName().getString().equals("Persist me"),"Full saved item stack and persistent components");
        h.assertTrue(item.getItem().getCount()==13&&h.getLevel().getEntity(item.getUUID())==null,"No destination gameplay item spawned");
        h.succeed();
    }
    @GameTest(template="empty") public static void entityStateWrongIdentityAndTrailingBytesRejected(GameTestHelper h)throws Exception {
        var c=cow(h);byte[] image=EntityContinuationCodec.capture(c);
        rejected(h,()->EntityContinuationCodec.decode(image,UUID.randomUUID(),"minecraft:cow","minecraft:overworld"));
        rejected(h,()->EntityContinuationCodec.decode(image,c.getUUID(),"minecraft:chicken","minecraft:overworld"));
        rejected(h,()->EntityContinuationCodec.decode(image,c.getUUID(),"minecraft:cow","minecraft:the_nether"));
        rejected(h,()->EntityContinuationCodec.decode(Arrays.copyOf(image,image.length+1),c.getUUID(),"minecraft:cow","minecraft:overworld"));
        byte[] bad=image.clone();bad[bad.length-1]^=0x7f;
        rejected(h,()->EntityContinuationCodec.decode(bad,c.getUUID(),"minecraft:cow","minecraft:overworld"));
        h.assertTrue(h.getLevel().getEntity(c.getUUID())==null&&!c.isRemoved(),"Invalid images never modify world entities");
        h.succeed();
    }
}
