package com.foundations.cluster.node.interaction;

import com.foundations.cluster.protocol.PlayerInventoryJournal;
import com.foundations.cluster.protocol.PlayerInventoryJournal.*;
import java.io.IOException;
import java.util.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Real Minecraft inventory adapter, NOT a live remote-intent route. Only fixture
 * callers currently use it. A future coordinator must own all inventory mutation
 * interception, trusted authority receipts and frame-gated client acknowledgements.
 * This initial adapter handles a single selected/offhand slot delta, not arbitrary
 * mod/player effects, death, menus, pickups, stats, exhaustion or advancements.
 */
public final class AnchorInventorySession {
    public record SlotDelta(OperationId id, long beforeRevision, int slot, byte[] before, byte[] after) {
        public SlotDelta { Objects.requireNonNull(id); before = before.clone(); after = after.clone(); }
        @Override public byte[] before() { return before.clone(); }
        @Override public byte[] after() { return after.clone(); }
    }
    private final ServerPlayer player;
    private final PlayerInventoryJournal journal;
    private final long epoch;
    private boolean needsRestore;
    public AnchorInventorySession(ServerPlayer player, PlayerInventoryJournal journal, long epoch) throws IOException {
        this.player = Objects.requireNonNull(player); this.journal = Objects.requireNonNull(journal); this.epoch = epoch;
        check();
    }
    private View check() throws IOException {
        InventoryImageCodec.requireLocalInventory(player);
        if (needsRestore) throw new IllegalStateException("Inventory publication failed; explicit restore required");
        View view = journal.view();
        if (!view.player().equals(player.getUUID()) || view.anchorEpoch() != epoch) throw new IllegalStateException("Wrong player/stale anchor");
        return view;
    }
    private byte[] live() throws IOException { return InventoryImageCodec.encode(InventoryImageCodec.capture(player), player.serverLevel().registryAccess()); }
    /** Explicit recovery; never automatically overwrites a newer vanilla player save on login. */
    public void restore() throws IOException {
        InventoryImageCodec.requireLocalInventory(player);
        View view = journal.view();
        if (!view.player().equals(player.getUUID()) || view.anchorEpoch() != epoch) throw new IllegalStateException("Wrong player/stale anchor");
        var restored = InventoryImageCodec.decode(view.image(), player.serverLevel().registryAccess());
        needsRestore = true; restored.install(player);
        if (!Arrays.equals(live(), view.image())) throw new IOException("Inventory restore did not reproduce durable state");
        needsRestore = false;
    }
    public Reservation reserve(int slot) throws IOException {
        View view = check();
        if (slot != player.getInventory().selected && slot != 40) throw new IllegalArgumentException("Only selected/offhand actions are supported");
        return journal.reserve(epoch, view.revision(), slot, live());
    }
    /** The caller must retain this delta in the durable authority result for retries. */
    public SlotDelta prepareSlotDelta(OperationId id, ItemStack replacement) throws IOException {
        View view = check();
        if (view.pending() == null || !view.pending().id().equals(id)) throw new IllegalStateException("No matching reservation");
        int slot = view.pending().slot();
        var before = InventoryImageCodec.decode(view.image(), player.serverLevel().registryAccess());
        byte[] after = InventoryImageCodec.encode(before.withSlot(slot, replacement), player.serverLevel().registryAccess());
        return new SlotDelta(id, view.revision(), slot, view.image(), after);
    }
    public boolean apply(SlotDelta delta, WorldReceipt durableWorldReceipt) throws IOException {
        View view = check(); Objects.requireNonNull(delta); Objects.requireNonNull(durableWorldReceipt);
        var before = InventoryImageCodec.decode(delta.before, player.serverLevel().registryAccess());
        var after = InventoryImageCodec.decode(delta.after, player.serverLevel().registryAccess());
        if (!before.sameExceptSlot(after, delta.slot)) throw new IllegalArgumentException("Delta changed unreserved slots/selection");
        if (journal.applied(delta.id).isPresent()) {
            var duplicate = journal.commit(epoch, delta.id, durableWorldReceipt, delta.after);
            // A reopened session may still hold pre-crash inventory. Dedup alone is
            // not proof that the durable image reached memory. Never publish an
            // old delta over newer inventory; require explicit latest-image restore.
            if (!Arrays.equals(live(), view.image())) {
                needsRestore = true;
                throw new IllegalStateException("Durable inventory has not been restored; explicit restore required");
            }
            return duplicate.newCommit();
        }
        if (view.pending() == null || !view.pending().id().equals(delta.id) || view.pending().slot() != delta.slot ||
                view.revision() != delta.beforeRevision || !Arrays.equals(view.image(), delta.before) || !Arrays.equals(live(), delta.before))
            throw new IllegalStateException("Inventory changed while the operation was reserved");
        var result = journal.commit(epoch, delta.id, durableWorldReceipt, delta.after);
        if (result.newCommit()) {
            needsRestore = true;
            after.install(player);
            if (!Arrays.equals(live(), delta.after)) throw new IOException("Player inventory publication diverged from durable delta");
            needsRestore = false;
        }
        return result.newCommit();
    }
    public boolean inventoryMutationAllowed() throws IOException { return check().pending() == null; }
    public void recordLocalChange() throws IOException {
        View view = check(); journal.observe(epoch, view.revision(), live());
    }
}
