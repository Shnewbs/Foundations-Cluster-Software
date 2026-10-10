package com.foundations.cluster.node.mixin;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
/** Uses vanilla's ID allocator for packet-only views; no extra Entity is constructed. */
@Mixin(Entity.class)
public interface EntityIdAllocatorAccessor {
    @Accessor("ENTITY_COUNTER") static AtomicInteger foundations$counter(){throw new AssertionError("Mixin not applied");}
}
