package com.foundations.cluster.playtest;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** Read-only ordinary-inventory comparison. Never loads, restores or writes a
 * player .dat. Digests include persistent stack components, not names/chat in logs.
 * Accessories, ender chest, open cursors, player effects and globals are out of scope. */
public final class PlaytestInventory {
    public static final int SLOTS = 41;
    private static final int MAX_IMAGE_BYTES = 1024 * 1024;
    private PlaytestInventory() {}
    public record Slot(String item, int count, int damage, String digest) {
        public String summary() { return item + ";count=" + count + ";damage=" + damage + ";sha256=" + digest; }
    }
    public record Image(int selected, List<Slot> slots, String digest) {
        public Image { slots = List.copyOf(slots); }
        public List<Integer> differences(Image other) {
            var changed = new ArrayList<Integer>();
            for (int i = 0; i < SLOTS; i++) if (!slots.get(i).equals(other.slots.get(i))) changed.add(i);
            return List.copyOf(changed);
        }
    }
    public static Image capture(ServerPlayer player) throws IOException {
        requireThread(player);
        if (player.getInventory().getContainerSize() != SLOTS || player.containerMenu != player.inventoryMenu ||
                !player.containerMenu.getCarried().isEmpty()) throw new IOException("UNSUPPORTED_MENU_CURSOR_OR_LAYOUT");
        var items = new ArrayList<ItemStack>();
        for (int i = 0; i < SLOTS; i++) items.add(player.getInventory().getItem(i).copy());
        return image(player.getInventory().selected, items, player.serverLevel().registryAccess());
    }
    public static void requireThread(ServerPlayer player) {
        if (!player.serverLevel().getServer().isSameThread()) throw new IllegalStateException("Server thread required");
    }
    /** Validate identity and all slot entries before comparing. Unknown slots are
     * refused rather than silently omitted as vanilla's tolerant loader may do. */
    public static Image fromPlayerSave(CompoundTag saved, UUID expectedPlayer, HolderLookup.Provider registries) throws IOException {
        if (!saved.hasUUID("UUID") || !saved.getUUID("UUID").equals(expectedPlayer)) throw new IOException("PLAYER_SAVE_IDENTITY_MISMATCH");
        if (!saved.contains("Inventory", Tag.TAG_LIST) || !saved.contains("SelectedItemSlot", Tag.TAG_INT))
            throw new IOException("PLAYER_SAVE_INVENTORY_MISSING");
        var inventory = (ListTag) saved.get("Inventory");
        if (inventory.size() > SLOTS || (!inventory.isEmpty() && inventory.getElementType() != Tag.TAG_COMPOUND))
            throw new IOException("PLAYER_SAVE_SLOT_LIST_INVALID");
        var items = new ArrayList<>(Collections.nCopies(SLOTS, ItemStack.EMPTY));
        var occupied = new BitSet(SLOTS);
        for (Tag value : inventory) {
            var entry = (CompoundTag) value;
            if (!entry.contains("Slot", Tag.TAG_BYTE)) throw new IOException("PLAYER_SAVE_SLOT_TYPE_INVALID");
            int encoded = entry.getByte("Slot") & 255;
            int slot = encoded <= 35 ? encoded : encoded >= 100 && encoded <= 103 ? encoded - 64 : encoded == 150 ? 40 : -1;
            if (slot < 0 || occupied.get(slot)) throw new IOException("PLAYER_SAVE_DUPLICATE_OR_UNSUPPORTED_SLOT");
            occupied.set(slot);
            var item = entry.copy(); item.remove("Slot");
            try { items.set(slot, ItemStack.CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE), item).getOrThrow()); }
            catch (RuntimeException ex) { throw new IOException("PLAYER_SAVE_ITEM_INVALID", ex); }
        }
        return image(saved.getInt("SelectedItemSlot"), items, registries);
    }
    /** No DataFixer or restore call: old/unsupported files are diagnostics, not migrations. */
    public static CompoundTag readSave(Path path) throws IOException {
        for (Path p = path.toAbsolutePath().normalize(); p != null; p = p.getParent())
            if (Files.isSymbolicLink(p)) throw new IOException("PLAYER_SAVE_SYMLINK_REFUSED");
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new NoSuchFileException("PLAYER_SAVE_NOT_FOUND");
        if (Files.size(path) > 4L * 1024 * 1024) throw new IOException("PLAYER_SAVE_COMPRESSED_LIMIT");
        try (var in = Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            // Snapshot the bounded compressed file before parsing; no file channel is
            // retained across a concurrent vanilla rename.
            byte[] compressed = in.readNBytes(4 * 1024 * 1024 + 1);
            if (compressed.length > 4 * 1024 * 1024) throw new IOException("PLAYER_SAVE_COMPRESSED_LIMIT");
            return NbtIo.readCompressed(new ByteArrayInputStream(compressed), NbtAccounter.create(16L * 1024 * 1024));
        } catch (RuntimeException ex) { throw new IOException("PLAYER_SAVE_NBT_INVALID", ex); }
    }
    public static Image image(int selected, List<ItemStack> items, HolderLookup.Provider registries) throws IOException {
        if (selected < 0 || selected > 8 || items.size() != SLOTS) throw new IOException("INVALID_INVENTORY_LAYOUT");
        var slots = new ArrayList<Slot>();
        var total = new ByteArrayOutputStream();
        try (var all = new DataOutputStream(total)) {
            all.writeInt(1); all.writeInt(selected);
            for (ItemStack original : items) {
                ItemStack stack = original.copy();
                CompoundTag tag;
                try {
                    tag = stack.isEmpty() ? new CompoundTag() : (CompoundTag) stack.save(registries);
                    ItemStack restored = tag.isEmpty() ? ItemStack.EMPTY : ItemStack.CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE), tag).getOrThrow();
                    if (!ItemStack.matches(stack, restored)) throw new IOException("NONPERSISTENT_ITEM_COMPONENTS");
                } catch (RuntimeException ex) { throw new IOException("UNSUPPORTED_ITEM_SERIALIZATION", ex); }
                var bytes = new BoundedBytes();
                try (var out = new DataOutputStream(bytes)) { canonical(tag, out, 0); }
                byte[] encoded = bytes.toByteArray();
                if (total.size() + encoded.length + 4 > MAX_IMAGE_BYTES) throw new IOException("INVENTORY_IMAGE_LIMIT");
                all.writeInt(encoded.length); all.write(encoded);
                slots.add(new Slot(stack.isEmpty() ? "minecraft:air" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                    stack.getCount(), stack.getDamageValue(), digest(encoded)));
            }
        }
        return new Image(selected, slots, digest(total.toByteArray()));
    }
    private static final class BoundedBytes extends ByteArrayOutputStream {
        private void bound(int n) { if (n < 0 || count > MAX_IMAGE_BYTES - n) throw new IllegalArgumentException("ITEM_IMAGE_LIMIT"); }
        @Override public synchronized void write(int b) { bound(1); super.write(b); }
        @Override public synchronized void write(byte[] b, int off, int len) { bound(len); super.write(b, off, len); }
    }
    private static void canonical(Tag value, DataOutput out, int depth) throws IOException {
        if (depth > 64) throw new IOException("ITEM_NBT_DEPTH_LIMIT");
        out.writeByte(value.getId());
        if (value instanceof CompoundTag compound) {
            for (String key : new TreeSet<>(compound.getAllKeys())) {
                out.writeUTF(key); canonical(Objects.requireNonNull(compound.get(key)), out, depth + 1);
            }
            out.writeUTF(""); out.writeByte(Tag.TAG_END);
        } else if (value instanceof ListTag list) {
            out.writeByte(list.getElementType()); out.writeInt(list.size());
            for (Tag child : list) canonical(child, out, depth + 1);
        } else value.write(out);
    }
    public static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException ex) { throw new AssertionError(ex); }
    }
}
