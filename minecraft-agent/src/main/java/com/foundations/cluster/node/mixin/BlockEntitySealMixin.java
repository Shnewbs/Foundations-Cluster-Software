package com.foundations.cluster.node.mixin;
import com.foundations.cluster.node.FoundationsClusterNode;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(targets="net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity")
public abstract class BlockEntitySealMixin {
 @Shadow @Final private BlockEntity blockEntity;
 @Inject(method="tick",at=@At("HEAD"),cancellable=true)
 private void foundations$seal(CallbackInfo ci){if(blockEntity.getLevel()!=null&&FoundationsClusterNode.regionSealed(blockEntity.getLevel(),blockEntity.getBlockPos()))ci.cancel();}
}
