package com.foundations.cluster.manager;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

public final class Main {
    public static void main(String[] args) throws Exception {
        try {
            run();
        } catch (Throwable t) {
            writeStartupFailure(t);
            t.printStackTrace(System.err);
            if (t instanceof Exception e) throw e;
            throw new RuntimeException(t);
        }
    }

    private static void run() throws Exception {
        ManagerConfig cfg=ManagerConfig.load();
        try (ManagerLock lock=ManagerLock.acquire(cfg.home().resolve("runtime"))) {
            if(Files.exists(cfg.home().resolve("runtime/update-in-progress.json")))
                throw new IllegalStateException("An update was interrupted. Restore the recorded update backup before starting the cluster.");
            ClusterLog log=new ClusterLog(cfg.home().resolve("logs/cluster-manager.log"),cfg.getInt("logs.maxDashboardLines",300));
            log.info("Foundations Cluster Runtime "+ClusterVersion.VERSION+" starting (package "+ClusterVersion.PACKAGE_REVISION+")");
            log.info("Startup: initializing audit and encrypted security state");
            AuditLog audit=new AuditLog(cfg.home().resolve("logs/audit.log"));
            TokenStore tokens=new TokenStore(cfg.home().resolve("runtime"));
            log.info("Startup: encrypted security vault ready");
            AuthManager auth=new AuthManager(cfg,log);
            log.info("Scaling mode: "+cfg.get("scaling.mode","manual")+" (human controls remain available)");
            log.info("Dashboard security: "+(auth.setupRequired()?"administrator setup required":"administrator credentials loaded"));
            TemplateManager templates=new TemplateManager(cfg,log);
            NodeAgentManager nodeAgent=new NodeAgentManager(cfg,templates,log);
            WorkerManager workers=new WorkerManager(cfg,tokens,log,templates,nodeAgent);
            LayerPlanner layers=new LayerPlanner(cfg,workers,log);
            RegionAuthorityManager regions=new RegionAuthorityManager(cfg,workers,log);
            PlayerStateManager playerStates=new PlayerStateManager(cfg,log);
            PlayerProfileManager playerProfiles=new PlayerProfileManager(cfg,log);
            SessionManager sessions=new SessionManager(cfg,workers,layers,regions,playerStates,log);
            HandoffManager handoffs=new HandoffManager(cfg,workers,sessions,layers,playerStates,playerProfiles,log);
            WorkerEvacuationManager evacuations=new WorkerEvacuationManager(cfg,workers,sessions,handoffs,layers,regions,log);
            DataPlaneManager dataPlane=new DataPlaneManager(cfg,workers,sessions,regions,log);
            GatewayServer gateway=new GatewayServer(cfg,tokens,workers,sessions,playerStates,layers,log);
            ConsoleService console=new ConsoleService(cfg,workers,layers,regions,playerStates,sessions,handoffs,evacuations,dataPlane,gateway,log,audit);
            ScalingController scaling=new ScalingController(cfg,workers,evacuations,log,audit);
            StartupController startup=new StartupController(cfg,templates,nodeAgent,workers,log);
            MetricsRecorder metrics=new MetricsRecorder(cfg,workers);
            AlertManager alerts=new AlertManager(cfg,workers,startup,gateway);
            log.info("Startup: starting Web UI/API server on "+cfg.get("web.bind","127.0.0.1")+":"+cfg.getInt("web.port",8765));
            ApiServer api=new ApiServer(cfg,tokens,auth,audit,workers,scaling,templates,nodeAgent,startup,layers,regions,playerStates,playerProfiles,sessions,handoffs,evacuations,dataPlane,gateway,metrics,alerts,console,log);
            api.start();
            startGatewayAfterNodeReady(cfg, nodeAgent, gateway, log);
            log.info("Cluster Manager READY");
            log.info("Internal agent/gateway/admin API secrets loaded from encrypted runtime/security vault");

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                cfg.lifecycle.beginShutdown("JVM shutdown");
                log.info("Cluster Manager shutting down; automatic recovery suppressed");
                startup.close(); scaling.close(); evacuations.close();
                try { Thread.sleep(1200L); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                try { workers.shutdownManaged(cfg.getLong("shutdown.workerWaitSeconds",45)*1000L); } catch(Exception ignored) {}
                gateway.close(); api.close(); metrics.close(); startup.close(); scaling.close(); evacuations.close(); dataPlane.close(); handoffs.close(); sessions.close(); playerStates.close(); regions.close(); layers.close(); workers.close();
            },"cluster-shutdown"));

            Thread.currentThread().join();
        }
    }


    private static void startGatewayAfterNodeReady(ManagerConfig cfg, NodeAgentManager nodeAgent, GatewayServer gateway, ClusterLog log) {
        if (!cfg.getBoolean("gateway.enabled", true) || !cfg.get("gateway.mode", "velocity").equalsIgnoreCase("velocity")) {
            gateway.start();
            return;
        }
        Thread.ofVirtual().name("foundations-gateway-start-sequencer").start(() -> {
            boolean announced = false;
            try {
                while (!cfg.lifecycle.shuttingDown() && nodeAgent.required() && !nodeAgent.ready()) {
                    if (!announced) {
                        log.info("Velocity Gateway waiting for Foundations Cluster Node build/install to finish");
                        announced = true;
                    }
                    Thread.sleep(500L);
                }
                if (cfg.lifecycle.shuttingDown()) return;
                if (announced) log.info("Cluster Node ready; continuing Velocity Gateway bootstrap");
                gateway.start();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("Velocity Gateway startup sequencer failed: " + (e.getMessage()==null?e.toString():e.getMessage()));
            }
        });
    }

    private static void writeStartupFailure(Throwable t) {
        try {
            Path dir=Path.of("logs").toAbsolutePath().normalize();
            Files.createDirectories(dir);
            Path file=dir.resolve("cluster-manager-startup-error.log");
            StringWriter sw=new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            String msg=Instant.now()+" [FATAL] Cluster Manager failed during startup\n"+sw+System.lineSeparator();
            Files.writeString(file,msg, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {}
    }
}
