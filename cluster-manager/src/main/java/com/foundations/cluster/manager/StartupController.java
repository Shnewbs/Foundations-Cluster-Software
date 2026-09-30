package com.foundations.cluster.manager;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

final class StartupController implements AutoCloseable {
    private final ManagerConfig cfg;
    private final TemplateManager templates;
    private final NodeAgentManager nodeAgent;
    private final WorkerManager workers;
    private final ClusterLog log;
    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r,"foundations-startup"); t.setDaemon(true); return t; });
    private final AtomicBoolean bootstrapRequested = new AtomicBoolean(false);
    private volatile boolean baselineComplete;
    private volatile String lastError = "";

    StartupController(ManagerConfig cfg, TemplateManager templates, NodeAgentManager nodeAgent, WorkerManager workers, ClusterLog log) {
        this.cfg=cfg; this.templates=templates; this.nodeAgent=nodeAgent; this.workers=workers; this.log=log;
        exec.scheduleAtFixedRate(this::tick, 1, 2, TimeUnit.SECONDS);
    }

    private void tick() {
        if (cfg.lifecycle.shuttingDown()) return;

        try {
            if (cfg.getBoolean("template.autoBootstrap", true) && !templates.hasNeoForgeArgs() && !templates.installing() && bootstrapRequested.compareAndSet(false,true)) {
                log.info("Startup pipeline: auto-bootstrapping NeoForge server-template");
                templates.bootstrapAsync();
                return;
            }

            if (templates.hasNeoForgeArgs() && nodeAgent.required() && !nodeAgent.installed() && !nodeAgent.building() && nodeAgent.mayAutoRetry()) {
                log.info("Startup pipeline: building/installing Foundations Cluster Node");
                nodeAgent.buildAndInstallAsync();
                return;
            }

            if (!baselineComplete && cfg.getBoolean("startup.autoStartWorkers", true) && templates.ready() && nodeAgent.ready() && gatewayPrerequisitesReady()) {
                int desired = Math.max(0, cfg.getInt("startup.workers", 1));
                int existing = (int) workers.all().stream().filter(w -> w.managed && w.state != WorkerState.OFFLINE && w.state != WorkerState.CRASHED && w.state != WorkerState.ERROR && w.state != WorkerState.KILLED && w.state != WorkerState.RETIRED).count();

                if (existing < desired) {
                    var stored = workers.all().stream()
                            .filter(w -> w.managed && w.state == WorkerState.OFFLINE)
                            .sorted(java.util.Comparator.comparing(w -> w.id))
                            .toList();
                    for (WorkerRecord w : stored) {
                        if (existing >= desired) break;
                        if (workers.startStoredOffline(w.id)) existing++;
                    }
                }

                while (existing < desired) { workers.spawn(existing > 0); existing++; }
                baselineComplete = true;
                lastError = "";
                log.info("Startup pipeline complete: baseline workers=" + desired);
            }
        } catch (Exception e) {
            String m = e.getMessage() == null ? e.toString() : e.getMessage();
            if (!m.equals(lastError)) log.warn("Startup pipeline: " + m);
            lastError = m;
        }
    }

    private boolean gatewayPrerequisitesReady(){
        if(!cfg.getBoolean("gateway.enabled",true)||!cfg.get("gateway.mode","velocity").equalsIgnoreCase("velocity"))return true;
        java.nio.file.Path home=cfg.home();
        java.nio.file.Path plugins = home.resolve("velocity/plugins");
        boolean gatewayPlugin = false;
        if (java.nio.file.Files.isDirectory(plugins)) {
            try (var jars = java.nio.file.Files.list(plugins)) {
                gatewayPlugin = jars.anyMatch(p -> java.nio.file.Files.isRegularFile(p)
                        && p.getFileName().toString().startsWith("foundations_velocity_gateway-")
                        && p.getFileName().toString().endsWith(".jar"));
            } catch (java.io.IOException ignored) {
                gatewayPlugin = false;
            }
        }
        return java.nio.file.Files.isRegularFile(home.resolve("velocity/velocity.jar"))
                && gatewayPlugin
                && java.nio.file.Files.isRegularFile(home.resolve("server-template/mods/proxy-compatible-forge-1.2.2.jar"))
                && java.nio.file.Files.isRegularFile(home.resolve("server-template/config/proxy-compatible-forge.toml"))
                && java.nio.file.Files.isRegularFile(home.resolve("velocity/forwarding.secret"));
    }

    String state() {
        if (templates.installing()) return "TEMPLATE_INSTALLING";
        if (!templates.hasNeoForgeArgs()) return "TEMPLATE_MISSING";
        if (cfg.getBoolean("template.requireEula", true) && !templates.eulaAccepted()) return "EULA_REQUIRED";
        if (nodeAgent.building()) return "NODE_AGENT_BUILDING";
        if (!nodeAgent.ready()) return "NODE_AGENT_REQUIRED";
        if (!gatewayPrerequisitesReady()) return "GATEWAY_PREPARING";
        if (!baselineComplete && cfg.getBoolean("startup.autoStartWorkers", true)) return "STARTING_WORKERS";
        return "READY";
    }

    String statusJson() {
        return "{\"state\":"+Json.q(state())+
                ",\"gatewayPrerequisitesReady\":"+gatewayPrerequisitesReady()+
                ",\"autoStartWorkers\":"+cfg.getBoolean("startup.autoStartWorkers",true)+
                ",\"desiredWorkers\":"+Math.max(0,cfg.getInt("startup.workers",1))+
                ",\"baselineComplete\":"+baselineComplete+
                ",\"lastError\":"+Json.q(lastError)+"}";
    }

    public void close() { exec.shutdownNow(); }
}
