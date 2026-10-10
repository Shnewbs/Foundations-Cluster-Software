package com.foundations.cluster.playtest;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import net.minecraft.commands.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.*;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.*;
import net.neoforged.neoforge.event.level.*;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Opt-in, server-side connected-player baseline integrated in the Cluster Node. Entirely observational: no
 * canceled events, inventory setters, game-world mutations, player-save writes, packets or
 * authority changes. Every capture needs an explicit command by that player.
 * Not an implementation of live Survival intent routing or automatic recovery. */
public final class FoundationsPlaytest {
    public static final String VERSION = "0.0.7a-dev.2";
    private static final int MAX_SESSIONS = 4;
    private static final long MAX_NANOS = TimeUnit.MINUTES.toNanos(5);
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Set<PlaytestTrace> closing = new HashSet<>();
    private final String boot = UUID.randomUUID().toString();
    private long tickStarted;
    private static final class Session {
        final ServerPlayer player;
        final PlaytestTrace trace;
        final List<Pending> pending = new ArrayList<>();
        PlaytestInventory.Image image;
        ItemStack[] lastStacks;
        String lastProblem = "";
        long actionId;
        Session(ServerPlayer player, PlaytestTrace trace) { this.player = player; this.trace = trace; }
    }
    private record Pending(long action, int tick, ServerLevel level, BlockPos pos, Block original, String kind) {}

