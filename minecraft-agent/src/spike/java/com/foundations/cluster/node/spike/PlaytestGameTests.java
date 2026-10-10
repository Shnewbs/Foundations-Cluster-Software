package com.foundations.cluster.node.spike;

import com.foundations.cluster.playtest.*;
import com.mojang.authlib.GameProfile;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.*;

/** Tests actual game inventory serialization and Node integration, not joined-client
 * gameplay or cross-worker .dat reconciliation. No real user inventory is used. */
@GameTestHolder("foundations_cluster_spike")
@PrefixGameTestTemplate(false)
public final class PlaytestGameTests {
    private interface Attempt { void run() throws Exception; }
    private static void rejects(GameTestHelper h, Attempt action) throws Exception {
        try { action.run(); } catch (IOException | IllegalArgumentException expected) { return; }
        h.fail("Invalid read-only inventory evidence was accepted");
    }
    private static ServerPlayer actor(GameTestHelper h) {
        var p = new FakePlayer(h.getLevel(), new GameProfile(UUID.randomUUID(), "playtest-fixture"));
        p.getInventory().selected = 2;
        return p;
    }
    private static CompoundTag save(ServerPlayer p) {
        // Actual vanilla/NeoForge player serialization, not a handwritten item schema.
        return p.saveWithoutId(new CompoundTag());
    }
    @GameTest(template="empty") public static void playtestDatRoundTripAllOrdinarySlots(GameTestHelper h) throws Exception {
        var p = actor(h);
        try {
            for (int i = 0; i < 41; i++) p.getInventory().setItem(i, new ItemStack(Items.DIRT, i + 1));
            p.getInventory().setItem(36, new ItemStack(Items.IRON_BOOTS));
            p.getInventory().setItem(39, new ItemStack(Items.IRON_HELMET));
            var named = new ItemStack(Items.IRON_PICKAXE); named.setDamageValue(23); named.set(DataComponents.CUSTOM_NAME, Component.literal("Private name must not be logged"));
            p.setItemInHand(InteractionHand.MAIN_HAND, named);
            var before = PlaytestInventory.capture(p);
            var saved = PlaytestInventory.fromPlayerSave(save(p), p.getUUID(), h.getLevel().registryAccess());
            h.assertTrue(before.digest().equals(saved.digest()) && before.differences(saved).isEmpty(), "All 41 ordinary slots and selection survive actual player serialization");
            h.assertTrue(before.slots().get(2).damage() == 23 && before.slots().get(40).count() == 41, "Main/offhand and armor mappings are exact");
            h.assertTrue(!before.slots().get(2).summary().contains("Private"), "Report summaries omit raw component text");
        } finally { p.getAdvancements().stopListening(); }
        h.succeed();
    }
    @GameTest(template="empty") public static void playtestSavedMismatchNeverRestores(GameTestHelper h) throws Exception {
        var p = actor(h);
        try {
            p.getInventory().setItem(9, new ItemStack(Items.DIAMOND, 17));
            var image = save(p); p.getInventory().setItem(9, new ItemStack(Items.DIAMOND, 16));
            var live = PlaytestInventory.capture(p); var old = PlaytestInventory.fromPlayerSave(image, p.getUUID(), h.getLevel().registryAccess());
            h.assertTrue(!live.digest().equals(old.digest()) && live.differences(old).equals(List.of(9)), "Changed saved slot is detected");
            h.assertTrue(p.getInventory().getItem(9).getCount() == 16, "Read-only comparison never reinstalls the old saved inventory");
            p.getInventory().selected = 3;
            h.assertTrue(!PlaytestInventory.capture(p).digest().equals(live.digest()), "Selection participates in digest");
        } finally { p.getAdvancements().stopListening(); }
        h.succeed();
    }
    @GameTest(template="empty") public static void playtestRejectsDuplicateUnknownAndMalformedSaveSlots(GameTestHelper h) throws Exception {
        var p = actor(h);
        try {
            p.getInventory().setItem(0, new ItemStack(Items.STONE)); var saved = save(p);
            var duplicate = saved.copy(); var list = duplicate.getList("Inventory", Tag.TAG_COMPOUND); list.add(list.getCompound(0).copy());
            rejects(h, () -> PlaytestInventory.fromPlayerSave(duplicate, p.getUUID(), h.getLevel().registryAccess()));
            var unknown = saved.copy(); unknown.getList("Inventory", Tag.TAG_COMPOUND).getCompound(0).putByte("Slot", (byte) 90);
            rejects(h, () -> PlaytestInventory.fromPlayerSave(unknown, p.getUUID(), h.getLevel().registryAccess()));
            var wrongType = saved.copy(); wrongType.getList("Inventory", Tag.TAG_COMPOUND).getCompound(0).putInt("Slot", 0);
            rejects(h, () -> PlaytestInventory.fromPlayerSave(wrongType, p.getUUID(), h.getLevel().registryAccess()));
            var invalidItem = saved.copy(); invalidItem.getList("Inventory", Tag.TAG_COMPOUND).getCompound(0).putString("id", "absent:item");
            rejects(h, () -> PlaytestInventory.fromPlayerSave(invalidItem, p.getUUID(), h.getLevel().registryAccess()));
            h.assertTrue(p.getInventory().getItem(0).is(Items.STONE), "Rejected save never alters live item");
        } finally { p.getAdvancements().stopListening(); }
        h.succeed();
    }
    @GameTest(template="empty") public static void playtestRejectsWrongIdentityAndMissingFields(GameTestHelper h) throws Exception {
        var p = actor(h);
        try {
            var saved = save(p);
            rejects(h, () -> PlaytestInventory.fromPlayerSave(saved, UUID.randomUUID(), h.getLevel().registryAccess()));
            var missing = saved.copy(); missing.remove("Inventory");
            rejects(h, () -> PlaytestInventory.fromPlayerSave(missing, p.getUUID(), h.getLevel().registryAccess()));
            var wrongSelection = saved.copy(); wrongSelection.putInt("SelectedItemSlot", 9);
            rejects(h, () -> PlaytestInventory.fromPlayerSave(wrongSelection, p.getUUID(), h.getLevel().registryAccess()));
            h.assertTrue(PlaytestInventory.fromPlayerSave(saved, p.getUUID(), h.getLevel().registryAccess()).slots().size() == 41, "Valid empty inventory is not a missing inventory");
        } finally { p.getAdvancements().stopListening(); }
        h.succeed();
    }
    @GameTest(template="empty") public static void playtestComponentOrderStableAndTransientRefused(GameTestHelper h) throws Exception {
        var p = actor(h);
        try {
            var a = new CompoundTag(); a.putInt("z", 1); a.putString("a", "hidden-data");
            var b = new CompoundTag(); b.putString("a", "hidden-data"); b.putInt("z", 1);
            var stack = new ItemStack(Items.STONE); stack.set(DataComponents.CUSTOM_DATA, CustomData.of(a));
            p.setItemInHand(InteractionHand.MAIN_HAND, stack); var first = PlaytestInventory.capture(p);
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(b)); var second = PlaytestInventory.capture(p);
            h.assertTrue(first.digest().equals(second.digest()), "NBT compound insertion order cannot create false divergence");
            b.putInt("z", 2); stack.set(DataComponents.CUSTOM_DATA, CustomData.of(b));
            h.assertTrue(!first.digest().equals(PlaytestInventory.capture(p).digest()), "Persistent components participate in comparison");
            stack.set(SurvivalSpike.TRANSIENT_COMPONENT.get(), "not-persistent");
            rejects(h, () -> PlaytestInventory.capture(p));
        } finally { p.getAdvancements().stopListening(); }
        h.succeed();
    }
    @GameTest(template="empty") public static void playtestDiskInspectionPreservesBytesAndRejectsTruncation(GameTestHelper h) throws Exception {
        var p = actor(h); Path path = Files.createTempFile("foundations-readonly-player-", ".dat");
        try {
            p.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.TORCH, 12));
            NbtIo.writeCompressed(save(p), path); byte[] original = Files.readAllBytes(path);
            var inspected = PlaytestInventory.fromPlayerSave(PlaytestInventory.readSave(path), p.getUUID(), h.getLevel().registryAccess());
            h.assertTrue(inspected.digest().equals(PlaytestInventory.capture(p).digest()), "Actual compressed player save is readable");
            h.assertTrue(Arrays.equals(original, Files.readAllBytes(path)), "Inspection preserves every .dat byte");
            Files.write(path, Arrays.copyOf(original, original.length / 2));
            byte[] bad = Files.readAllBytes(path); rejects(h, () -> PlaytestInventory.readSave(path));
            h.assertTrue(Arrays.equals(bad, Files.readAllBytes(path)), "Malformed save is never repaired, replaced or truncated by inspector");
        } finally { p.getAdvancements().stopListening(); Files.deleteIfExists(path); }
        h.succeed();
    }
    @GameTest(template="empty") public static void playtestRejectsCursorAndSyntheticCommandStart(GameTestHelper h) throws Exception {
        var p = actor(h);
        try {
            h.assertTrue(!FoundationsPlaytest.isConnectedPlayer(p), "Synthetic actors are not counted as connected-player evidence");
            p.containerMenu.setCarried(new ItemStack(Items.DIAMOND)); rejects(h, () -> PlaytestInventory.capture(p));
            p.containerMenu.setCarried(ItemStack.EMPTY);
            var dispatcher = h.getLevel().getServer().getCommands().getDispatcher();
            h.assertTrue(dispatcher.getRoot().getChild("foundations_playtest") != null, "Node-integrated playtest command event registered");
            h.assertTrue(!dispatcher.getRoot().getChild("foundations_playtest").canUse(p.createCommandSourceStack().withPermission(0)), "Only an operator can start diagnostic capture");
            h.assertTrue(!net.neoforged.fml.ModList.get().isLoaded("foundations_cluster_playtest"), "Recorder adds no new mod ID/world fingerprint input");
            int result = dispatcher.execute("foundations_playtest start control fixture", p.createCommandSourceStack().withPermission(2));
            h.assertTrue(result == 0, "Live capture cannot be started by a fake test player");
        } finally { p.getAdvancements().stopListening(); }
        h.succeed();
    }
}
