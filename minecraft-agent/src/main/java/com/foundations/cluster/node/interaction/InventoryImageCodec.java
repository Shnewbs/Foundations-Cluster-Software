package com.foundations.cluster.node.interaction;

import com.foundations.cluster.protocol.PlayerInventoryJournal;
import java.io.*;
import java.util.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** Strict, canonical, bounded inventory NBT. Unsupported/nonpersistent components
 * are rejected, not silently stripped. Container cursors/mod equipment are not
 * part of this initial adapter, so open menus/nonempty cursors are refused.
 */
public final class InventoryImageCodec {
    private InventoryImageCodec() {}
    public static final int SLOT_COUNT = 41;
    public static final class Image {
        private final int selected;
        private final ItemStack[] slots;
        private Image(int selected, ItemStack[] slots) {
            this.selected = selected; this.slots = Arrays.stream(slots).map(ItemStack::copy).toArray(ItemStack[]::new);
        }
        public int selected() { return selected; }
        public ItemStack stack(int slot) { return slots[slot].copy(); }
        public Image withSlot(int slot, ItemStack replacement) {
            if (slot < 0 || slot >= SLOT_COUNT) throw new IllegalArgumentException("Invalid inventory slot");
            ItemStack[] changed = slots.clone(); changed[slot] = Objects.requireNonNull(replacement).copy();
            return new Image(selected, changed);
        }
        public boolean sameExceptSlot(Image other, int slot) {
            if (selected != other.selected || slot < 0 || slot >= SLOT_COUNT) return false;
            for (int i = 0; i < SLOT_COUNT; i++) if (i != slot && !ItemStack.matches(slots[i], other.slots[i])) return false;
            return true;
        }
        /** Decode/validate the ENTIRE image before calling this method. No client ack is sent. */
        public void install(ServerPlayer player) {
            requireLocalInventory(player);
            for (int i = 0; i < SLOT_COUNT; i++) player.getInventory().setItem(i, slots[i].copy());
            player.getInventory().selected = selected;
            player.getInventory().setChanged();
        }
    }
    public static void requireLocalInventory(ServerPlayer player) {
        Objects.requireNonNull(player);
        if (!player.serverLevel().getServer().isSameThread()) throw new IllegalStateException("Inventory adapter requires the server thread");
        if (player.getInventory().getContainerSize() != SLOT_COUNT || player.containerMenu != player.inventoryMenu ||
                !player.containerMenu.getCarried().isEmpty()) throw new IllegalStateException("Open menus/cursors or unsupported inventory layout");
    }
    public static Image capture(ServerPlayer player) {
        requireLocalInventory(player);
        int selected = player.getInventory().selected;
        if (selected < 0 || selected > 8) throw new IllegalStateException("Invalid selected hotbar slot");
        ItemStack[] slots = new ItemStack[SLOT_COUNT];
        for (int i = 0; i < SLOT_COUNT; i++) slots[i] = player.getInventory().getItem(i).copy();
        return new Image(selected, slots);
    }
    public static byte[] encode(Image image, HolderLookup.Provider registries) throws IOException {
        CompoundTag root = new CompoundTag(); root.putInt("schema", 1); root.putInt("selected", image.selected);
        ListTag entries = new ListTag();
        for (int i = 0; i < SLOT_COUNT; i++) {
            CompoundTag entry = new CompoundTag(); entry.putInt("slot", i);
            ItemStack stack = image.slots[i];
            Tag encoded = stack.isEmpty() ? new CompoundTag() : stack.save(registries);
            if (!(encoded instanceof CompoundTag compound)) throw new IOException("Item codec did not emit a compound");
            ItemStack decoded = parseStack(compound, registries);
            if (!ItemStack.matches(stack, decoded)) throw new IOException("Nonpersistent/unsupported item components at slot " + i);
            entry.put("stack", encoded); entries.add(entry);
        }
        root.put("slots", entries);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream() {
            private void bound(int n) { if (count > PlayerInventoryJournal.MAX_IMAGE_BYTES - n) throw new IllegalArgumentException("Inventory image exceeds limit"); }
            @Override public synchronized void write(int b) { bound(1); super.write(b); }
            @Override public synchronized void write(byte[] b, int off, int len) { bound(len); super.write(b, off, len); }
        };
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(Tag.TAG_COMPOUND); out.writeUTF(""); writeCanonical(root, out);
        }
        return bytes.toByteArray();
    }
    private static void writeCanonical(Tag tag, DataOutput out) throws IOException {
        if (tag instanceof CompoundTag compound) {
            for (String key : new TreeSet<>(compound.getAllKeys())) {
                Tag value = Objects.requireNonNull(compound.get(key)); out.writeByte(value.getId()); out.writeUTF(key); writeCanonical(value, out);
            }
            out.writeByte(Tag.TAG_END);
        } else if (tag instanceof ListTag list) {
            out.writeByte(list.getElementType()); out.writeInt(list.size());
            for (Tag value : list) writeCanonical(value, out);
        } else tag.write(out);
    }
    public static Image decode(byte[] bytes, HolderLookup.Provider registries) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > PlayerInventoryJournal.MAX_IMAGE_BYTES) throw new IOException("Invalid inventory image length");
        CompoundTag root;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            root = NbtIo.read(in, NbtAccounter.create(16L * 1024 * 1024));
            if (root == null || in.available() != 0) throw new IOException("Trailing or missing inventory NBT");
        }
        if (!root.getAllKeys().equals(Set.of("schema", "selected", "slots")) || !root.contains("schema", Tag.TAG_INT) ||
                root.getInt("schema") != 1 || !root.contains("selected", Tag.TAG_INT) || !root.contains("slots", Tag.TAG_LIST))
            throw new IOException("Invalid inventory image schema");
        int selected = root.getInt("selected");
        if (selected < 0 || selected > 8) throw new IOException("Invalid inventory selection");
        ListTag entries = root.getList("slots", Tag.TAG_COMPOUND);
        if (entries.size() != SLOT_COUNT) throw new IOException("Incomplete inventory image");
        ItemStack[] slots = new ItemStack[SLOT_COUNT];
        for (Tag tag : entries) {
            CompoundTag entry = (CompoundTag) tag;
            if (!entry.getAllKeys().equals(Set.of("slot", "stack")) || !entry.contains("slot", Tag.TAG_INT) || !entry.contains("stack", Tag.TAG_COMPOUND))
                throw new IOException("Invalid inventory entry");
            int slot = entry.getInt("slot");
            if (slot < 0 || slot >= SLOT_COUNT || slots[slot] != null) throw new IOException("Duplicate/invalid inventory slot");
            slots[slot] = parseStack(entry.getCompound("stack"), registries);
        }
        return new Image(selected, slots);
    }
    private static ItemStack parseStack(CompoundTag tag, HolderLookup.Provider registries) throws IOException {
        if (tag.isEmpty()) return ItemStack.EMPTY;
        try { return ItemStack.CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE), tag).getOrThrow(); }
        catch (RuntimeException ex) { throw new IOException("Unrecognized or invalid item components", ex); }
    }
}
