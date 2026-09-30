package com.foundations.cluster.manager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/**
 * Browser/admin console for Foundations and Minecraft server commands.
 * Intentionally does NOT expose an operating-system shell.
 */
final class ConsoleService {
    record Result(String target, String command, List<String> lines, int affected) {}
    record HistoryEntry(String time, String actor, String target, String command, String result) {}

    private final ManagerConfig cfg;
    private final WorkerManager workers;
    private final LayerPlanner layers;
    private final RegionAuthorityManager regions;
    private final PlayerStateManager playerStates;
    private final SessionManager sessions;
    private final HandoffManager handoffs;
    private final WorkerEvacuationManager evacuations;
    private final DataPlaneManager dataPlane;
    private final GatewayServer gateway;
    private final ClusterLog log;
    private final AuditLog audit;
    private final Path historyFile;

    ConsoleService(ManagerConfig cfg, WorkerManager workers, LayerPlanner layers, RegionAuthorityManager regions, PlayerStateManager playerStates, SessionManager sessions, HandoffManager handoffs, WorkerEvacuationManager evacuations, DataPlaneManager dataPlane, GatewayServer gateway,
                   ClusterLog log, AuditLog audit) throws IOException {
        this.cfg = cfg; this.workers = workers; this.layers = layers; this.regions = regions; this.playerStates = playerStates; this.sessions = sessions; this.handoffs=handoffs; this.evacuations=evacuations; this.dataPlane=dataPlane; this.gateway = gateway; this.log = log; this.audit = audit;
        this.historyFile = cfg.home().resolve("logs/console-history.log");
        Files.createDirectories(historyFile.getParent());
        if (!Files.exists(historyFile)) Files.createFile(historyFile);
    }

    Result execute(String actor, String targetRaw, String commandRaw) throws IOException {
        String actorSafe = actor == null || actor.isBlank() ? "admin" : actor;
        String target = normalizeTarget(targetRaw);
        String command = commandRaw == null ? "" : commandRaw.trim();
        int max = Math.max(64, cfg.getInt("console.maxCommandLength", 2048));
        if (command.isBlank()) throw new IllegalArgumentException("Console command may not be blank.");
        if (command.length() > max) throw new IllegalArgumentException("Console command exceeds " + max + " characters.");
        if (command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0) throw new IllegalArgumentException("Only one console command may be executed at a time.");

        try {
            Result result;
            if (target.equals("cluster")) result = clusterCommand(command);
            else if (target.equals("all-workers")) result = broadcast(command);
            else {
                workers.sendConsoleCommand(target, command);
                result = new Result(target, command, List.of("Command sent to " + target + ": " + stripSlash(command)), 1);
            }
            audit.record(actorSafe, "CONSOLE_COMMAND", target, command);
            appendHistory(actorSafe, target, command, summarize(result.lines()));
            return result;
        } catch (RuntimeException | IOException ex) {
            audit.record(actorSafe, "CONSOLE_COMMAND_FAILED", target, command + " :: " + ex.getMessage());
            appendHistory(actorSafe, target, command, "ERROR: " + clean(ex.getMessage()));
            throw ex;
        }
    }

    List<String> output(String targetRaw, int limit) {
        String target = normalizeTarget(targetRaw);
        int safeLimit = Math.max(1, Math.min(5000, limit));
        if (target.equals("cluster")) return log.tail(safeLimit);
        if (target.equals("all-workers")) return combinedWorkerTail(safeLimit);
        return workerTail(target, safeLimit);
    }

    List<HistoryEntry> history(int limit) {
        try {
            List<String> lines = Files.readAllLines(historyFile, StandardCharsets.UTF_8);
            int from = Math.max(0, lines.size() - Math.max(1, Math.min(1000, limit)));
            List<HistoryEntry> out = new ArrayList<>();
            for (String line : lines.subList(from, lines.size())) {
                String[] p = line.split("\\t", 5);
                if (p.length >= 4) out.add(new HistoryEntry(p[0], p[1], p[2], p[3], p.length > 4 ? p[4] : ""));
            }
            return out;
        } catch (IOException e) { return List.of(); }
    }

