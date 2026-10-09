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
    public SurvivalSpike(IEventBus bus) { ATTACHMENTS.register(bus); }
}
