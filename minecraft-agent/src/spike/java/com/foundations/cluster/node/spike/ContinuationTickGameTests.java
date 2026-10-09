package com.foundations.cluster.node.spike;

import java.nio.file.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.material.*;
import net.minecraft.world.ticks.*;
import net.neoforged.neoforge.gametest.*;

/** Real vanilla scheduled-tick codecs under deliberate time skew. No local level
 * clock is rewritten. Not a running redstone/fluid continuation or mod-timer test. */
@GameTestHolder("foundations_cluster_spike")
@PrefixGameTestTemplate(false)
public final class ContinuationTickGameTests {
    private static ListTag roundTrip(Tag ticks) throws Exception {
        Path file = Files.createTempFile("foundations-scheduled-ticks-", ".nbt");
        try { var nbt = new CompoundTag(); nbt.put("ticks", ticks); NbtIo.writeCompressed(nbt, file);
            return NbtIo.readCompressed(file, NbtAccounter.create(1024 * 1024)).getList("ticks", Tag.TAG_COMPOUND);
        } finally { Files.deleteIfExists(file); }
    }
    private static Optional<Block> block(String id) { return BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(id)); }
    private static Optional<Fluid> fluid(String id) { return BuiltInRegistries.FLUID.getOptional(ResourceLocation.parse(id)); }
    @GameTest(template="empty") public static void actualBlockTickCodecPreservesDelayAcrossClockSkew(GameTestHelper h) throws Exception {
        var ticks = new LevelChunkTicks<Block>(); var pos = new BlockPos(1, 64, 1);
        ticks.schedule(new ScheduledTick<>(Blocks.REDSTONE_LAMP, pos, 1250L, TickPriority.HIGH, 1L));
        var saved = roundTrip(ticks.save(1200L, b -> BuiltInRegistries.BLOCK.getKey(b).toString()));
        var restored = LevelChunkTicks.load(saved, ContinuationTickGameTests::block, new ChunkPos(pos)); restored.unpack(850L);
        var first = restored.poll();
        h.assertTrue(first != null && first.triggerTick() == 900L && first.priority() == TickPriority.HIGH && first.pos().equals(pos), "50 remaining ticks and priority survive +200/-150 worker time skew");
        h.assertTrue(restored.poll() == null, "No duplicated pending block tick"); h.succeed();
    }
    @GameTest(template="empty") public static void actualFluidTickCodecPreservesDelayAcrossClockSkew(GameTestHelper h) throws Exception {
        var ticks = new LevelChunkTicks<Fluid>(); var pos = new BlockPos(2, 64, 2);
        ticks.schedule(new ScheduledTick<>(Fluids.WATER, pos, 1220L, TickPriority.NORMAL, 3L));
        var saved = roundTrip(ticks.save(1200L, f -> BuiltInRegistries.FLUID.getKey(f).toString()));
        var restored = LevelChunkTicks.load(saved, ContinuationTickGameTests::fluid, new ChunkPos(pos)); restored.unpack(850L);
        var first = restored.poll();
        h.assertTrue(first != null && first.triggerTick() == 870L && first.type() == Fluids.WATER, "Fluid codec preserves 20 remaining ticks"); h.succeed();
    }
    @GameTest(template="empty") public static void overdueTicksRemainDueAndWrongChunkExcluded(GameTestHelper h) throws Exception {
        var ticks = new LevelChunkTicks<Block>(); var pos = new BlockPos(1, 64, 1);
        ticks.schedule(new ScheduledTick<>(Blocks.REDSTONE_LAMP, pos, 1190L, TickPriority.NORMAL, 1L));
        var saved = roundTrip(ticks.save(1200L, b -> BuiltInRegistries.BLOCK.getKey(b).toString()));
        var restored = LevelChunkTicks.load(saved, ContinuationTickGameTests::block, new ChunkPos(pos)); restored.unpack(850L);
        h.assertTrue(restored.poll().triggerTick() <= 850L, "Overdue source ticks are still due after import");
        var wrong = LevelChunkTicks.load(saved, ContinuationTickGameTests::block, new ChunkPos(5, 5)); wrong.unpack(850L);
        h.assertTrue(wrong.poll() == null, "Canonical parser does not import a tick from another chunk"); h.succeed();
    }
}
