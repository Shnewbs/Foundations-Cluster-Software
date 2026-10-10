package com.foundations.cluster.node.mixin;
import net.minecraft.network.syncher.SynchedEntityData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
/** Read-only full snapshot, including defaults; never drains the native dirty set. */
@Mixin(SynchedEntityData.class)
public interface EntityDataSnapshotAccessor {
    @Accessor("itemsById") SynchedEntityData.DataItem<?>[] foundations$items();
}