    public FoundationsPlaytest() {
        NeoForge.EVENT_BUS.addListener(this::tickPre);
        NeoForge.EVENT_BUS.addListener(this::tickPost);
        NeoForge.EVENT_BUS.addListener(this::logout);
        NeoForge.EVENT_BUS.addListener(this::dimension);
        NeoForge.EVENT_BUS.addListener(this::stopping);
        // Lowest-priority observation also receives canceled events. Later listeners
        // at the same priority can still change them; deferred samples are separate.
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, true, this::leftClick);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, true, this::rightClick);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, true, this::rightItem);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, true, this::breaking);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, true, this::placing);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, true, this::drops);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, true, this::toss);
        NeoForge.EVENT_BUS.addListener(this::pickup);
        NeoForge.EVENT_BUS.addListener(this::death);
        org.slf4j.LoggerFactory.getLogger(FoundationsPlaytest.class).info(
            "Foundations Playtest {} loaded. Recording is OFF until /foundations_playtest start; no Survival gates changed.", VERSION);
    }
    public static boolean isConnectedPlayer(ServerPlayer p) {
        return !(p instanceof FakePlayer) && p.connection != null && p.serverLevel().getServer().getPlayerList().getPlayer(p.getUUID()) == p;
    }
    public void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = Commands.literal("foundations_playtest").requires(source -> source.hasPermission(2));
        for (String mode : List.of("control", "seam"))
            root.then(Commands.literal("start").then(Commands.literal(mode).then(Commands.argument("label", StringArgumentType.word())
                .executes(c -> start(c.getSource(), mode, StringArgumentType.getString(c, "label"))))));
        root.then(Commands.literal("mark").then(Commands.argument("label", StringArgumentType.word())
            .executes(c -> mark(c.getSource(), StringArgumentType.getString(c, "label")))));
        root.then(Commands.literal("status").executes(c -> status(c.getSource())));
        root.then(Commands.literal("check-save").executes(c -> checkSave(c.getSource())));
        root.then(Commands.literal("stop").executes(c -> stop(c.getSource())));
        dispatcher.register(root);
    }
    private int start(CommandSourceStack source, String mode, String label) {
        try {
            ServerPlayer p = source.getPlayerOrException();
            if (!isConnectedPlayer(p)) throw new IllegalStateException("A connected real player is required");
            PlaytestTrace.validateLabel(label);
            if (sessions.containsKey(p.getUUID())) throw new IllegalStateException("Stop your existing capture first");
            closing.removeIf(t -> t.completion().isDone());
            if (sessions.size() + closing.size() >= MAX_SESSIONS) throw new IllegalStateException("Four captures are already active or flushing");
            // Verify the initial image before recording. No player state is changed.
            PlaytestInventory.capture(p);
            String worker = Objects.toString(System.getenv("FOUNDATIONS_CLUSTER_WORKER_ID"), "standalone");
            worker = worker.matches("[A-Za-z0-9_-]{1,64}") ? worker : "custom-worker";
            var metadata = Map.of("version", VERSION, "boot", boot, "worker", worker,
                "playerKey", PlaytestInventory.digest(p.getUUID().toString().getBytes(StandardCharsets.UTF_8)),
                "gameMode", p.gameMode.getGameModeForPlayer().getName(), "dimension", p.serverLevel().dimension().location().toString(),
                "minecraft", "1.21.1", "remoteSurvivalEnabledByAddon", "false");
            var trace = new PlaytestTrace(p.serverLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("foundations-playtest"), label, mode, metadata);
            var session = new Session(p, trace); sessions.put(p.getUUID(), session);
            inventory(session, "START", true);
            source.sendSuccess(() -> Component.literal("Playtest recording started (max 5 minutes). Reports contain coordinates, item IDs/counts/damage and component digests, not raw item NBT or chat. No gameplay changes are enabled."), false);
            return 1;
        } catch (Exception ex) { return error(source, "Start refused: " + safeStartError(ex)); }
    }
    private static String safeStartError(Exception ex) {
        // Only our own validation messages, never arbitrary item/mod exception text.
        if (ex instanceof IllegalArgumentException || ex instanceof IllegalStateException)
            return ex.getMessage() == null ? "invalid capture state" : ex.getMessage();
        return "close inventories/cursors and check the report directory/limit; unsupported item data is not recorded";
    }
    private Session own(CommandSourceStack source) throws Exception {
        var p = source.getPlayerOrException();
        var s = sessions.get(p.getUUID());
        if (s == null || s.player != p) throw new IllegalStateException("No capture on this worker. Use /foundations_playtest start control <label> or start seam <label>");
        return s;
    }
    private int mark(CommandSourceStack source, String label) {
        try {
            PlaytestTrace.validateLabel(label); var s = own(source);
            emit(s, "MARK", Map.of("label", label)); inventory(s, "MARK", true);
            source.sendSuccess(() -> Component.literal("Playtest marker recorded: " + label), false); return 1;
        } catch (Exception ex) { return error(source, "No active capture or invalid marker"); }
    }
    private int status(CommandSourceStack source) {
        try {
            var s = own(source);
            source.sendSuccess(() -> Component.literal("Playtest: " + s.trace.records() + " records, " + s.trace.dropped() +
                " dropped; " + (s.trace.failure().isEmpty() ? "recording" : "report write failed") + ". Remote Survival is NOT enabled by this recorder."), false);
            return 1;
        } catch (Exception ex) { return error(source, "No capture is active on this worker"); }
    }
    private int stop(CommandSourceStack source) {
        try { finish(own(source), "PLAYER_STOP", true); return 1; }
        catch (Exception ex) { return error(source, "No capture is active on this worker"); }
    }
    private int checkSave(CommandSourceStack source) {
        Session s;
        try { s = own(source); }
        catch (Exception ex) { return error(source, "Start a capture first"); }
        // Deliberately synchronous and explicit, not a per-tick file read. Check after
        // /save-all flush while standing still with menus closed. The result compares
        // CURRENT ordinary inventory with the LAST .dat; unsaved changes can differ.
        try {
            var live = PlaytestInventory.capture(s.player);
            Path file = s.player.serverLevel().getServer().getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(s.player.getUUID() + ".dat");
            CompoundTag saved = PlaytestInventory.readSave(file);
            var disk = PlaytestInventory.fromPlayerSave(saved, s.player.getUUID(), s.player.serverLevel().registryAccess());
            String result = live.digest().equals(disk.digest()) ? "MATCH" : "MISMATCH";
            emit(s, "SAVE_CHECK", Map.of("result", result, "liveDigest", live.digest(), "savedDigest", disk.digest(),
                "differentSlots", live.differences(disk).toString(), "selectionMatches", Boolean.toString(live.selected() == disk.selected()),
                "scope", "current ordinary inventory vs last player .dat; no restore or durability proof"));
            source.sendSuccess(() -> Component.literal("Player-save inventory: " + result + ". A mismatch can be an unsaved change. No file or inventory was modified."), false);
            return result.equals("MATCH") ? 1 : 0;
        } catch (Exception ex) {
            emit(s, "SAVE_CHECK", Map.of("result", ex instanceof NoSuchFileException ? "MISSING" : "UNSUPPORTED_OR_UNREADABLE"));
            return error(source, "Player-save check unavailable: missing/unsupported data or an open menu. No state was changed.");
        }
    }
    private static int error(CommandSourceStack source, String text) { source.sendFailure(Component.literal(text)); return 0; }
    private Session active(Player player) {
        if (!(player instanceof ServerPlayer p) || !p.serverLevel().getServer().isSameThread()) return null;
        var s = sessions.get(p.getUUID()); return s != null && s.player == p ? s : null;
    }
    private void emit(Session s, String kind, Map<String, String> fields) {
        if (s == null) return;
        try { s.trace.record(s.player.serverLevel().getServer().getTickCount(), kind, fields); }
        catch (RuntimeException ex) { s.trace.record(-1, "OBSERVATION_ERROR", Map.of("category", "RECORD_LIMIT_OR_FORMAT")); }
    }
    private void inventory(Session s, String cause, boolean force) {
        try {
            var p = s.player;
            if (!force && s.lastStacks != null && s.image != null && p.containerMenu == p.inventoryMenu &&
                    p.containerMenu.getCarried().isEmpty() && s.image.selected() == p.getInventory().selected && s.lastProblem.isEmpty()) {
                boolean same = true;
                for (int i = 0; i < PlaytestInventory.SLOTS; i++) if (!ItemStack.matches(s.lastStacks[i], p.getInventory().getItem(i))) { same = false; break; }
                if (same) return;
            }
            long begin = System.nanoTime(); var image = PlaytestInventory.capture(p);
            List<Integer> changed = s.image == null ? java.util.stream.IntStream.range(0, PlaytestInventory.SLOTS).boxed().toList() : s.image.differences(image);
            var fields = new TreeMap<String, String>();
            fields.put("cause", cause); fields.put("digest", image.digest()); fields.put("selected", Integer.toString(image.selected()));
            fields.put("changedSlots", changed.toString()); fields.put("encodeNanos", Long.toString(Math.max(0, System.nanoTime() - begin)));
            fields.put("food", Integer.toString(p.getFoodData().getFoodLevel())); fields.put("saturation", Float.toString(p.getFoodData().getSaturationLevel()));
            fields.put("health", Float.toString(p.getHealth())); fields.put("experienceLevel", Integer.toString(p.experienceLevel));
            for (int slot : changed) fields.put("slot." + slot, image.slots().get(slot).summary());
            emit(s, "INVENTORY", fields);
            s.image = image; s.lastStacks = new ItemStack[PlaytestInventory.SLOTS];
            for (int i = 0; i < s.lastStacks.length; i++) s.lastStacks[i] = p.getInventory().getItem(i).copy();
            s.lastProblem = "";
        } catch (Exception ex) {
            if (s.lastProblem.isEmpty()) emit(s, "INVENTORY_UNSUPPORTED", Map.of("cause", cause,
                "category", "OPEN_MENU_CURSOR_LAYOUT_OR_ITEM_CODEC"));
            s.lastProblem = "UNSUPPORTED";
        }
    }
    private Map<String, String> blockFields(BlockPos pos, ServerPlayer p, boolean canceled) {
        return Map.of("position", pos.getX() + "," + pos.getY() + "," + pos.getZ(),
            "dimension", p.serverLevel().dimension().location().toString(), "canceledAtObserver", Boolean.toString(canceled));
    }
    private void action(Session s, String kind, BlockPos pos, Block block, boolean canceled) {
        if (s == null) return;
        long id = ++s.actionId;
        var f = new TreeMap<>(blockFields(pos, s.player, canceled));
        f.put("action", Long.toString(id)); f.put("block", BuiltInRegistries.BLOCK.getKey(block).toString());
        emit(s, kind, f);
        if (s.pending.size() < 32) s.pending.add(new Pending(id, s.player.serverLevel().getServer().getTickCount(), s.player.serverLevel(), pos.immutable(), block, kind));
        else emit(s, "RESULT_SAMPLE_OMITTED", Map.of("action", Long.toString(id), "reason", "PENDING_SAMPLE_LIMIT"));
    }
    private void leftClick(PlayerInteractEvent.LeftClickBlock e) {
        var s = active(e.getEntity()); if (s == null) return;
        var f = new TreeMap<>(blockFields(e.getPos(), s.player, e.isCanceled())); f.put("miningAction", e.getAction().name());
        emit(s, "LEFT_CLICK", f); inventory(s, "LEFT_CLICK", false);
    }
    private void rightClick(PlayerInteractEvent.RightClickBlock e) {
        var s = active(e.getEntity()); if (s == null) return;
        var chunk = s.player.serverLevel().getChunkSource().getChunkNow(e.getPos().getX() >> 4, e.getPos().getZ() >> 4);
        if (chunk != null) action(s, "USE_BLOCK", e.getPos(), chunk.getBlockState(e.getPos()).getBlock(), e.isCanceled());
        inventory(s, "USE_BLOCK", false);
    }
    private void rightItem(PlayerInteractEvent.RightClickItem e) {
        var s = active(e.getEntity()); if (s != null) { emit(s, "USE_ITEM", Map.of("hand", e.getHand().name(), "canceledAtObserver", Boolean.toString(e.isCanceled()))); inventory(s, "USE_ITEM", false); }
    }
    private void breaking(BlockEvent.BreakEvent e) {
        var s = active(e.getPlayer()); action(s, "BREAK_EVENT", e.getPos(), e.getState().getBlock(), e.isCanceled());
    }
    private void placing(BlockEvent.EntityPlaceEvent e) {
        if (e.getEntity() instanceof Player p) action(active(p), "PLACE_EVENT", e.getPos(), e.getPlacedBlock().getBlock(), e.isCanceled());
    }
    private void drops(BlockDropsEvent e) {
        if (!(e.getBreaker() instanceof Player p)) return;
        var s = active(p); if (s == null) return;
        emit(s, "BLOCK_DROPS_EVENT", Map.of("entitiesAtObserver", Integer.toString(e.getDrops().size()), "canceledAtObserver", Boolean.toString(e.isCanceled())));
    }
    private void toss(ItemTossEvent e) {
        var s = active(e.getPlayer()); if (s == null) return;
        emit(s, "ITEM_TOSS", Map.of("item", BuiltInRegistries.ITEM.getKey(e.getEntity().getItem().getItem()).toString(),
            "count", Integer.toString(e.getEntity().getItem().getCount()), "canceledAtObserver", Boolean.toString(e.isCanceled()),
            "entityKey", PlaytestInventory.digest(e.getEntity().getUUID().toString().getBytes(StandardCharsets.UTF_8))));
        inventory(s, "ITEM_TOSS", false);
    }
    private void pickup(ItemEntityPickupEvent.Post e) {
        var s = active(e.getPlayer()); if (s == null) return;
        var original = e.getOriginalStack();
        emit(s, "ITEM_PICKUP", Map.of("item", BuiltInRegistries.ITEM.getKey(original.getItem()).toString(),
            "originalCount", Integer.toString(original.getCount()), "remainingCount", Integer.toString(e.getCurrentStack().getCount()),
            "entityKey", PlaytestInventory.digest(e.getItemEntity().getUUID().toString().getBytes(StandardCharsets.UTF_8))));
        inventory(s, "ITEM_PICKUP", false);
    }
    private void death(LivingDeathEvent e) {
        if (e.getEntity() instanceof Player p) { var s = active(p); if (s != null) emit(s, "PLAYER_DEATH_EVENT", Map.of()); }
    }
    private void dimension(PlayerEvent.PlayerChangedDimensionEvent e) {
        var s = active(e.getEntity()); if (s != null) { emit(s, "DIMENSION_CHANGE", Map.of("from", e.getFrom().location().toString(), "to", e.getTo().location().toString())); inventory(s, "DIMENSION_CHANGE", true); }
    }
    private void logout(PlayerEvent.PlayerLoggedOutEvent e) {
        var s = active(e.getEntity()); if (s != null) finish(s, "LOGOUT_OR_BACKEND_HANDOFF", false);
    }
    private void tickPre(ServerTickEvent.Pre e) { if (!sessions.isEmpty()) tickStarted = System.nanoTime(); }
    private void tickPost(ServerTickEvent.Post e) {
        if (sessions.isEmpty()) return;
        long tickNanos = tickStarted == 0 ? 0 : Math.max(0, System.nanoTime() - tickStarted);
        int tick = e.getServer().getTickCount();
        for (var s : List.copyOf(sessions.values())) {
            if (!isConnectedPlayer(s.player)) { finish(s, "PLAYER_INSTANCE_LEFT", false); continue; }
            if (s.trace.elapsedNanos() >= MAX_NANOS || s.trace.dropped() > 0 || !s.trace.failure().isEmpty()) {
                finish(s, s.trace.elapsedNanos() >= MAX_NANOS ? "TIME_LIMIT" : "CAPTURE_LIMIT_OR_IO_FAILURE", true); continue;
            }
            try {
                for (Iterator<Pending> iterator = s.pending.iterator(); iterator.hasNext();) {
                    var a = iterator.next(); if (a.tick() == tick) continue;
                    iterator.remove();
                    var chunk = a.level().getChunkSource().getChunkNow(a.pos().getX() >> 4, a.pos().getZ() >> 4);
                    var f = new TreeMap<String, String>(); f.put("action", Long.toString(a.action())); f.put("sourceKind", a.kind());
                    f.put("ticksLater", Integer.toString(tick - a.tick()));
                    f.put("observedState", chunk == null ? "UNLOADED" : chunk.getBlockState(a.pos()).toString());
                    f.put("minedStat", Integer.toString(s.player.getStats().getValue(Stats.BLOCK_MINED.get(a.original()))));
                    f.put("scope", "later server sample; not a client acknowledgement or unique action attribution");
                    emit(s, "RESULT_SAMPLE", f);
                    inventory(s, "RESULT_SAMPLE", false);
                }
                if (tick % 5 == 0) inventory(s, "POLL", false);
                if (tick % 20 == 0) emit(s, "SERVER_SAMPLE", Map.of("tickNanos", Long.toString(tickNanos),
                    "x", Double.toString(s.player.getX()), "y", Double.toString(s.player.getY()), "z", Double.toString(s.player.getZ()),
                    "dimension", s.player.serverLevel().dimension().location().toString(), "gameMode", s.player.gameMode.getGameModeForPlayer().getName()));
            } catch (Exception ex) { emit(s, "OBSERVATION_ERROR", Map.of("category", "SAMPLE_FAILED")); }
        }
    }
    private void finish(Session s, String reason, boolean notify) {
        if (sessions.remove(s.player.getUUID()) != s) return;
        inventory(s, "END", true);
        if (!s.pending.isEmpty()) emit(s, "RESULT_SAMPLE_OMITTED", Map.of("count", Integer.toString(s.pending.size()), "reason", "CAPTURE_ENDED"));
        s.trace.finish(reason); closing.add(s.trace);
        MinecraftServer server = s.player.serverLevel().getServer();
        if (notify) s.trace.completion().whenComplete((path, error) -> server.execute(() -> {
            closing.remove(s.trace);
            if (isConnectedPlayer(s.player)) s.player.sendSystemMessage(Component.literal(error == null ?
                "Playtest report saved: <world>/foundations-playtest/" + path.getFileName() +
                ". Recording has stopped. Review the report before sharing." : "Playtest report write failed; do not treat the capture as complete."));
        }));
    }
    private void stopping(ServerStoppingEvent e) {
        for (var s : List.copyOf(sessions.values())) finish(s, "SERVER_STOP", false);
        for (var trace : List.copyOf(closing)) {
            try { trace.close(); } catch (IOException ignored) { /* absent footer/completion failure remains explicit */ }
        }
        closing.clear(); tickStarted = 0;
    }
}
