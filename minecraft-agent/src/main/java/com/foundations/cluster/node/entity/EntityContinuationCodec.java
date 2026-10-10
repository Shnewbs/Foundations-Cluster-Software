package com.foundations.cluster.node.entity;

import com.foundations.cluster.protocol.EntityOwnershipLedger;
import java.io.*;
import java.util.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/**
 * Bounded full persistent entity-state envelope for staging at an authority
 * boundary. Captures Minecraft's real save NBT (not client-only metadata).
 *
 * IMPORTANT: This is a capture/readback adapter, not an entity teleport or
 * permission to spawn a second simulation. Production promotion requires a
 * durable destination state receipt, single-writer owner generation CAS,
 * checked source demotion, entity-storage reconciliation and restart tests.
 * No restore method is exposed to live callers.
 */
public final class EntityContinuationCodec {
    private EntityContinuationCodec() {}
    private static final int SCHEMA = 1;
    public static final int MAX_IMAGE = EntityOwnershipLedger.MAX_IMAGE;
    public record Continuation(UUID entity,String dimension,String type,long capturedGameTime,
                               CompoundTag savedEntity,String checksum) {
        public Continuation {
            Objects.requireNonNull(entity);Objects.requireNonNull(dimension);
            Objects.requireNonNull(type);Objects.requireNonNull(savedEntity);
            Objects.requireNonNull(checksum);
            savedEntity=savedEntity.copy();
        }
        @Override public CompoundTag savedEntity() {return savedEntity.copy();}
    }
    private static final class BoundedOutput extends ByteArrayOutputStream {
        @Override public synchronized void write(int b) {
            if(count>=MAX_IMAGE)throw new IllegalArgumentException("Entity state exceeds staging cap");
            super.write(b);
        }
        @Override public synchronized void write(byte[] bytes,int start,int size){
            if(size<0||count>MAX_IMAGE-size)throw new IllegalArgumentException("Entity state exceeds staging cap");
            super.write(bytes,start,size);
        }
    }
    private static byte[] write(CompoundTag tag)throws IOException {
        var buf=new BoundedOutput();
        try(var out=new DataOutputStream(buf)) {NbtIo.write(tag,out);}
        catch(RuntimeException e){throw new IOException("Entity state failed bounds/encoding check",e);}
        byte[] image=buf.toByteArray();
        if(image.length==0||image.length>MAX_IMAGE)throw new IOException("Invalid entity image length");
        return image;
    }
    private static CompoundTag read(byte[] image)throws IOException {
        if(image==null||image.length==0||image.length>MAX_IMAGE)throw new IOException("Invalid entity image length");
        try(var input=new DataInputStream(new ByteArrayInputStream(image))) {
            CompoundTag tag=NbtIo.read(input,NbtAccounter.create(4L*MAX_IMAGE));
            if(tag==null||input.available()!=0)throw new IOException("Missing/trailing entity NBT");
            return tag;
        }catch(RuntimeException e){throw new IOException("Invalid staged entity NBT",e);}
    }
    /** Call only on source's main thread for a currently-owned supported entity. */
    public static byte[] capture(Entity entity)throws IOException {
        Objects.requireNonNull(entity);
        if(!(entity.level() instanceof ServerLevel level)||!level.getServer().isSameThread())
            throw new IllegalStateException("Entity snapshot requires live source server thread");
        EntityViewPackets.supported(entity); // exact certified vanilla types; no passengers/leashes
        var state=new CompoundTag();
        if(!entity.save(state))throw new IOException("Entity refused canonical save");
        ResourceLocation type=BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        if(type==null||!state.getString("id").equals(type.toString())||
           !state.hasUUID("UUID")||!state.getUUID("UUID").equals(entity.getUUID()))
            throw new IOException("Entity save omitted identity or type");
        var image=new CompoundTag();image.putInt("schema",SCHEMA);
        image.putUUID("uuid",entity.getUUID());
        image.putString("type",type.toString());
        image.putString("dimension",level.dimension().location().toString());
        image.putLong("capturedGameTime",level.getGameTime());
        image.put("entity",state);
        byte[] bytes=write(image);
        decode(bytes,entity.getUUID(),type.toString(),level.dimension().location().toString());
        return bytes;
    }
    /** Strict decode BEFORE staging; never mutates or spawns an actual world entity. */
    public static Continuation decode(byte[] bytes,UUID expectedUuid,String expectedType,String expectedDimension)throws IOException {
        Objects.requireNonNull(expectedUuid);
        var root=read(bytes);
        if(!root.getAllKeys().equals(Set.of("schema","uuid","type","dimension","capturedGameTime","entity"))||
           !root.contains("schema",Tag.TAG_INT)||root.getInt("schema")!=SCHEMA||
           !root.hasUUID("uuid")||!root.contains("entity",Tag.TAG_COMPOUND)||
           !root.contains("capturedGameTime",Tag.TAG_LONG)||
           !root.contains("dimension",Tag.TAG_STRING)||!root.contains("type",Tag.TAG_STRING))
            throw new IOException("Entity continuation envelope invalid");
        UUID id=root.getUUID("uuid");String type=root.getString("type"), dimension=root.getString("dimension");
        if(!id.equals(expectedUuid)||!type.equals(expectedType)||!dimension.equals(expectedDimension)||
           ResourceLocation.tryParse(type)==null||ResourceLocation.tryParse(dimension)==null)
            throw new IOException("Mismatched entity UUID/type/dimension in staged image");
        var entity=root.getCompound("entity");
        if(!entity.hasUUID("UUID")||!entity.getUUID("UUID").equals(id)||
           !entity.contains("id",Tag.TAG_STRING)||!entity.getString("id").equals(type)||
           !entity.contains("Pos",Tag.TAG_LIST)||entity.getList("Pos",Tag.TAG_DOUBLE).size()!=3)
            throw new IOException("Entity embedded identity/position invalid");
        for(Tag t:entity.getList("Pos",Tag.TAG_DOUBLE))if(!Double.isFinite(((DoubleTag)t).getAsDouble()))
            throw new IOException("Nonfinite entity position");
        if(!entity.contains("Motion",Tag.TAG_LIST)||entity.getList("Motion",Tag.TAG_DOUBLE).size()!=3)
            throw new IOException("Entity velocity missing");
        for(Tag t:entity.getList("Motion",Tag.TAG_DOUBLE))if(!Double.isFinite(((DoubleTag)t).getAsDouble()))
            throw new IOException("Nonfinite entity velocity");
        return new Continuation(id,dimension,type,root.getLong("capturedGameTime"),entity,
            EntityOwnershipLedger.hash(bytes));
    }
}
