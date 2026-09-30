package com.foundations.cluster.node.mixin;

import com.foundations.cluster.node.FoundationsClusterNode;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prevent unmanaged random growth on physical replicas; the authority ticks normally. */
@Mixin(ServerLevel.class)
public abstract class GrowthTickMixin {
    @Inject(method="tickChunk",at=@At("HEAD"),cancellable=true)
    private void foundations$authorityGrowth(LevelChunk chunk,int randomTickSpeed,CallbackInfo ci) {
        if(!FoundationsClusterNode.allowNaturalChunkTick((ServerLevel)(Object)this,chunk))ci.cancel();
    }
}
