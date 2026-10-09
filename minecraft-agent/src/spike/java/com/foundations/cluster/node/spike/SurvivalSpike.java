package com.foundations.cluster.node.spike;

import java.util.function.Supplier;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.*;

@Mod("foundations_cluster_spike")
public final class SurvivalSpike {
    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
        DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, "foundations_cluster_spike");
    static final Supplier<AttachmentType<CompoundTag>> APPLIED = ATTACHMENTS.register("applied_operations",
        () -> AttachmentType.builder(() -> new CompoundTag()).serialize(CompoundTag.CODEC).build());
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks("foundations_cluster_spike");
    static final Supplier<net.minecraft.world.level.block.Block> REFUSES_REMOVAL = BLOCKS.register("refuses_removal",
        () -> new net.minecraft.world.level.block.Block(net.minecraft.world.level.block.state.BlockBehaviour.Properties.of().strength(1)) {
            @Override public boolean onDestroyedByPlayer(net.minecraft.world.level.block.state.BlockState state,
                    net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos,
                    net.minecraft.world.entity.player.Player player, boolean harvest,
                    net.minecraft.world.level.material.FluidState fluid) { return false; }
        });
    public SurvivalSpike(IEventBus bus) { ATTACHMENTS.register(bus); BLOCKS.register(bus); }
}
