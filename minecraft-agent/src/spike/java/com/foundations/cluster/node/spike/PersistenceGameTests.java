package com.foundations.cluster.node.spike;

import java.nio.file.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.neoforged.neoforge.attachment.AttachmentHolder;
import net.neoforged.neoforge.gametest.*;

/** First Spike C oracle: an actual vanilla/NeoForge chunk serialization round trip.
 * Not a process-kill, entity-storage or cross-worker promotion acceptance test.
 */
@GameTestHolder("foundations_cluster_spike")
@PrefixGameTestTemplate(false)
public final class PersistenceGameTests {
    @GameTest(template="empty") public static void mutationAndMarkerShareChunkImage(GameTestHelper h) throws Exception {
        var relative=new BlockPos(2,2,2);var pos=h.absolutePos(relative);var level=h.getLevel();
        h.setBlock(relative,Blocks.DIAMOND_BLOCK);
        var chunk=level.getChunkAt(pos);
        var previous=chunk.getData(SurvivalSpike.APPLIED).copy();
        Path file=Files.createTempFile("foundations-chunk-oracle-", ".nbt");
        try {
            var applied=new CompoundTag();applied.putString("operation","fixture/1");applied.putLong("frame",1);
            chunk.setData(SurvivalSpike.APPLIED,applied);
            var serialized=ChunkSerializer.write(level,chunk);
            h.assertTrue(serialized.contains(AttachmentHolder.ATTACHMENTS_NBT_KEY,Tag.TAG_COMPOUND),"Chunk serializer includes operation attachment");
            NbtIo.writeCompressed(serialized,file);
            var restored=ChunkSerializer.read(level,level.getPoiManager(),new net.minecraft.world.level.chunk.storage.RegionStorageInfo("spike",level.dimension(),"chunk"),chunk.getPos(),NbtIo.readCompressed(file,NbtAccounter.create(32*1024*1024L)));
            h.assertTrue(restored.getBlockState(pos).is(Blocks.DIAMOND_BLOCK),"World mutation survives vanilla serialization");
            h.assertTrue(restored.getData(SurvivalSpike.APPLIED).equals(applied),"Same chunk image carries applied-operation marker");
        } finally {chunk.setData(SurvivalSpike.APPLIED,previous);Files.deleteIfExists(file);}
        h.succeed();
    }
}
