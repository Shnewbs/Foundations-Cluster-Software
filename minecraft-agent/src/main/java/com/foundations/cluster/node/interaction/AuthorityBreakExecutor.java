package com.foundations.cluster.node.interaction;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Spike A executor. Deliberately NOT connected to BLOCK_REQUEST: the durable
 * chunk/entity and anchor commit adapters are still required before live use.
 * The caller owns mining progression, actor lifetime, effect capture and recovery.
 */
public final class AuthorityBreakExecutor {
    private AuthorityBreakExecutor() {}

    public enum Outcome { EXECUTED, FENCED, WRONG_MODE, UNLOADED, OUT_OF_REACH,
        PROTECTED, STALE_BLOCK, STALE_TOOL, UNBREAKABLE, CANCELLED }

    /** A reservation snapshot, including components and count, not just item type. */
    public static final class PreparedBreak {
        private final BlockPos position;
        private final BlockState block;
        private final ItemStack tool;
        private final int slot;
        public PreparedBreak(BlockPos position, BlockState block, ItemStack tool, int slot) {
            this.position = Objects.requireNonNull(position).immutable();
            this.block = Objects.requireNonNull(block);
            this.tool = Objects.requireNonNull(tool).copy();
            if (slot < 0 || slot > 8) throw new IllegalArgumentException("Not a hotbar slot");
            this.slot = slot;
        }
    }

    public static Outcome execute(ServerPlayer actor, PreparedBreak request, BooleanSupplier ownsEpoch) {
        Objects.requireNonNull(actor); Objects.requireNonNull(request); Objects.requireNonNull(ownsEpoch);
        var level = actor.serverLevel();
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Break must run on the server thread");
        if (!ownsEpoch.getAsBoolean()) return Outcome.FENCED;
        if (actor.gameMode.getGameModeForPlayer() != GameType.SURVIVAL) return Outcome.WRONG_MODE;
        var pos = request.position;
        // Never load a chunk as a side effect of an interaction request.
        if (!level.hasChunkAt(pos)) return Outcome.UNLOADED;
        if (!actor.canInteractWithBlock(pos, 0)) return Outcome.OUT_OF_REACH;
        if (!level.getWorldBorder().isWithinBounds(pos) || !level.mayInteract(actor, pos)) return Outcome.PROTECTED;
        var state = level.getBlockState(pos);
        if (!state.equals(request.block) || state.isAir()) return Outcome.STALE_BLOCK;
        if (actor.getInventory().selected != request.slot || !ItemStack.matches(actor.getMainHandItem(), request.tool))
            return Outcome.STALE_TOOL;
        if (state.getDestroySpeed(level, pos) < 0) return Outcome.UNBREAKABLE;
        // NeoForge's patched game-mode path runs BreakEvent, harvest checks,
        // ItemStack.mineBlock, playerDestroy, loot modifiers and BlockDropsEvent.
        // Calling setBlock(AIR) here would silently skip those semantics.
        if (!ownsEpoch.getAsBoolean()) return Outcome.FENCED;
        // A mod may refuse block removal AFTER mineBlock has worn the tool.
        // true means the game path executed, not that the world became air.
        // The caller must journal observed effects even when the block remains.
        return actor.gameMode.destroyBlock(pos) ? Outcome.EXECUTED : Outcome.CANCELLED;
    }
}
