package com.foundations.cluster.node.spike;

import java.nio.file.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.neoforged.neoforge.attachment.AttachmentHolder;
import net.neoforged.neoforge.gametest.*;

/** Actual vanilla/NeoForge chunk serialization; not entity-storage or promotion certification. */
@GameTestHolder("foundations_cluster_spike")
@PrefixGameTestTemplate(false)
public final class PersistenceGameTests {
    private static final net.minecraft.resources.ResourceLocation MARKER = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("foundations_cluster_spike", "applied_operations");
    private static CompoundTag marker() { var tag=new CompoundTag();tag.putString("operation","fixture/1");tag.putLong("frame",1);return tag; }
    private interface Attempt { void run() throws Exception; }
    private static void rejects(GameTestHelper h, Attempt action) throws Exception {
        try { action.run(); } catch (java.io.IOException expected) { return; }
        h.fail("Invalid save image was accepted");
    }
    @GameTest(template="empty") public static void missingOrAlteredMarkerIsRejected(GameTestHelper h) throws Exception {
        var chunk=h.getLevel().getChunkAt(h.absolutePos(new BlockPos(2,2,2)));
        var before=chunk.getData(SurvivalSpike.APPLIED).copy();
        try {
            var expected=marker();chunk.setData(SurvivalSpike.APPLIED,expected.copy());
            var image=com.foundations.cluster.node.interaction.ChunkSaveFence.serialize(h.getLevel(),chunk,MARKER,expected);
            var missing=image.copy();missing.remove(AttachmentHolder.ATTACHMENTS_NBT_KEY);
            rejects(h,()->com.foundations.cluster.node.interaction.ChunkSaveFence.validate(missing,chunk.getPos(),MARKER,expected));
            var changed=image.copy();changed.getCompound(AttachmentHolder.ATTACHMENTS_NBT_KEY).getCompound(MARKER.toString()).putLong("frame",2);
            rejects(h,()->com.foundations.cluster.node.interaction.ChunkSaveFence.validate(changed,chunk.getPos(),MARKER,expected));
            var wrong=image.copy();wrong.putInt("xPos",chunk.getPos().x+1);
            rejects(h,()->com.foundations.cluster.node.interaction.ChunkSaveFence.validate(wrong,chunk.getPos(),MARKER,expected));
        } finally {chunk.setData(SurvivalSpike.APPLIED,before);}
        h.succeed();
    }
    @GameTest(template="empty") public static void serializerOmissionFailsClosed(GameTestHelper h) throws Exception {
        var chunk=h.getLevel().getChunkAt(h.absolutePos(new BlockPos(2,2,2)));
        var broken=net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("foundations_cluster_spike","broken_applied_operations");
        try {
            chunk.setData(SurvivalSpike.BROKEN_APPLIED,marker());
            var unsafe=ChunkSerializer.write(h.getLevel(),chunk);
            h.assertTrue(!unsafe.getCompound(AttachmentHolder.ATTACHMENTS_NBT_KEY).contains(broken.toString()),"Fixture must reproduce omitted operation data");
            rejects(h,()->com.foundations.cluster.node.interaction.ChunkSaveFence.serialize(h.getLevel(),chunk,broken,marker()));
        } finally {chunk.removeData(SurvivalSpike.BROKEN_APPLIED);}
        h.succeed();
    }
    @GameTest(template="empty") public static void mutationAndMarkerShareChunkImage(GameTestHelper h) throws Exception {
        var relative=new BlockPos(2,2,2);var pos=h.absolutePos(relative);var level=h.getLevel();
        h.setBlock(relative,Blocks.DIAMOND_BLOCK);
        var chunk=level.getChunkAt(pos);
        var previous=chunk.getData(SurvivalSpike.APPLIED).copy();
        Path file=Files.createTempFile("foundations-chunk-oracle-", ".nbt");
        try {
            var applied=new CompoundTag();applied.putString("operation","fixture/1");applied.putLong("frame",1);
            chunk.setData(SurvivalSpike.APPLIED,applied);
            var serialized=com.foundations.cluster.node.interaction.ChunkSaveFence.serialize(level,chunk,MARKER,applied);
            h.assertTrue(serialized.contains(AttachmentHolder.ATTACHMENTS_NBT_KEY,Tag.TAG_COMPOUND),"Chunk serializer includes operation attachment");
            NbtIo.writeCompressed(serialized,file);
            var restored=ChunkSerializer.read(level,level.getPoiManager(),new net.minecraft.world.level.chunk.storage.RegionStorageInfo("spike",level.dimension(),"chunk"),chunk.getPos(),NbtIo.readCompressed(file,NbtAccounter.create(32*1024*1024L)));
            h.assertTrue(restored.getBlockState(pos).is(Blocks.DIAMOND_BLOCK),"World mutation survives vanilla serialization");
            h.assertTrue(restored.getData(SurvivalSpike.APPLIED).equals(applied),"Same chunk image carries applied-operation marker");
        } finally {chunk.setData(SurvivalSpike.APPLIED,previous);Files.deleteIfExists(file);}
        h.succeed();
    }
}
