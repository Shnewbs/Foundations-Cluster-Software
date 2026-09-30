package com.foundations.cluster.node.mixin;
import com.foundations.cluster.node.FoundationsClusterNode;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(LevelChunk.class)
public abstract class ChunkMutationMixin {
 @Shadow public abstract Level getLevel();
 @Inject(method="setBlockState",at=@At("HEAD"),cancellable=true)
 private void foundations$seal(BlockPos pos,BlockState state,boolean moving,CallbackInfoReturnable<BlockState> ci){if(FoundationsClusterNode.regionSealed(getLevel(),pos))ci.setReturnValue(null);}
 @Inject(method="setBlockState",at=@At("RETURN"))
 private void foundations$capture(BlockPos pos,BlockState state,boolean moving,CallbackInfoReturnable<BlockState> ci){FoundationsClusterNode.captureMutation(getLevel(),pos,ci.getReturnValue(),state);}
 // Snapshot replay must not run placement/removal gameplay midway through a restore.
 // Keep LevelChunk's heightmap, lighting and block-entity bookkeeping intact.
 @Redirect(method="setBlockState",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/block/state/BlockState;onPlace(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)V"))
 private void foundations$restorePlace(BlockState state,Level level,BlockPos pos,BlockState old,boolean moving){
  if(!FoundationsClusterNode.replayingSnapshot(level))state.onPlace(level,pos,old,moving);
 }
 @Redirect(method="setBlockState",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/block/state/BlockState;onRemove(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)V"))
 private void foundations$restoreRemove(BlockState old,Level level,BlockPos pos,BlockState state,boolean moving){
  if(!FoundationsClusterNode.replayingSnapshot(level))old.onRemove(level,pos,state,moving);
  else if(old.hasBlockEntity()&&!old.is(state.getBlock()))level.removeBlockEntity(pos);
 }
}