    private Result broadcast(String command) {
        List<String> lines = new ArrayList<>();
        int sent = 0;
        for (WorkerRecord w : workers.all().stream().sorted(Comparator.comparing(a -> a.id)).toList()) {
            try {
                workers.sendConsoleCommand(w.id, command);
                sent++;
                lines.add("SENT " + w.id);
            } catch (Exception ex) {
                lines.add("SKIP " + w.id + " — " + ex.getMessage());
            }
        }
        if (sent == 0) throw new IllegalStateException("No locally managed running worker accepted the command.");
        lines.add(0, "Broadcast command to " + sent + " worker(s): " + stripSlash(command));
        return new Result("all-workers", command, lines, sent);
    }

    private Result clusterCommand(String raw) throws IOException {
        String cmd = raw.trim();
        if (cmd.toLowerCase(Locale.ROOT).startsWith("cluster ")) cmd = cmd.substring(8).trim();
        String[] a = cmd.split("\\s+");
        String root = a[0].toLowerCase(Locale.ROOT);
        List<String> lines = new ArrayList<>();
        int affected = 0;

        switch (root) {
            case "help", "?" -> lines.addAll(help());
            case "version" -> lines.add("Foundations Cluster Runtime " + ClusterVersion.VERSION + " · Node protocol " + ClusterVersion.NODE_VERSION);
            case "status" -> {
                var all = workers.all();
                int players = all.stream().mapToInt(w -> w.players).sum();
                var sum = layers.summary();
                long routable = all.stream().filter(WorkerRecord::routableForNewSession).count();
                lines.add("Workers: " + routable + "/" + all.size() + " routable");
                lines.add("Players: " + players + " · Sessions: " + sessions.count());
                lines.add("Layers: " + sum.activeLayers() + " · Cells: " + sum.groups() + " · Assignments: " + sum.assignments());
                var gw = gateway.snapshot();
                lines.add("Gateway: " + (gw.running() ? "READY " + gw.bind() + ":" + gw.port() : gw.enabled() ? "ERROR" : "DISABLED")
                        + " · active=" + gw.activeConnections() + " · routed=" + gw.routedConnections());
                lines.add("Session model: " + handoffs.mode() + " · normal region movement preserves the client anchor=" + handoffs.persistentSessionMode());
                var rs = regions.summary();
                lines.add("Region Authority: ACTIVE records=" + rs.total() + " active=" + rs.active() + " suspect=" + rs.suspect() + " recovering=" + rs.recovering() + " owners=" + rs.owners());
                lines.add("Write fencing: CONTROL-PLANE ONLY | R7.R5 block + generic BlockEntity persistence ACTIVE (entity/POI/global SavedData pending)");
            }
            case "gateway" -> {
                var g = gateway.snapshot();
                lines.add("Gateway " + (g.running() ? "READY" : g.enabled() ? "ERROR" : "DISABLED") + " on " + g.bind() + ":" + g.port());
                lines.add("Connections: active=" + g.activeConnections() + " total=" + g.totalConnections() + " routed=" + g.routedConnections() + " status=" + g.statusRequests() + " failed=" + g.failedConnections());
                if (!g.lastError().isBlank()) lines.add("Last error: " + g.lastError());
                for (var r : g.routes()) lines.add(r.playerId() + " -> " + r.workerId() + ":" + r.workerPort() + (r.layerId().isBlank()?"":" -> "+r.layerId()) + " remote=" + r.remoteAddress());
            }
            case "workers" -> {
                var list = workers.all().stream().sorted(Comparator.comparing(w -> w.id)).toList();
                if (list.isEmpty()) lines.add("No workers in inventory.");
                for (WorkerRecord w : list) lines.add(String.format(Locale.ROOT,
                        "%s %-11s :%d players=%d mspt=%.1f layers=%d pid=%d",
                        w.id, w.state, w.port, w.players, w.mspt, layers.activeLayerCountForWorker(w.id), workers.effectivePid(w.id)));
            }
            case "spawn" -> {
                boolean warm = a.length > 1 && a[1].equalsIgnoreCase("warm");
                WorkerRecord w = workers.spawn(warm); affected = 1;
                lines.add("Spawned " + w.id + " on port " + w.port + (warm ? " as warm capacity" : ""));
            }
            case "drain", "undrain" -> {
                requireArgs(a, 2, root + " <workerId>");
                workers.drain(a[1], root.equals("drain")); affected = 1;
                lines.add((root.equals("drain") ? "Draining " : "Undrained ") + a[1]);
            }
            case "maintenance" -> {
                requireArgs(a, 3, "maintenance <workerId> <on|off>");
                boolean on = parseOnOff(a[2]); workers.maintenance(a[1], on); affected = 1;
                lines.add("Maintenance " + (on ? "enabled" : "disabled") + " for " + a[1]);
            }
            case "restart" -> {
                requireArgs(a, 2, "restart <workerId>"); evacuations.restart(a[1]); affected = 1; lines.add("Graceful restart requested for " + a[1]);
            }
            case "stop" -> {
                requireArgs(a, 2, "stop <workerId>"); evacuations.begin(a[1]); affected = 1; lines.add("Graceful stop requested for " + a[1]);
            }
            case "kill" -> {
                requireArgs(a, 2, "kill <workerId>"); workers.kill(a[1]); affected = 1; lines.add("Emergency kill requested for " + a[1]);
            }
            case "sessions" -> {
                var list = sessions.all().stream().sorted(Comparator.comparing(s -> s.playerId)).toList();
                if (list.isEmpty()) lines.add("No active session placements.");
                for (SessionRecord s : list) lines.add(s.playerId + " anchor=" + s.anchorWorkerId + " simulation=" + s.simulationWorkerId + (s.layerId.isBlank() ? "" : " -> " + s.layerId)
                        + " cell=" + s.dimensionId + "[" + s.cellX + "," + s.cellZ + "] region=" + (s.regionId.isBlank()?"—":s.regionId) + " authority=" + (s.authorityWorkerId.isBlank()?"—":s.authorityWorkerId+"@"+s.authorityEpoch));
            }
            case "player" -> {
                requireArgs(a, 3, "player find <playerId>");
                if (!a[1].equalsIgnoreCase("find")) throw new IllegalArgumentException("Usage: player find <playerId>");
                String q = a[2]; boolean found = false;
                for (SessionRecord s : sessions.all()) if (s.playerId.equalsIgnoreCase(q)) {
                    found = true; lines.add(s.playerId + " session=" + s.sessionId + " anchor=" + s.anchorWorkerId + " simulation=" + s.simulationWorkerId + " layer=" + (s.layerId.isBlank()?"—":s.layerId)
                            + " cell=" + s.dimensionId + "[" + s.cellX + "," + s.cellZ + "] party=" + (s.partyId.isBlank()?"—":s.partyId));
                }
                ClusterPlayerState ps = playerStates.findByName(q);
                if (ps != null) {
                    found = true;
                    lines.add("Cluster state: uuid=" + ps.uuid + " worker=" + ps.workerId + " dim=" + ps.dimensionId
                            + String.format(Locale.ROOT," pos=%.1f,%.1f,%.1f",ps.x,ps.y,ps.z) + " gamemode=" + ps.gameMode + " op=" + ps.op + " rev=" + ps.revision);
                }
                if (!found) lines.add("No placed session or cluster player state found for player " + q + ".");
            }
            case "layer" -> {
                requireArgs(a, 2, "layer <list|create|freeze|unfreeze|drain|undrain|retire> ...");
                String sub = a[1].toLowerCase(Locale.ROOT);
                if (sub.equals("list")) {
                    var list = layers.allLayers().stream().sorted(Comparator.comparing(l -> l.id)).toList();
                    if (list.isEmpty()) lines.add("No population layers exist yet.");
                    for (LayerRecord l : list) lines.add(l.id + " " + l.state + " worker=" + l.workerId + " pop=" + layers.population(l)
                            + " cell=" + l.cell.display() + " slot=" + l.slot);
                } else if (sub.equals("create")) {
                    requireArgs(a, 4, "layer create <cellX> <cellZ> [workerId]");
                    int x = Integer.parseInt(a[2]), z = Integer.parseInt(a[3]);
                    String preferred = a.length > 4 ? a[4] : null;
                    LayerCellKey cell = new LayerCellKey(cfg.get("layer.defaultWorld","foundations"), cfg.get("layer.defaultDimension","minecraft:overworld"), x, z);
                    LayerRecord l = layers.createLayer(cell, preferred, "console-create"); affected = 1;
                    lines.add("Created " + l.id + " on " + l.workerId + " for " + cell.display());
                } else {
                    requireArgs(a, 3, "layer " + sub + " <layerId>"); String id = a[2];
                    switch (sub) {
                        case "freeze" -> layers.freeze(id);
                        case "unfreeze" -> layers.unfreeze(id);
                        case "drain" -> layers.drain(id);
                        case "undrain" -> layers.undrain(id);
                        case "retire" -> layers.retire(id);
                        default -> throw new IllegalArgumentException("Unknown layer command: " + sub);
                    }
                    affected = 1; lines.add("Layer " + id + " -> " + sub);
                }
            }
            case "region" -> {
                requireArgs(a, 2, "region <list|ensure|transfer|freeze|unfreeze|expire> ...");
                String sub = a[1].toLowerCase(Locale.ROOT);
                if (sub.equals("list")) {
                    var list = regions.all().stream().sorted(Comparator.comparing(r -> r.key.id())).toList();
                    if (list.isEmpty()) lines.add("No Region Authority records exist yet.");
                    for (RegionRecord r : list) lines.add(r.key.id()+" "+r.state+" owner="+(r.ownerWorkerId.isBlank()?"—":r.ownerWorkerId)+" epoch="+r.epoch+" lease="+Math.max(0,(r.leaseExpiresAtMillis-System.currentTimeMillis())/1000)+"s");
                } else if (sub.equals("ensure")) {
                    requireArgs(a,4,"region ensure <regionX> <regionZ> [workerId]");
                    int x=Integer.parseInt(a[2]),z=Integer.parseInt(a[3]); String preferred=a.length>4?a[4]:null;
                    RegionKey key=new RegionKey(cfg.get("layer.defaultWorld","foundations"),cfg.get("layer.defaultDimension","minecraft:overworld"),x,z);
                    RegionRecord r=regions.ensure(key,preferred,"console ensure"); affected=1; lines.add("Ensured "+r.key.id()+" owner="+r.ownerWorkerId+" epoch="+r.epoch);
                } else if (sub.equals("transfer")) {
                    requireArgs(a,4,"region transfer <regionId> <workerId>"); RegionRecord r=regions.transfer(a[2],a[3],"console transfer"); affected=1; lines.add("Transferred "+r.key.id()+" -> "+r.ownerWorkerId+" epoch="+r.epoch);
                } else if (sub.equals("freeze") || sub.equals("unfreeze")) {
                    requireArgs(a,3,"region "+sub+" <regionId>"); regions.freeze(a[2],sub.equals("freeze")); affected=1; lines.add("Region "+a[2]+" -> "+sub);
                } else if (sub.equals("expire")) {
                    requireArgs(a,3,"region expire <regionId>"); regions.forceExpire(a[2]); affected=1; lines.add("Expired authority lease for "+a[2]);
                } else throw new IllegalArgumentException("Unknown region command: "+sub);
            }
            case "dataplane" -> {
                var d=dataPlane.summary();
                lines.add("Data Plane: "+(d.enabled()?"ENABLED":"DISABLED")+" pending="+d.pending()+" inflight="+d.inflight()+" published="+d.published()+" delivered="+d.delivered()+" acked="+d.acked()+" rejected="+d.rejected()+" dropped="+d.dropped());
                affected=0;
            }
            case "handoff" -> {
                requireArgs(a,2,"handoff <list|request|abort> ...");
                String sub=a[1].toLowerCase(Locale.ROOT);
                if(sub.equals("list")){
                    var list=handoffs.all().stream().sorted(Comparator.comparingLong(r->r.createdAtMillis)).toList();
                    if(list.isEmpty())lines.add("No handoffs recorded.");
                    for(HandoffRecord r:list)lines.add(r.id+" "+r.state+" player="+r.playerId+" anchor="+r.sourceWorkerId+" simulationTarget="+r.destinationWorkerId+" mode="+handoffs.mode()+(r.error.isBlank()?"":" error="+r.error));
                }else if(sub.equals("request")){
                    requireArgs(a,3,"handoff request <player> [worker]");String dest=a.length>3?a[3]:"";HandoffRecord r=handoffs.requestManualBackendTransfer(a[2],dest,"console physical worker transfer");affected=1;lines.add("Player transfer "+r.id+" requested: "+r.playerId+" "+r.sourceWorkerId+" -> "+r.destinationWorkerId);
                }else if(sub.equals("abort")){
                    requireArgs(a,3,"handoff abort <handoffId>");HandoffRecord r=handoffs.abort(a[2],"console abort");affected=1;lines.add("Handoff "+r.id+" -> "+r.state);
                }else throw new IllegalArgumentException("Unknown handoff command: "+sub);
            }
            case "orphans" -> {
                var os = workers.orphanProcesses();
                if (os.isEmpty()) lines.add("No orphaned Foundations worker JVMs detected.");
                for (var o : os) lines.add(o.workerId() + " pid=" + o.pid() + " port=" + o.port());
            }
            default -> throw new IllegalArgumentException("Unknown cluster command: " + root + ". Type 'help'.");
        }
        return new Result("cluster", raw, lines, affected);
    }

