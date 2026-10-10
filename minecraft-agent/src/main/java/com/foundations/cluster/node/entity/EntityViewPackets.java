package com.foundations.cluster.node.entity;

import com.foundations.cluster.node.mixin.EntityDataSnapshotAccessor;
import com.foundations.cluster.node.mixin.EntityIdAllocatorAccessor;
import com.foundations.cluster.protocol.EntityViewsWire;
import com.foundations.cluster.protocol.EntityViewsWire.*;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.util.*;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;

/** Minecraft 1.21.1 presentation adapter. Builds vanilla packets without creating,
 * adding, saving, loading or ticking a destination-side Entity object. */
public final class EntityViewPackets {
    private EntityViewPackets() {}
    public static Kind supported(Entity entity) {
        if(entity.isRemoved()||entity.isPassenger()||entity.isVehicle()||(entity instanceof Mob mob&&mob.isLeashed()))
            throw new IllegalArgumentException("Removed entities, passengers, vehicles and leashes are not supported by the paired-view preview");
        if(entity.getType()==EntityType.COW && entity.getClass()==Cow.class)return Kind.COW;
        if(entity.getType()==EntityType.ITEM && entity.getClass()==ItemEntity.class)return Kind.ITEM;
        throw new IllegalArgumentException("Pair preview supports vanilla cows and dropped items only");
    }
    public static State capture(Entity entity,UUID binding)throws IOException {
        Kind kind=supported(entity);
        if(!entity.level().getServer().isSameThread())throw new IllegalStateException("Entity capture requires the server thread");
        // Do not call packDirty(): that would consume native tracker updates.
        var data=new ArrayList<SynchedEntityData.DataValue<?>>();
        for(var item:((EntityDataSnapshotAccessor)(Object)entity.getEntityData()).foundations$items())
            if(item!=null)data.add(item.value());
        var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(256,EntityViewsWire.MAX_METADATA+1),entity.registryAccess());
        byte[] metadata;
        try {
            ClientboundSetEntityDataPacket.STREAM_CODEC.encode(buffer,new ClientboundSetEntityDataPacket(0,data));
            if(buffer.readableBytes()>EntityViewsWire.MAX_METADATA)throw new IOException("Entity metadata exceeds preview limit");
            metadata=new byte[buffer.readableBytes()];buffer.readBytes(metadata);
        }catch(RuntimeException e){throw new IOException("Entity metadata cannot be encoded within preview limits",e);}finally{buffer.release();}
        Vec3 v=entity.getDeltaMovement();
        return new State(binding,entity.getUUID(),kind,entity.level().dimension().location().toString(),entity.getX(),entity.getY(),entity.getZ(),
            entity.getYRot(),entity.getXRot(),entity.getYHeadRot(),v.x,v.y,v.z,entity.onGround(),metadata);
    }
    /** Reserve IDs from the same allocator used by ordinary server entities, never
     * guess a negative or high-ID namespace that a native entity could later reuse. */
    public static int allocateId() {
        return EntityIdAllocatorAccessor.foundations$counter().updateAndGet(n->{
            if(n<0||n>=Integer.MAX_VALUE-1)throw new IllegalStateException("Entity network-ID space exhausted");return n+1;
        });
    }
    public static ClientboundSetEntityDataPacket metadata(State state,int id,RegistryAccess registries)throws IOException {
        var buffer=new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(state.metadata()),registries);
        try {
            var packet=ClientboundSetEntityDataPacket.STREAM_CODEC.decode(buffer);
            if(buffer.isReadable()||packet.id()!=0||packet.packedItems().size()>254)throw new IOException("Invalid entity metadata envelope");
            Set<Integer> ids=new HashSet<>();for(var value:packet.packedItems())
                if(value.id()<0||value.id()>254||!ids.add(value.id()))throw new IOException("Duplicate/invalid entity data accessor");
            return new ClientboundSetEntityDataPacket(id,List.copyOf(packet.packedItems()));
        }catch(RuntimeException e){throw new IOException("Entity metadata decode failed",e);}finally{buffer.release();}
    }
    public static ClientboundAddEntityPacket spawn(State s,int id) {
        return new ClientboundAddEntityPacket(id,s.entity(),s.x(),s.y(),s.z(),s.pitch(),s.yaw(),
            s.kind()==Kind.COW?EntityType.COW:EntityType.ITEM,0,new Vec3(s.vx(),s.vy(),s.vz()),s.headYaw());
    }
    // These two packet types have entity-taking constructors in 1.21.1. Decode
    // their pinned vanilla wire form rather than constructing a fake world object.
    public static ClientboundTeleportEntityPacket teleport(State s,int id) {
        FriendlyByteBuf b=new FriendlyByteBuf(Unpooled.buffer());
        try{b.writeVarInt(id);b.writeDouble(s.x());b.writeDouble(s.y());b.writeDouble(s.z());b.writeByte(angle(s.yaw()));b.writeByte(angle(s.pitch()));b.writeBoolean(s.onGround());
            var packet=ClientboundTeleportEntityPacket.STREAM_CODEC.decode(b);if(b.isReadable())throw new IllegalStateException("Teleport packet schema changed");return packet;
        }finally{b.release();}
    }
    public static ClientboundRotateHeadPacket head(State s,int id) {
        FriendlyByteBuf b=new FriendlyByteBuf(Unpooled.buffer());
        try{b.writeVarInt(id);b.writeByte(angle(s.headYaw()));var p=ClientboundRotateHeadPacket.STREAM_CODEC.decode(b);
            if(b.isReadable())throw new IllegalStateException("Head packet schema changed");return p;
        }finally{b.release();}
    }
    public static ClientboundSetEntityMotionPacket motion(State s,int id){return new ClientboundSetEntityMotionPacket(id,new Vec3(s.vx(),s.vy(),s.vz()));}
    private static byte angle(float degrees){return (byte)Math.floor(degrees*256.0F/360.0F);}
    public static boolean moved(State a,State b){return a.x()!=b.x()||a.y()!=b.y()||a.z()!=b.z()||a.yaw()!=b.yaw()||a.pitch()!=b.pitch()||a.onGround()!=b.onGround();}
    public static boolean velocityChanged(State a,State b){return a.vx()!=b.vx()||a.vy()!=b.vy()||a.vz()!=b.vz();}
}
