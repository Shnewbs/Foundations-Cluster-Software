package com.foundations.cluster.node.interaction;

import java.io.IOException;
import java.util.Objects;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.neoforged.neoforge.attachment.AttachmentHolder;

/** Prototype pre-persistence fence. NeoForge can omit an attachment when its
 * serializer fails. Such an image must never be treated as a valid operation
 * checkpoint. Does not replace vanilla saves or prove entity-storage coherence.
 */
public final class ChunkSaveFence {
    private ChunkSaveFence() {}
    public static CompoundTag serialize(ServerLevel level, LevelChunk chunk, ResourceLocation markerId,
                                         CompoundTag expectedMarker) throws IOException {
        Objects.requireNonNull(level); Objects.requireNonNull(chunk);
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Chunk save fence requires server thread");
        if (chunk.getLevel() != level) throw new IllegalArgumentException("Chunk belongs to another level");
        CompoundTag expected = Objects.requireNonNull(expectedMarker).copy();
        if (expected.isEmpty()) throw new IllegalArgumentException("Empty operation marker is not checkpoint evidence");
        CompoundTag serialized = ChunkSerializer.write(level, chunk);
        validate(serialized, chunk.getPos(), markerId, expected);
        return serialized;
    }
    public static void validate(CompoundTag image, ChunkPos position, ResourceLocation markerId,
                                 CompoundTag expectedMarker) throws IOException {
        Objects.requireNonNull(image); Objects.requireNonNull(position); Objects.requireNonNull(markerId);
        if (expectedMarker == null || expectedMarker.isEmpty()) throw new IllegalArgumentException("Expected operation marker is required");
        if (!image.contains("xPos", Tag.TAG_INT) || !image.contains("zPos", Tag.TAG_INT) ||
                image.getInt("xPos") != position.x || image.getInt("zPos") != position.z)
            throw new IOException("Chunk save image coordinate mismatch");
        if (!image.contains(AttachmentHolder.ATTACHMENTS_NBT_KEY, Tag.TAG_COMPOUND))
            throw new IOException("Chunk save image is missing operation attachments");
        CompoundTag attachments = image.getCompound(AttachmentHolder.ATTACHMENTS_NBT_KEY);
        if (!attachments.contains(markerId.toString(), Tag.TAG_COMPOUND) ||
                !expectedMarker.equals(attachments.getCompound(markerId.toString())))
            throw new IOException("Chunk save image lost or changed applied-operation marker: " + markerId);
    }
}