    private List<String> help() {
        return List.of(
                "Foundations cluster console commands:",
                "  status | version | gateway | workers | spawn [warm]",
                "  drain <worker> | undrain <worker> | maintenance <worker> <on|off>",
                "  restart <worker> | stop <worker> | kill <worker>",
                "  sessions | player find <player>",
                "  handoff list | handoff request <player> [worker] | handoff abort <handoffId>",
                "  dataplane",
                "  region list | region ensure <regionX> <regionZ> [worker]",
                "  region transfer <regionId> <worker> | region freeze|unfreeze|expire <regionId>",
                "  layer list | layer create <cellX> <cellZ> [worker]",
                "  layer freeze|unfreeze|drain|undrain|retire <layerId>",
                "  orphans",
                "Select a worker target to send native Minecraft server commands such as list, say, save-all, whitelist, etc.",
                "OS shell access is intentionally not available from this console."
        );
    }

    private List<String> combinedWorkerTail(int limit) {
        List<WorkerRecord> list = workers.all().stream().sorted(Comparator.comparing(w -> w.id)).toList();
        if (list.isEmpty()) return List.of("No workers in inventory.");
        int per = Math.max(10, (int)Math.ceil(limit / (double)Math.max(1, list.size())));
        List<String> out = new ArrayList<>();
        for (WorkerRecord w : list) for (String line : workerTail(w.id, per)) out.add("[" + w.id + "] " + line);
        if (out.size() > limit) return new ArrayList<>(out.subList(out.size() - limit, out.size()));
        return out;
    }

    private List<String> workerTail(String workerId, int limit) {
        Path base = cfg.home().resolve("logs/workers").normalize();
        Path file = base.resolve(workerId + ".log").normalize();
        if (!file.startsWith(base) || !Files.isRegularFile(file)) return List.of("No worker log available for " + workerId + ".");
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            int from = Math.max(0, lines.size() - limit);
            return new ArrayList<>(lines.subList(from, lines.size()));
        } catch (IOException e) { return List.of("Could not read worker log: " + e.getMessage()); }
    }

    private String normalizeTarget(String raw) {
        String target = raw == null || raw.isBlank() ? "cluster" : raw.trim();
        if (target.equals("cluster") || target.equals("all-workers")) return target;
        if (!target.matches("[A-Za-z0-9._-]{1,64}")) throw new IllegalArgumentException("Unknown console target: " + target);
        if (workers.get(target) == null) throw new IllegalArgumentException("Unknown worker: " + target);
        return target;
    }

    private synchronized void appendHistory(String actor, String target, String command, String result) {
        String line = Instant.now() + "\t" + clean(actor) + "\t" + clean(target) + "\t" + clean(command) + "\t" + clean(result) + System.lineSeparator();
        try { Files.writeString(historyFile, line, StandardCharsets.UTF_8, StandardOpenOption.APPEND); } catch (IOException ignored) {}
    }

    private static String summarize(List<String> lines) { return lines == null || lines.isEmpty() ? "OK" : clean(lines.get(0)); }
    private static String clean(String s) { return (s == null ? "" : s).replace('\t',' ').replace('\n',' ').replace('\r',' '); }
    private static String stripSlash(String s) { String x=s.trim(); return x.startsWith("/") ? x.substring(1) : x; }
    private static void requireArgs(String[] a, int n, String usage) { if (a.length < n) throw new IllegalArgumentException("Usage: " + usage); }
    private static boolean parseOnOff(String v) {
        if (Set.of("on","true","1","yes").contains(v.toLowerCase(Locale.ROOT))) return true;
        if (Set.of("off","false","0","no").contains(v.toLowerCase(Locale.ROOT))) return false;
        throw new IllegalArgumentException("Expected on/off.");
    }
}
