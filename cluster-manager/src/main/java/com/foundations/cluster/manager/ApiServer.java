package com.foundations.cluster.manager;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Executors;

final class ApiServer implements AutoCloseable {
    private final ManagerConfig cfg; private final TokenStore tokens; private final AuthManager auth; private final AuditLog audit;
    private final WorkerManager workers; private final ScalingController scaling; private final TemplateManager templates; private final NodeAgentManager nodeAgent;
    private final StartupController startup; private final LayerPlanner layers; private final RegionAuthorityManager regions; private final PlayerStateManager playerStates; private final PlayerProfileManager playerProfiles; private final SessionManager sessions; private final HandoffManager handoffs; private final WorkerEvacuationManager evacuations; private final DataPlaneManager dataPlane; private final GatewayServer gateway; private final MetricsRecorder metrics; private final AlertManager alerts; private final ConsoleService console; private final ClusterLog log;
    private final HttpServer server; private volatile boolean shutdownRequested;
    private final WorldEnvironmentCoordinator environment;
    private final SupportArchives support;
    private final TransferTrace transferTrace = new TransferTrace();

    ApiServer(ManagerConfig cfg, TokenStore tokens, AuthManager auth, AuditLog audit, WorkerManager workers, ScalingController scaling,
              TemplateManager templates, NodeAgentManager nodeAgent, StartupController startup, LayerPlanner layers, RegionAuthorityManager regions, PlayerStateManager playerStates, PlayerProfileManager playerProfiles, SessionManager sessions, HandoffManager handoffs, WorkerEvacuationManager evacuations, DataPlaneManager dataPlane, GatewayServer gateway,
              MetricsRecorder metrics, AlertManager alerts, ConsoleService console, ClusterLog log) throws IOException {
        this.cfg=cfg;this.tokens=tokens;this.auth=auth;this.audit=audit;this.workers=workers;this.scaling=scaling;this.templates=templates;this.nodeAgent=nodeAgent;
        this.startup=startup;this.layers=layers;this.regions=regions;this.playerStates=playerStates;this.playerProfiles=playerProfiles;this.sessions=sessions;this.handoffs=handoffs;this.evacuations=evacuations;this.dataPlane=dataPlane;this.gateway=gateway;this.metrics=metrics;this.alerts=alerts;this.console=console;this.log=log;
        environment=new WorldEnvironmentCoordinator(cfg,log);
        support=new SupportArchives(cfg,log,limit->{
            Map<String,byte[]> captures=new LinkedHashMap<>(dataPlane.diagnosticSnapshots(limit));
            captures.put("summary.zip",DiagnosticBundle.create(cfg,workers,transferTrace));
            return captures;
        },List.of(tokens.adminToken,tokens.agentToken,tokens.gatewayToken));
        server=HttpServer.create(new InetSocketAddress(cfg.get("web.bind","127.0.0.1"),cfg.getInt("web.port",8765)),0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        context("/api/health", e->json(e,200,"{\"ok\":true,\"version\":"+Json.q(ClusterVersion.VERSION)+"}"));
        context("/api/auth/status", this::authStatus); context("/api/auth/setup",this::authSetup); context("/api/auth/login",this::authLogin); context("/api/auth/logout",this::authLogout); context("/api/auth/me",this::authMe);
        context("/api/security/password",this::changePassword); context("/api/security/revoke-sessions",this::revokeSessions);
        context("/api/status",this::status); context("/api/workers",this::workersEndpoint);
        context("/api/template/status",this::templateStatus); context("/api/template/bootstrap",this::templateBootstrap); context("/api/template/eula/accept",this::templateEulaAccept);
        context("/api/node-agent/status",this::nodeAgentStatus); context("/api/node-agent/build",this::nodeAgentBuild); context("/api/startup/status",this::startupStatus);
        context("/api/route/suggest",this::routeSuggest); context("/api/sessions",this::sessionsEndpoint); context("/api/handoffs",this::handoffsEndpoint); context("/api/gateway/handoffs",this::gatewayHandoffsEndpoint); context("/api/layers",this::layersEndpoint); context("/api/regions",this::regionsEndpoint); context("/api/player-state",this::playerStateEndpoint); context("/api/dataplane/status",this::dataPlaneStatus); context("/api/gateway/status",this::gatewayStatus); context("/api/gateway/topology",this::gatewayTopology); context("/api/gateway/heartbeat",this::gatewayHeartbeat); context("/api/gateway/failover",this::gatewayFailover); context("/api/logs",this::logs);
        context("/api/config",this::configEndpoint); context("/api/scaling/status",this::scalingStatus); context("/api/scaling/approve",this::scalingApprove); context("/api/metrics",this::metricsEndpoint); context("/api/alerts",this::alertsEndpoint); context("/api/audit",this::auditEndpoint);
        context("/api/console/execute",this::consoleExecute); context("/api/console/output",this::consoleOutput); context("/api/console/history",this::consoleHistory);
        context("/api/system/restart-workers",this::restartWorkers); context("/api/system/shutdown",this::shutdown);
        context("/api/agent/region-checkpoint",this::agentRegionCheckpoint); context("/api/agent/persistence/blocks",this::agentBlockBatch); context("/api/agent/register",this::agentRegister); context("/api/agent/heartbeat",this::agentHeartbeat); context("/api/agent/offline",this::agentOffline); context("/api/agent/player-state",this::agentPlayerState); context("/api/agent/player-restore",this::agentPlayerRestore); context("/api/agent/handoff",this::agentHandoffEndpoint); context("/api/agent/dataplane/publish",this::agentDataPlanePublish); context("/api/agent/dataplane/poll",this::agentDataPlanePoll); context("/api/agent/dataplane/ack",this::agentDataPlaneAck); context("/api/agent/persistence/replay",this::agentPersistenceReplay);
        context("/api/alerts/acknowledge",this::acknowledgeAlert);
        context("/api/world/environment",e->{if(admin(e))json(e,200,environment.statusJson());});
        context("/api/host", e -> {if(admin(e))json(e,200,HostTelemetry.snapshot(cfg.home()));});
        context("/api/diagnostics/download",this::diagnostics);
        context("/api/support",this::supportEndpoint);
        context("/api/gateway/trace",this::gatewayTrace);
        context("/api/transfers/trace",e -> {if(admin(e))json(e,200,transferTrace.json());});
        context("/",this::staticFile);
    }

    void start(){server.start();support.start();log.info("Web control plane listening at http://"+cfg.get("web.bind","127.0.0.1")+":"+cfg.getInt("web.port",8765));}

    private void authStatus(HttpExchange e)throws IOException{json(e,200,auth.statusJson(e));}
    private void authSetup(HttpExchange e)throws IOException{
        if(!post(e))return;Map<String,String>m=Json.flatObject(body(e));
        try{var r=auth.setup(m.get("username"),m.get("password"),Json.b(m,"remember",true),e);e.getResponseHeaders().add("Set-Cookie",auth.cookieHeader(r));audit.record(r.username(),"AUTH_SETUP","admin","First-run administrator configured");json(e,201,"{\"ok\":true,\"username\":"+Json.q(r.username())+"}");}
        catch(SecurityException ex){json(e,403,"{\"error\":"+Json.q(ex.getMessage())+"}");}catch(Exception ex){json(e,400,"{\"error\":"+Json.q(ex.getMessage())+"}");}
    }
    private void authLogin(HttpExchange e)throws IOException{
        if(!post(e))return;Map<String,String>m=Json.flatObject(body(e));String remote=String.valueOf(e.getRemoteAddress());
        try{var r=auth.login(m.getOrDefault("username",""),m.getOrDefault("password",""),Json.b(m,"remember",false),remote);e.getResponseHeaders().add("Set-Cookie",auth.cookieHeader(r));audit.record(r.username(),"LOGIN","dashboard",remote);json(e,200,"{\"ok\":true,\"username\":"+Json.q(r.username())+",\"expiresAt\":"+r.expiresAt()+"}");}
        catch(SecurityException ex){audit.record(m.getOrDefault("username","?"),"LOGIN_FAILED","dashboard",remote);json(e,401,"{\"error\":"+Json.q(ex.getMessage())+"}");}catch(Exception ex){json(e,400,"{\"error\":"+Json.q(ex.getMessage())+"}");}
    }
    private void authLogout(HttpExchange e)throws IOException{if(!post(e))return;String a=actor(e);auth.logout(e);e.getResponseHeaders().add("Set-Cookie",auth.clearCookieHeader());audit.record(a,"LOGOUT","dashboard","");json(e,200,"{\"ok\":true}");}
    private void authMe(HttpExchange e)throws IOException{String u=auth.authenticatedUser(e);if(u==null){json(e,401,"{\"authenticated\":false}");return;}json(e,200,"{\"authenticated\":true,\"username\":"+Json.q(u)+",\"role\":\"ADMIN\"}");}
    private void changePassword(HttpExchange e)throws IOException{if(!admin(e)||!post(e))return;Map<String,String>m=Json.flatObject(body(e));String a=actor(e);try{auth.changePassword(m.getOrDefault("currentPassword",""),m.getOrDefault("newPassword",""),e);audit.record(a,"PASSWORD_CHANGE","admin","All sessions revoked");e.getResponseHeaders().add("Set-Cookie",auth.clearCookieHeader());json(e,200,"{\"ok\":true,\"reauthenticate\":true}");}catch(Exception ex){json(e,400,"{\"error\":"+Json.q(ex.getMessage())+"}");}}
    private void revokeSessions(HttpExchange e)throws IOException{if(!admin(e)||!post(e))return;String a=actor(e);auth.revokeAllSessions();audit.record(a,"REVOKE_SESSIONS","admin","All remembered sessions revoked");e.getResponseHeaders().add("Set-Cookie",auth.clearCookieHeader());json(e,200,"{\"ok\":true,\"reauthenticate\":true}");}

    private void status(HttpExchange e)throws IOException{
        if(!admin(e))return;var inventory=workers.all();var all=inventory.stream().filter(w->MetricsRecorder.hasFreshTelemetry(w,cfg.getLong("worker.heartbeatTimeoutSeconds",10))).toList();int players=all.stream().mapToInt(w->w.players).sum();long used=all.stream().mapToLong(w->w.heapUsed).sum(),max=all.stream().mapToLong(w->w.heapMax).sum();double mspt=all.stream().filter(w->w.lastHeartbeatMillis>0).mapToDouble(w->w.mspt).average().orElse(0),cpu=all.stream().filter(w->w.lastHeartbeatMillis>0).mapToDouble(w->w.cpuPercent).average().orElse(0);long ready=all.stream().filter(WorkerRecord::routableForNewSession).count();int activeAlerts=alerts.current().size();double health=clusterHealth(all,mspt);
        var ls=layers.summary();var rs=regions.summary();var gs=gateway.snapshot();var ps=dataPlane.persistenceSummary();var bes=dataPlane.blockEntityPersistenceSummary();
        String j="{\"lifecycle\":"+Json.q(cfg.lifecycle.state())+",\"version\":"+Json.q(ClusterVersion.VERSION)+",\"clusterName\":"+Json.q(cfg.get("cluster.name","Foundations")) +",\"scalingMode\":"+Json.q(scaling.mode())+",\"workers\":"+inventory.size()+",\"readyWorkers\":"+ready+",\"players\":"+players+",\"sessions\":"+sessions.count()+",\"avgMspt\":"+fmt(mspt)+",\"avgCpu\":"+fmt(cpu)+",\"heapUsed\":"+used+",\"heapMax\":"+max+",\"healthPercent\":"+fmt(health)+",\"alerts\":"+activeAlerts+",\"startupState\":"+Json.q(startup.state())+",\"gatewayEnabled\":"+gs.enabled()+",\"gatewayRunning\":"+gs.running()+",\"gatewayPort\":"+gs.port()+",\"gatewayConnections\":"+gs.activeConnections()+",\"regionAuthority\":"+regions.enabled()+",\"regionWriteFencing\":"+regions.writeFencingEnforced()+",\"regions\":"+rs.total()+",\"activeRegions\":"+rs.active()+",\"suspectRegions\":"+rs.suspect()+",\"recoveringRegions\":"+rs.recovering()+",\"regionOwners\":"+rs.owners()+",\"persistenceEnabled\":"+(ps.enabled()||bes.enabled())+",\"persistenceProtocol\":\"block+blockentity-journal-v3\",\"persistenceRevision\":"+Math.max(ps.revision(),bes.revision())+",\"persistenceBlocks\":"+ps.blocks()+",\"persistenceWalEntries\":"+ps.walEntries()+",\"persistenceBlockEntities\":"+bes.blockEntities()+",\"persistenceBlockEntityRevision\":"+bes.revision()+",\"persistenceBlockEntityWalEntries\":"+bes.walEntries()+",\"persistenceBlockEntityPayloadBytes\":"+bes.payloadBytes()+",\"playerStateRecords\":"+playerStates.all().size()+",\"layerPlanner\":"+layers.enabled()+",\"layers\":"+ls.activeLayers()+",\"layerGroups\":"+ls.groups()+",\"layerAssignments\":"+ls.assignments()+"}";
        json(e,200,j);
    }
    private double clusterHealth(Collection<WorkerRecord> all,double mspt){if(all.isEmpty())return startup.state().equals("READY")?100:80;double ready=all.stream().filter(w->w.state==WorkerState.READY||w.state==WorkerState.ACTIVE).count()/(double)all.size();double perf=mspt<=20?1:Math.max(0,1-(mspt-20)/80.0);return Math.max(0,Math.min(100,(ready*.7+perf*.3)*100));}

    private void configEndpoint(HttpExchange e)throws IOException{
        if(!admin(e))return;
        if(e.getRequestMethod().equals("GET")){StringBuilder b=new StringBuilder("{\"values\":{");boolean first=true;for(var x:cfg.editableSnapshot().entrySet()){if(!first)b.append(',');first=false;b.append(Json.q(x.getKey())).append(':').append(Json.q(x.getValue()));}b.append("},\"restartRequiredKeys\":[");first=true;for(String k:ManagerConfig.RESTART_REQUIRED_KEYS){if(!first)b.append(',');first=false;b.append(Json.q(k));}b.append("],\"newWorkersOnlyKeys\":[");first=true;for(String k:ManagerConfig.NEW_WORKERS_ONLY_KEYS){if(!first)b.append(',');first=false;b.append(Json.q(k));}b.append("],\"workerRestartRequiredKeys\":[");first=true;for(String k:ManagerConfig.WORKER_RESTART_REQUIRED_KEYS){if(!first)b.append(',');first=false;b.append(Json.q(k));}json(e,200,b.append("]}").toString());return;}
        if(e.getRequestMethod().equals("PUT")||e.getRequestMethod().equals("POST")){try{Map<String,String>m=Json.flatObject(body(e));var r=cfg.updateEditable(m);audit.record(actor(e),"CONFIG_UPDATE","cluster",String.join(",",r.changed().keySet()));json(e,200,updateResultJson(r));}catch(Exception ex){json(e,400,"{\"error\":"+Json.q(ex.getMessage())+"}");}return;}
        json(e,405,"{}");
    }
    private String updateResultJson(ManagerConfig.UpdateResult r){return "{\"ok\":true,\"changed\":"+r.changed().size()+",\"restartRequired\":"+stringArray(r.restartRequired())+",\"newWorkersOnly\":"+stringArray(r.newWorkersOnly())+",\"workerRestartRequired\":"+stringArray(r.workerRestartRequired())+"}";}

    private void scalingStatus(HttpExchange e)throws IOException{if(!admin(e))return;json(e,200,scaling.statusJson());}
    private void scalingApprove(HttpExchange e)throws IOException{if(!admin(e)||!post(e))return;String a=actor(e);try{String action=scaling.approveRecommendation();audit.record(a,"SCALING_APPROVE","cluster",action);json(e,200,"{\"ok\":true,\"action\":"+Json.q(action)+"}");}catch(Exception ex){json(e,409,"{\"error\":"+Json.q(ex.getMessage())+"}");}}

    private void metricsEndpoint(HttpExchange e)throws IOException{if(!admin(e))return;if(query(e).containsKey("workerId")){json(e,200,metrics.workerJson(query(e).get("workerId")));return;}int limit=intQuery(e,"limit",120,1,2000);var samples=query(e).containsKey("seconds")?metrics.range(intQuery(e,"seconds",900,300,604800)):metrics.tail(limit);StringBuilder b=new StringBuilder("{\"samples\":[");boolean first=true;for(var s:samples){if(!first)b.append(',');first=false;b.append("{\"time\":").append(s.time()).append(",\"workers\":").append(s.workers()).append(",\"readyWorkers\":").append(s.readyWorkers()).append(",\"players\":").append(s.players()).append(",\"mspt\":").append(fmt(s.mspt())).append(",\"cpu\":").append(fmt(s.cpu())).append(",\"heapUsed\":").append(s.heapUsed()).append(",\"heapMax\":").append(s.heapMax()).append('}');}json(e,200,b.append("]}").toString());}
    private void alertsEndpoint(HttpExchange e)throws IOException{if(!admin(e))return;StringBuilder b=new StringBuilder("{\"alerts\":[");boolean first=true;for(var a:alerts.current()){if(!first)b.append(',');first=false;b.append("{\"id\":").append(Json.q(AlertManager.key(a))).append(",\"acknowledgedBy\":").append(Json.q(alerts.actor(a))).append(",\"firstSeen\":").append(alerts.firstSeen(a)).append(",\"severity\":").append(Json.q(a.severity())).append(",\"type\":").append(Json.q(a.type())).append(",\"target\":").append(Json.q(a.target())).append(",\"message\":").append(Json.q(a.message())).append('}');}json(e,200,b.append("]}").toString());}
    private void auditEndpoint(HttpExchange e)throws IOException{if(!admin(e))return;int limit=intQuery(e,"limit",200,1,1000);StringBuilder b=new StringBuilder("{\"entries\":[");boolean first=true;for(var a:audit.tail(limit)){if(!first)b.append(',');first=false;b.append("{\"time\":").append(Json.q(a.time())).append(",\"actor\":").append(Json.q(a.actor())).append(",\"action\":").append(Json.q(a.action())).append(",\"target\":").append(Json.q(a.target())).append(",\"detail\":").append(Json.q(a.detail())).append('}');}json(e,200,b.append("]}").toString());}

    private void templateStatus(HttpExchange e)throws IOException{if(!admin(e))return;json(e,200,templates.statusJson());}
    private void templateBootstrap(HttpExchange e)throws IOException{if(!admin(e)||!post(e))return;try{templates.bootstrapAsync();audit.record(actor(e),"TEMPLATE_BOOTSTRAP","server-template",templates.neoForgeVersion());json(e,202,templates.statusJson());}catch(Exception ex){json(e,409,"{\"error\":"+Json.q(ex.getMessage())+"}");}}
    private void templateEulaAccept(HttpExchange e)throws IOException{if(!admin(e)||!post(e))return;try{templates.acceptEula();audit.record(actor(e),"EULA_ACCEPT","server-template","Minecraft EULA marked accepted");json(e,200,templates.statusJson());}catch(Exception ex){json(e,409,"{\"error\":"+Json.q(ex.getMessage())+"}");}}
    private void nodeAgentStatus(HttpExchange e)throws IOException{if(!admin(e))return;json(e,200,nodeAgent.statusJson());}
    private void nodeAgentBuild(HttpExchange e)throws IOException{if(!admin(e)||!post(e))return;try{nodeAgent.buildAndInstallAsync();audit.record(actor(e),"NODE_BUILD","cluster-node",ClusterVersion.NODE_VERSION);json(e,202,nodeAgent.statusJson());}catch(Exception ex){json(e,409,"{\"error\":"+Json.q(ex.getMessage())+"}");}}
    private void startupStatus(HttpExchange e)throws IOException{if(!admin(e))return;json(e,200,startup.statusJson());}

    private void workersEndpoint(HttpExchange e)throws IOException{
        if(!admin(e))return;String path=e.getRequestURI().getPath(),method=e.getRequestMethod();
        if(path.equals("/api/workers")&&method.equals("GET")){json(e,200,workersJson());return;}
        if(path.equals("/api/workers/orphans")&&method.equals("GET")){json(e,200,orphansJson());return;}
        if(path.equals("/api/workers/spawn")&&method.equals("POST")){boolean warm=query(e).getOrDefault("warm","false").equalsIgnoreCase("true");try{WorkerRecord w=workers.spawn(warm);audit.record(actor(e),"WORKER_SPAWN",w.id,"warm="+warm);json(e,201,"{\"workerId\":"+Json.q(w.id)+"}");}catch(Exception ex){json(e,409,"{\"error\":"+Json.q(ex.getMessage())+"}");}return;}
        String orphanPrefix="/api/workers/orphans/";
        if(path.startsWith(orphanPrefix)&&method.equals("POST")){String rest=path.substring(orphanPrefix.length());String[]parts=rest.split("/");if(parts.length==2&&parts[1].equals("kill")){try{long pid=Long.parseLong(parts[0]);workers.killOrphan(pid);audit.record(actor(e),"ORPHAN_KILL",Long.toString(pid),"");json(e,200,"{\"ok\":true}");}catch(Exception ex){json(e,409,"{\"error\":"+Json.q(ex.getMessage())+"}");}return;}}
        String prefix="/api/workers/";if(path.startsWith(prefix)){String rest=path.substring(prefix.length());String[]parts=rest.split("/");
            if(parts.length==2&&method.equals("POST")){String id=URLDecoder.decode(parts[0],StandardCharsets.UTF_8),action=parts[1];try{
                switch(action){
                    case"pin"->workers.pin(id,true);
                    case"unpin"->workers.pin(id,false);
                    case"drain"->workers.drain(id,true);
                    case"undrain"->workers.drain(id,false);
                    case"maintenance"->workers.maintenance(id,true);
                    case"unmaintenance"->workers.maintenance(id,false);
                    case"stop"->{var st=evacuations.begin(id);audit.record(actor(e),"WORKER_STOP",id,"graceful evacuation deadline="+st.deadlineMillis());json(e,202,stopStatusJson(id,st));return;}
                    case"cancel-stop"->{var st=evacuations.cancel(id);audit.record(actor(e),"WORKER_STOP_CANCEL",id,"");json(e,200,stopStatusJson(id,st));return;}
                    case"restart"->{var st=evacuations.restart(id);audit.record(actor(e),"WORKER_RESTART",id,"graceful evacuation/restart");json(e,202,stopStatusJson(id,st));return;}
                    case"force-stop", "kill"->{evacuations.forceStop(id);audit.record(actor(e),"WORKER_FORCE_STOP",id,"");json(e,200,"{\"ok\":true,\"state\":\"KILLED\"}");return;}
                    case"retire"->{beginRetireWorker(id,actor(e));audit.record(actor(e),"WORKER_RETIRE",id,"drain layers/sessions then stop");json(e,202,"{\"ok\":true,\"state\":\"RETIRING\"}");return;}
                    case"delete"->deleteWorkerSafe(id);
                    case"force-delete"->forceDeleteWorker(id);
                    default->{json(e,404,"{\"error\":\"unknown action\"}");return;}
                }
                if(!action.equals("retire"))audit.record(actor(e),"WORKER_"+action.toUpperCase(Locale.ROOT).replace('-','_'),id,"");
                json(e,200,"{\"ok\":true}");
            }catch(IllegalStateException ex){json(e,409,"{\"error\":"+Json.q(ex.getMessage())+"}");}catch(Exception ex){json(e,404,"{\"error\":"+Json.q(ex.getMessage())+"}");}return;}
            if(parts.length==2&&parts[1].equals("log")&&method.equals("GET")){workerLog(e,parts[0]);return;}}
        json(e,404,"{\"error\":\"not found\"}");
    }

    private void beginRetireWorker(String id,String actor){
        WorkerRecord w=workers.get(id);if(w==null)throw new IllegalArgumentException("Unknown worker: "+id);if(!w.managed)throw new IllegalStateException(id+" is externally managed and cannot be retired automatically.");
        workers.drain(id,true);layers.drainWorkerLayers(id);
        try{int moved=regions.evacuateWorker(id,"worker retire");if(moved>0){log.info("Retire evacuated "+moved+" authority region(s) from "+id);audit.record(actor,"REGION_EVACUATE",id,"regions="+moved);}}catch(Exception ex){log.warn("Worker retirement blocked by Region Authority for "+id+": "+ex.getMessage());audit.record(actor,"WORKER_RETIRE_AUTHORITY_BLOCK",id,ex.getMessage());return;}
        Thread.ofVirtual().name("worker-retire-"+id).start(()->{
            long deadline=System.currentTimeMillis()+Math.max(60,cfg.getLong("worker.retireTimeoutSeconds",1800))*1000L;
            try{
                while(System.currentTimeMillis()<deadline){
                    layers.retireEmptyWorkerLayers(id);
                    if(sessions.countForWorker(id)==0&&layers.activeLayerCountForWorker(id)==0&&regions.activeRegionCountForWorker(id)==0){
                        workers.stop(id);
                        long stopDeadline=System.currentTimeMillis()+Math.max(15,cfg.getLong("worker.stopGraceSeconds",30)+10)*1000L;
                        while(System.currentTimeMillis()<stopDeadline&&workers.processAlive(id))Thread.sleep(250);
                        if(workers.processAlive(id))workers.kill(id);
                        workers.markRetired(id);
                        audit.record(actor,"WORKER_RETIRED",id,"retirement complete");
                        return;
                    }
                    Thread.sleep(1000);
                }
                log.warn("Worker retirement timed out for "+id+"; worker remains drained. sessions="+sessions.countForWorker(id)+" layers="+layers.activeLayerCountForWorker(id)+" authorityRegions="+regions.activeRegionCountForWorker(id));
                audit.record(actor,"WORKER_RETIRE_TIMEOUT",id,"sessions="+sessions.countForWorker(id)+" layers="+layers.activeLayerCountForWorker(id)+" authorityRegions="+regions.activeRegionCountForWorker(id));
            }catch(Exception ex){log.warn("Worker retirement failed for "+id+": "+ex.getMessage());audit.record(actor,"WORKER_RETIRE_ERROR",id,ex.getMessage());}
        });
    }

    private void deleteWorkerSafe(String id)throws IOException{
        WorkerRecord w=workers.get(id);if(w==null)throw new IllegalArgumentException("Unknown worker: "+id);
        int sessionCount=sessions.countForWorker(id),layerCount=layers.activeLayerCountForWorker(id),authorityCount=regions.activeRegionCountForWorker(id);
        if(w.process!=null&&w.process.isAlive())throw new IllegalStateException("Worker is still running. Stop or retire it before deleting.");
        if(w.players>0||sessionCount>0||layerCount>0||authorityCount>0)throw new IllegalStateException("Delete blocked: players="+w.players+", sessions="+sessionCount+", activeLayers="+layerCount+", authorityRegions="+authorityCount+". Drain/retire first or use Force Delete.");
        workers.delete(id);
    }

    private void forceDeleteWorker(String id)throws Exception{
        WorkerRecord w=workers.get(id);if(w==null)throw new IllegalArgumentException("Unknown worker: "+id);if(!w.managed)throw new IllegalStateException("Force Delete is only available for locally managed workers.");
        int released=sessions.releaseForWorker(id);int retired=layers.forceRetireWorkerLayers(id);int releasedRegions=regions.forceReleaseWorker(id,"force delete");
        if(w.process!=null&&w.process.isAlive()){workers.kill(id);long deadline=System.currentTimeMillis()+5000;while(System.currentTimeMillis()<deadline&&workers.processAlive(id))Thread.sleep(100);}
        if(workers.processAlive(id))throw new IllegalStateException("Worker process did not terminate; directory was not deleted.");
        workers.delete(id);log.warn("Force deleted "+id+" releasedSessions="+released+" retiredLayers="+retired+" releasedRegions="+releasedRegions);
    }

    private String orphansJson(){StringBuilder b=new StringBuilder("{\"orphans\":[");boolean first=true;for(var o:workers.orphanProcesses()){if(!first)b.append(',');first=false;b.append("{\"pid\":").append(o.pid()).append(",\"workerId\":").append(Json.q(o.workerId())).append(",\"port\":").append(o.port()).append(",\"command\":").append(Json.q(o.command())).append('}');}return b.append("]}").toString();}

    private void workerLog(HttpExchange e,String id)throws IOException{Path f=cfg.home().resolve("logs/workers/"+id+".log").normalize();if(!f.startsWith(cfg.home().resolve("logs/workers").normalize())||workers.get(id)==null){json(e,404,"{\"error\":\"worker not found\"}");return;}if(!Files.isRegularFile(f)){json(e,200,"{\"lines\":[]}");return;}int limit=intQuery(e,"limit",250,1,2000);List<String>lines=LogTail.read(f,limit);int from=Math.max(0,lines.size()-limit);StringBuilder b=new StringBuilder("{\"lines\":[");boolean first=true;for(String s:lines.subList(from,lines.size())){if(!first)b.append(',');first=false;b.append(Json.q(s));}json(e,200,b.append("]}").toString());}
    private void routeSuggest(HttpExchange e)throws IOException{if(!admin(e))return;WorkerRecord w=workers.routeCandidate();if(w==null)json(e,503,"{\"error\":\"no READY/ACTIVE non-draining worker available\"}");else json(e,200,"{\"workerId\":"+Json.q(w.id)+",\"host\":"+Json.q(cfg.get("gateway.backendHost","127.0.0.1")) +",\"port\":"+w.port+",\"players\":"+w.players+"}");}

    private void sessionsEndpoint(HttpExchange e)throws IOException{
        String path=e.getRequestURI().getPath(),method=e.getRequestMethod();
        if(path.equals("/api/sessions")&&method.equals("GET")){if(!admin(e))return;json(e,200,sessionsJson());return;}
        if(path.equals("/api/sessions/place")&&method.equals("POST")){
            if(!gatewayOrAdmin(e))return;
            try{
                cfg.lifecycle.requireRunning();
                SessionManager.Placement p=sessions.place(Json.flatObject(body(e)));
                WorkerRecord w=p.worker();SessionRecord s=p.session();LayerRecord l=p.layer();
                ClusterPlayerState profileState=playerStates.findByName(s.playerId);
                if(profileState!=null&&!profileState.uuid.isBlank())playerProfiles.prepare(w.id,profileState.uuid,profileState.name);
                if(adminAuthenticated(e))audit.record(actor(e),"SESSION_PLACE",s.playerId,w.id+(l==null?"":" layer="+l.id));
                json(e,200,"{\"sessionId\":"+Json.q(s.sessionId)+",\"playerId\":"+Json.q(s.playerId)+",\"partyId\":"+Json.q(s.partyId)+",\"workerId\":"+Json.q(w.id)+",\"anchorWorkerId\":"+Json.q(s.anchorWorkerId)+",\"simulationWorkerId\":"+Json.q(s.simulationWorkerId)+",\"layerId\":"+Json.q(s.layerId)+",\"worldId\":"+Json.q(s.worldId)+",\"dimensionId\":"+Json.q(s.dimensionId)+",\"cellX\":"+s.cellX+",\"cellZ\":"+s.cellZ+",\"regionId\":"+Json.q(s.regionId)+",\"regionX\":"+s.regionX+",\"regionZ\":"+s.regionZ+",\"authorityWorkerId\":"+Json.q(s.authorityWorkerId)+",\"authorityEpoch\":"+s.authorityEpoch+",\"host\":"+Json.q(cfg.get("gateway.backendHost","127.0.0.1")) +",\"port\":"+w.port+",\"reason\":"+Json.q(p.reason())+"}");
            }catch(Exception ex){json(e,503,"{\"error\":"+Json.q(ex.getMessage())+"}");}
            return;
        }
        String prefix="/api/sessions/";
        if(path.startsWith(prefix)&&path.endsWith("/release")&&method.equals("POST")){
            if(!gatewayOrAdmin(e))return;String id=URLDecoder.decode(path.substring(prefix.length(),path.length()-"/release".length()),StandardCharsets.UTF_8);sessions.release(id);if(adminAuthenticated(e))audit.record(actor(e),"SESSION_RELEASE",id,"");json(e,200,"{\"ok\":true}");return;
        }
        json(e,404,"{\"error\":\"not found\"}");
    }
    private String sessionsJson(){StringBuilder b=new StringBuilder("{\"sessions\":[");boolean first=true;for(SessionRecord s:sessions.all().stream().sorted(Comparator.comparing(a->a.playerId)).toList()){if(!first)b.append(',');first=false;b.append("{\"sessionId\":").append(Json.q(s.sessionId)).append(",\"playerId\":").append(Json.q(s.playerId)).append(",\"partyId\":").append(Json.q(s.partyId)).append(",\"workerId\":").append(Json.q(s.workerId)).append(",\"anchorWorkerId\":").append(Json.q(s.anchorWorkerId)).append(",\"simulationWorkerId\":").append(Json.q(s.simulationWorkerId)).append(",\"layerId\":").append(Json.q(s.layerId)).append(",\"worldId\":").append(Json.q(s.worldId)).append(",\"dimensionId\":").append(Json.q(s.dimensionId)).append(",\"cellX\":").append(s.cellX).append(",\"cellZ\":").append(s.cellZ).append(",\"regionId\":").append(Json.q(s.regionId)).append(",\"regionX\":").append(s.regionX).append(",\"regionZ\":").append(s.regionZ).append(",\"authorityWorkerId\":").append(Json.q(s.authorityWorkerId)).append(",\"authorityEpoch\":").append(s.authorityEpoch).append(",\"ageSeconds\":").append(Math.max(0,(System.currentTimeMillis()-s.createdAtMillis)/1000)).append('}');}return b.append("]}").toString();}

    private void layersEndpoint(HttpExchange e)throws IOException{
        if(!admin(e))return;String path=e.getRequestURI().getPath(),method=e.getRequestMethod();
        if(path.equals("/api/layers")&&method.equals("GET")){json(e,200,layersJson());return;}
        if(path.equals("/api/layers/groups")&&method.equals("GET")){json(e,200,layerGroupsJson());return;}
        if(path.equals("/api/layers/create")&&method.equals("POST")){try{Map<String,String>m=Json.flatObject(body(e));LayerCellKey cell=layers.cellFrom(m);LayerRecord l=layers.createLayer(cell,m.getOrDefault("workerId",""),"manual-create");audit.record(actor(e),"LAYER_CREATE",l.id,l.cell.display()+" worker="+l.workerId);json(e,201,layerJson(l));}catch(Exception ex){json(e,409,"{\"error\":"+Json.q(ex.getMessage())+"}");}return;}
        String prefix="/api/layers/";if(path.startsWith(prefix)&&method.equals("POST")){String rest=path.substring(prefix.length());String[]parts=rest.split("/");if(parts.length==2){String id=URLDecoder.decode(parts[0],StandardCharsets.UTF_8),action=parts[1];try{switch(action){case"freeze"->layers.freeze(id);case"unfreeze"->layers.unfreeze(id);case"drain"->layers.drain(id);case"undrain"->layers.undrain(id);case"retire"->layers.retire(id);default->{json(e,404,"{\"error\":\"unknown layer action\"}");return;}}audit.record(actor(e),"LAYER_"+action.toUpperCase(Locale.ROOT),id,"");json(e,200,"{\"ok\":true}");}catch(Exception ex){json(e,409,"{\"error\":"+Json.q(ex.getMessage())+"}");}return;}}
        json(e,404,"{\"error\":\"not found\"}");
    }
    private String layersJson(){var sum=layers.summary();StringBuilder b=new StringBuilder("{\"enabled\":").append(layers.enabled()).append(",\"cellChunks\":").append(layers.cellChunks()).append(",\"targetPlayers\":").append(layers.targetPlayers()).append(",\"splitPlayers\":").append(layers.splitPlayers()).append(",\"mergePlayers\":").append(layers.mergePlayers()).append(",\"activeLayers\":").append(sum.activeLayers()).append(",\"groups\":").append(sum.groups()).append(",\"assignments\":").append(sum.assignments()).append(",\"layers\":[");boolean first=true;for(LayerRecord l:layers.allLayers().stream().sorted(Comparator.comparing((LayerRecord x)->x.cell.groupId()).thenComparingInt(x->x.slot)).toList()){if(!first)b.append(',');first=false;b.append(layerJson(l));}return b.append("]}").toString();}
    private String layerJson(LayerRecord l){WorkerRecord w=workers.get(l.workerId);long now=System.currentTimeMillis();return "{\"id\":"+Json.q(l.id)+",\"groupId\":"+Json.q(l.cell.groupId())+",\"worldId\":"+Json.q(l.cell.worldId())+",\"dimensionId\":"+Json.q(l.cell.dimensionId())+",\"cellX\":"+l.cell.cellX()+",\"cellZ\":"+l.cell.cellZ()+",\"slot\":"+l.slot+",\"workerId\":"+Json.q(l.workerId)+",\"state\":"+Json.q(l.state.name())+",\"population\":"+layers.population(l)+",\"leaseId\":"+Json.q(l.leaseId)+",\"leaseEpoch\":"+l.leaseEpoch+",\"leaseRemainingSeconds\":"+Math.max(0,(l.leaseExpiresAtMillis-now)/1000)+",\"workerMspt\":"+fmt(w==null?0:w.mspt)+",\"note\":"+Json.q(l.note)+"}";}
    private String layerGroupsJson(){StringBuilder b=new StringBuilder("{\"cellChunks\":").append(layers.cellChunks()).append(",\"groups\":[");boolean first=true;for(LayerCellKey cell:layers.activeCells()){if(!first)b.append(',');first=false;List<LayerRecord>ls=layers.layersInCell(cell);int pop=ls.stream().mapToInt(layers::population).sum();long active=ls.stream().filter(l->l.state!=LayerState.RETIRED).count();String pressure=pop>=layers.splitPlayers()?"HOT":pop>=layers.targetPlayers()?"WARM":"NORMAL";int size=layers.cellChunks(),minX=cell.cellX()*size,minZ=cell.cellZ()*size,maxX=minX+size-1,maxZ=minZ+size-1;b.append("{\"groupId\":").append(Json.q(cell.groupId())).append(",\"worldId\":").append(Json.q(cell.worldId())).append(",\"dimensionId\":").append(Json.q(cell.dimensionId())).append(",\"cellX\":").append(cell.cellX()).append(",\"cellZ\":").append(cell.cellZ()).append(",\"chunkMinX\":").append(minX).append(",\"chunkMaxX\":").append(maxX).append(",\"chunkMinZ\":").append(minZ).append(",\"chunkMaxZ\":").append(maxZ).append(",\"population\":").append(pop).append(",\"layers\":").append(active).append(",\"pressure\":").append(Json.q(pressure)).append('}');}return b.append("]}").toString();}


    private void regionsEndpoint(HttpExchange e)throws IOException{
        if(!admin(e))return;String path=e.getRequestURI().getPath(),method=e.getRequestMethod();
        if(path.equals("/api/regions")&&method.equals("GET")){json(e,200,regionsJson());return;}
        if(path.equals("/api/regions/ensure")&&method.equals("POST")){try{Map<String,String>m=Json.flatObject(body(e));RegionKey key=new RegionKey(m.getOrDefault("worldId",cfg.get("layer.defaultWorld","foundations")),m.getOrDefault("dimensionId",cfg.get("layer.defaultDimension","minecraft:overworld")),Integer.parseInt(m.getOrDefault("regionX","0")),Integer.parseInt(m.getOrDefault("regionZ","0")));RegionRecord r=regions.ensure(key,m.getOrDefault("workerId",""),"dashboard ensure");audit.record(actor(e),"REGION_ENSURE",r.key.id(),"owner="+r.ownerWorkerId);json(e,201,regionJson(r));}catch(Exception ex){json(e,409,"{\"error\":"+Json.q(ex.getMessage())+"}");}return;}
        String prefix="/api/regions/";if(path.startsWith(prefix)&&method.equals("POST")){String rest=path.substring(prefix.length());int cut=rest.lastIndexOf('/');if(cut>0&&cut<rest.length()-1){String id=URLDecoder.decode(rest.substring(0,cut),StandardCharsets.UTF_8),action=rest.substring(cut+1);try{Map<String,String>m=Json.flatObject(body(e));RegionRecord r=regions.get(id);if(r==null)throw new IllegalArgumentException("Unknown region: "+id);switch(action){case"transfer"->{String destination=m.getOrDefault("workerId","");boolean moveSessions=Json.b(m,"moveSessions",false);r=regions.transfer(id,destination,"dashboard transfer");int movedPlayers=0;if(moveSessions){for(SessionRecord s:sessions.all()){if(!id.equals(s.regionId))continue;String anchor=(s.anchorWorkerId==null||s.anchorWorkerId.isBlank())?s.workerId:s.anchorWorkerId;if(destination.equals(anchor))continue;try{HandoffRecord h=handoffs.requestManualBackendTransfer(s.playerId,destination,"region authority transfer "+id);audit.record(actor(e),"PLAYER_TRANSFER_REQUEST",s.playerId,h.sourceWorkerId+" -> "+h.destinationWorkerId+" region="+id);movedPlayers++;}catch(Exception hx){log.warn("Region transfer "+id+" could not move player "+s.playerId+" to "+destination+": "+hx.getMessage());audit.record(actor(e),"PLAYER_TRANSFER_FAILED",s.playerId,"region="+id+" destination="+destination+" error="+hx.getMessage());}}log.info("Region transfer "+id+" -> "+destination+" requested live simulation migration for "+movedPlayers+" player(s)");}}case"cancel-save"->regions.cancelCheckpoint(id);case"freeze"->regions.freeze(id,true);case"unfreeze"->regions.freeze(id,false);case"expire"->regions.forceExpire(id);default->{json(e,404,"{\"error\":\"unknown region action\"}");return;}}audit.record(actor(e),"REGION_"+action.toUpperCase(Locale.ROOT),id,m.getOrDefault("workerId",""));json(e,200,regionJson(regions.get(id)));}catch(Exception ex){json(e,409,"{\"error\":"+Json.q(ex.getMessage())+"}");}return;}}
        json(e,404,"{\"error\":\"not found\"}");
    }
    private String regionsJson(){var sum=regions.summary();StringBuilder b=new StringBuilder("{\"enabled\":").append(regions.enabled()).append(",\"regionChunks\":").append(regions.regionChunks()).append(",\"leaseSeconds\":").append(regions.leaseSeconds()).append(",\"writeFencingEnforced\":").append(regions.writeFencingEnforced()).append(",\"total\":").append(sum.total()).append(",\"active\":").append(sum.active()).append(",\"suspect\":").append(sum.suspect()).append(",\"recovering\":").append(sum.recovering()).append(",\"unassigned\":").append(sum.unassigned()).append(",\"owners\":").append(sum.owners()).append(",\"regions\":[");boolean first=true;for(RegionRecord r:regions.all().stream().sorted(Comparator.comparing(x->x.key.id())).toList()){if(!first)b.append(',');first=false;b.append(regionJson(r));}return b.append("]}").toString();}
    private String regionJson(RegionRecord r){long now=System.currentTimeMillis();return "{\"id\":"+Json.q(r.key.id())+",\"worldId\":"+Json.q(r.key.worldId())+",\"dimensionId\":"+Json.q(r.key.dimensionId())+",\"regionX\":"+r.key.regionX()+",\"regionZ\":"+r.key.regionZ()+",\"ownerWorkerId\":"+Json.q(r.ownerWorkerId)+",\"previousOwnerWorkerId\":"+Json.q(r.previousOwnerWorkerId)+",\"leaseId\":"+Json.q(r.leaseId)+",\"epoch\":"+r.epoch+",\"state\":"+Json.q(r.state.name())+",\"leaseRemainingSeconds\":"+Math.max(0,(r.leaseExpiresAtMillis-now)/1000)+",\"note\":"+Json.q(r.note)+",\"savePending\":"+regions.checkpointPending(r.key.id())+",\"saveStatus\":"+Json.q(regions.checkpointStatus(r.key.id()))+"}";}

    private void playerStateEndpoint(HttpExchange e)throws IOException{if(!admin(e))return;if(!e.getRequestMethod().equals("GET")){json(e,405,"{\"error\":\"GET required\"}");return;}StringBuilder b=new StringBuilder("{\"records\":[");boolean first=true;for(ClusterPlayerState s:playerStates.all().stream().sorted(Comparator.comparing(x->x.name==null?x.uuid:x.name.toLowerCase(Locale.ROOT))).toList()){if(!first)b.append(',');first=false;b.append("{\"uuid\":").append(Json.q(s.uuid)).append(",\"name\":").append(Json.q(s.name)).append(",\"workerId\":").append(Json.q(s.workerId)).append(",\"dimensionId\":").append(Json.q(s.dimensionId)).append(",\"x\":").append(fmt(s.x)).append(",\"y\":").append(fmt(s.y)).append(",\"z\":").append(fmt(s.z)).append(",\"yaw\":").append(fmt(s.yaw)).append(",\"pitch\":").append(fmt(s.pitch)).append(",\"gameMode\":").append(Json.q(s.gameMode)).append(",\"op\":").append(s.op).append(",\"revision\":").append(s.revision).append(",\"ageSeconds\":").append(Math.max(0,(System.currentTimeMillis()-s.updatedAtMillis)/1000)).append('}');}json(e,200,b.append("]}").toString());}

    private void consoleExecute(HttpExchange e)throws IOException{
        if(!admin(e)||!post(e))return;
        Map<String,String>m=Json.flatObject(body(e));
        try{var r=console.execute(actor(e),m.getOrDefault("target","cluster"),m.getOrDefault("command",""));StringBuilder b=new StringBuilder("{\"ok\":true,\"target\":").append(Json.q(r.target())).append(",\"command\":").append(Json.q(r.command())).append(",\"affected\":").append(r.affected()).append(",\"lines\":[");boolean first=true;for(String line:r.lines()){if(!first)b.append(',');first=false;b.append(Json.q(line));}json(e,200,b.append("]}").toString());}
        catch(Exception ex){json(e,409,"{\"error\":"+Json.q(ex.getMessage())+"}");}
    }
    private void consoleOutput(HttpExchange e)throws IOException{
        if(!admin(e))return;String target=query(e).getOrDefault("target","cluster");int limit=intQuery(e,"limit",cfg.getInt("console.outputLines",800),1,5000);
        try{List<String>lines=console.output(target,limit);StringBuilder b=new StringBuilder("{\"target\":").append(Json.q(target)).append(",\"lines\":[");boolean first=true;for(String line:lines){if(!first)b.append(',');first=false;b.append(Json.q(line));}json(e,200,b.append("]}").toString());}
        catch(Exception ex){json(e,400,"{\"error\":"+Json.q(ex.getMessage())+"}");}
    }
    private void consoleHistory(HttpExchange e)throws IOException{
        if(!admin(e))return;int limit=intQuery(e,"limit",cfg.getInt("console.historyLines",100),1,1000);StringBuilder b=new StringBuilder("{\"entries\":[");boolean first=true;for(var h:console.history(limit)){if(!first)b.append(',');first=false;b.append("{\"time\":").append(Json.q(h.time())).append(",\"actor\":").append(Json.q(h.actor())).append(",\"target\":").append(Json.q(h.target())).append(",\"command\":").append(Json.q(h.command())).append(",\"result\":").append(Json.q(h.result())).append('}');}json(e,200,b.append("]}").toString());
    }


    private void gatewayTopology(HttpExchange e)throws IOException{
        if(!gatewayOrAdmin(e))return;String host=cfg.get("gateway.backendHost","127.0.0.1");StringBuilder b=new StringBuilder("{\"lifecycle\":"+Json.q(cfg.lifecycle.state())+",\"workers\":[");boolean first=true;
        for(WorkerRecord w:workers.all().stream().sorted(Comparator.comparing(x->x.id)).toList()){if(!first)b.append(',');first=false;b.append("{\"id\":").append(Json.q(w.id)).append(",\"host\":").append(Json.q(host)).append(",\"port\":").append(w.port).append(",\"routable\":").append(w.routableForNewSession()).append(",\"state\":").append(Json.q(w.state.name())).append('}');}
        json(e,200,b.append("]}").toString());
    }
    private void gatewayHeartbeat(HttpExchange e)throws IOException{
        if(!gatewayOrAdmin(e)||!post(e))return;Map<String,String>m=Json.flatObject(body(e));int players=0;try{players=Integer.parseInt(m.getOrDefault("players","0"));}catch(Exception ignored){}gateway.externalHeartbeat(players,m.getOrDefault("transport",m.getOrDefault("pluginVersion","velocity")));json(e,200,"{\"ok\":true,\"lifecycle\":"+Json.q(cfg.lifecycle.state())+"}");
    }
    private void gatewayFailover(HttpExchange e)throws IOException{
        if(!gatewayOrAdmin(e)||!post(e))return;
        if(cfg.lifecycle.shuttingDown()){json(e,503,"{\"error\":\"Cluster is shutting down\",\"code\":\"SHUTTING_DOWN\"}");return;}
        try{
            Map<String,String>m=Json.flatObject(body(e));
            String playerId=m.getOrDefault("playerId","").trim();
            String sessionId=m.getOrDefault("sessionId","").trim();
            String failed=m.getOrDefault("failedWorkerId","").trim();
            String reason=m.getOrDefault("reason","backend connection lost").trim();
            if(failed.isBlank())throw new IllegalArgumentException("failedWorkerId is required");
            if(!workers.noteBackendFailure(failed,reason)){
                json(e,409,"{\"error\":\"reported backend is still healthy; emergency failover refused\"}");return;
            }
            SessionManager.Placement p=sessions.emergencyFailover(sessionId,playerId,failed);
            SessionRecord sr=p.session();WorkerRecord dest=p.worker();
            ClusterPlayerState ps=playerStates.findByName(sr.playerId);
            if(ps!=null&&!ps.uuid.isBlank())playerProfiles.prepare(dest.id,ps.uuid,ps.name);
            log.warn("Velocity emergency redirect armed player="+sr.playerId+" failed="+failed+" -> "+dest.id+" reason="+reason);
            json(e,200,"{\"ok\":true,\"playerId\":"+Json.q(sr.playerId)+",\"sessionId\":"+Json.q(sr.sessionId)+",\"failedWorkerId\":"+Json.q(failed)+",\"workerId\":"+Json.q(dest.id)+",\"host\":"+Json.q(cfg.get("gateway.backendHost","127.0.0.1"))+",\"port\":"+dest.port+",\"layerId\":"+Json.q(sr.layerId)+"}");
        }catch(Exception ex){json(e,409,"{\"error\":"+Json.q(ex.getMessage())+"}");}
    }

    private void gatewayStatus(HttpExchange e)throws IOException{
        if(!admin(e))return;var g=gateway.snapshot();StringBuilder b=new StringBuilder("{\"enabled\":").append(g.enabled()).append(",\"running\":").append(g.running()).append(",\"bind\":").append(Json.q(g.bind())).append(",\"port\":").append(g.port()).append(",\"backendHost\":").append(Json.q(cfg.get("gateway.backendHost","127.0.0.1"))).append(",\"workerBindAddress\":").append(Json.q(cfg.get("worker.bindAddress","127.0.0.1"))).append(",\"activeConnections\":").append(g.activeConnections()).append(",\"totalConnections\":").append(g.totalConnections()).append(",\"routedConnections\":").append(g.routedConnections()).append(",\"statusRequests\":").append(g.statusRequests()).append(",\"failedConnections\":").append(g.failedConnections()).append(",\"startedAtMillis\":").append(g.startedAtMillis()).append(",\"lastError\":").append(Json.q(g.lastError())).append(",\"mode\":").append(Json.q(g.mode())).append(",\"transportVersion\":").append(Json.q(g.transportVersion())).append(",\"processPid\":").append(g.processPid()).append(",\"processOwned\":").append(g.processOwned()).append(",\"processAdopted\":").append(g.processAdopted()).append(",\"seamlessConfigured\":").append(cfg.getBoolean("gateway.seamless.enabled",false)).append(",\"seamlessAtLaunch\":").append(gateway.seamlessAtLaunch()).append(",\"sessionModel\":").append(Json.q(handoffs.mode())).append(",\"legacyVelocityBackendSwitch\":").append(handoffs.legacyVelocityBackendSwitch()).append(",\"routes\":[");boolean first=true;for(var r:g.routes()){if(!first)b.append(',');first=false;b.append("{\"id\":").append(r.id()).append(",\"playerId\":").append(Json.q(r.playerId())).append(",\"sessionId\":").append(Json.q(r.sessionId())).append(",\"workerId\":").append(Json.q(r.workerId())).append(",\"workerPort\":").append(r.workerPort()).append(",\"layerId\":").append(Json.q(r.layerId())).append(",\"regionId\":").append(Json.q(r.regionId())).append(",\"authorityWorkerId\":").append(Json.q(r.authorityWorkerId())).append(",\"authorityEpoch\":").append(r.authorityEpoch()).append(",\"remoteAddress\":").append(Json.q(r.remoteAddress())).append(",\"connectedAtMillis\":").append(r.connectedAtMillis()).append('}');}json(e,200,b.append("]}").toString());
    }


    private void handoffsEndpoint(HttpExchange e)throws IOException{
        if(!admin(e))return;String path=e.getRequestURI().getPath(),method=e.getRequestMethod();
        if(path.equals("/api/handoffs")&&method.equals("GET")){json(e,200,handoffsJson(handoffs.all()));return;}
        if(path.equals("/api/handoffs/request")&&method.equals("POST")){try{Map<String,String>m=Json.flatObject(body(e));HandoffRecord r=handoffs.requestManualBackendTransfer(m.getOrDefault("playerId",""),m.getOrDefault("destinationWorkerId",""),m.getOrDefault("reason","dashboard physical worker transfer"));audit.record(actor(e),"PLAYER_TRANSFER_REQUEST",r.playerId,r.sourceWorkerId+" -> "+r.destinationWorkerId+" physical=true");json(e,202,handoffJson(r));}catch(Exception ex){json(e,409,"{\"error\":"+Json.q(ex.getMessage())+"}");}return;}
        String prefix="/api/handoffs/";if(path.startsWith(prefix)&&path.endsWith("/abort")&&method.equals("POST")){String id=URLDecoder.decode(path.substring(prefix.length(),path.length()-"/abort".length()),StandardCharsets.UTF_8);try{HandoffRecord r=handoffs.abort(id,"administrator abort");audit.record(actor(e),"HANDOFF_ABORT",id,r.playerId);json(e,200,handoffJson(r));}catch(Exception ex){json(e,409,"{\"error\":"+Json.q(ex.getMessage())+"}");}return;}json(e,404,"{\"error\":\"not found\"}");
    }

    private void gatewayHandoffsEndpoint(HttpExchange e)throws IOException{
        if(!gatewayOrAdmin(e))return;String path=e.getRequestURI().getPath(),method=e.getRequestMethod();
        if(path.equals("/api/gateway/handoffs")&&method.equals("GET")){json(e,200,handoffsJson(handoffs.readyForProxy()));return;}
        if(path.equals("/api/gateway/handoffs/commit")&&method.equals("POST")){try{Map<String,String>m=Json.flatObject(body(e));HandoffRecord r=handoffs.commit(m.getOrDefault("handoffId",""),m.getOrDefault("workerId",""));json(e,200,handoffJson(r));}catch(Exception ex){json(e,409,"{\"error\":"+Json.q(ex.getMessage())+"}");}return;}
        if(path.equals("/api/gateway/handoffs/committing")&&method.equals("POST")){try{Map<String,String>m=Json.flatObject(body(e));json(e,200,handoffJson(handoffs.markCommitting(m.getOrDefault("handoffId",""))));}catch(Exception ex){json(e,409,"{\"error\":"+Json.q(ex.getMessage())+"}");}return;}
        if(path.equals("/api/gateway/handoffs/abort")&&method.equals("POST")){try{Map<String,String>m=Json.flatObject(body(e));json(e,200,handoffJson(handoffs.abort(m.getOrDefault("handoffId",""),m.getOrDefault("error","proxy abort"))));}catch(Exception ex){json(e,409,"{\"error\":"+Json.q(ex.getMessage())+"}");}return;}
        json(e,404,"{\"error\":\"not found\"}");
    }

    private void agentHandoffEndpoint(HttpExchange e)throws IOException{
        if(!agent(e)||!post(e))return;String path=e.getRequestURI().getPath();try{Map<String,String>m=Json.flatObject(body(e));String id=m.getOrDefault("handoffId","");
            if(path.endsWith("/snapshot")){HandoffRecord r=handoffs.sourceSnapshot(id,m.getOrDefault("workerId",""),m.getOrDefault("snapshot",""));json(e,200,handoffJson(r));return;}
            if(path.endsWith("/ready")){HandoffRecord r=handoffs.destinationReady(id,m.getOrDefault("workerId",""));json(e,200,handoffJson(r));return;}
            if(path.endsWith("/abort")){HandoffRecord r=handoffs.abort(id,m.getOrDefault("error","agent abort"));json(e,200,handoffJson(r));return;}
            json(e,404,"{\"error\":\"unknown handoff agent action\"}");
        }catch(Exception ex){json(e,409,"{\"error\":"+Json.q(ex.getMessage())+"}");}
    }

    private String handoffsJson(Collection<HandoffRecord> list){StringBuilder b=new StringBuilder("{\"handoffs\":[");boolean first=true;for(HandoffRecord r:list.stream().sorted(Comparator.comparingLong(x->x.createdAtMillis)).toList()){if(!first)b.append(',');first=false;b.append(handoffJson(r));}return b.append("]}").toString();}
    private String handoffJson(HandoffRecord r){return "{\"id\":"+Json.q(r.id)+",\"sessionId\":"+Json.q(r.sessionId)+",\"playerId\":"+Json.q(r.playerId)+",\"sourceWorkerId\":"+Json.q(r.sourceWorkerId)+",\"destinationWorkerId\":"+Json.q(r.destinationWorkerId)+",\"targetLayerId\":"+Json.q(r.targetLayerId)+",\"state\":"+Json.q(r.state.name())+",\"mode\":"+Json.q(handoffs.mode())+",\"proxySwitchRequired\":"+r.proxySwitchRequired+",\"clientAnchorPreserved\":"+(handoffs.persistentSessionMode()&&!r.proxySwitchRequired)+",\"reason\":"+Json.q(r.reason)+",\"error\":"+Json.q(r.error)+",\"createdAtMillis\":"+r.createdAtMillis+",\"updatedAtMillis\":"+r.updatedAtMillis+"}";}

    private void logs(HttpExchange e)throws IOException{if(!admin(e))return;int limit=intQuery(e,"limit",300,1,1000);StringBuilder b=new StringBuilder("{\"lines\":[");boolean first=true;for(String s:log.tail(limit)){if(!first)b.append(',');first=false;b.append(Json.q(s));}json(e,200,b.append("]}").toString());}
    private void restartWorkers(HttpExchange e)throws IOException{if(!admin(e)||!post(e))return;int count=evacuations.restartAllManagedRolling();audit.record(actor(e),"CLUSTER_RESTART_WORKERS","cluster","graceful workers="+count);json(e,202,"{\"ok\":true,\"state\":\"GRACEFUL_ROLLING_RESTART\",\"workers\":"+count+"}");}
    private void shutdown(HttpExchange e)throws IOException{if(!admin(e)||!post(e))return;if(shutdownRequested){json(e,202,"{\"ok\":true,\"state\":\"SHUTTING_DOWN\"}");return;}shutdownRequested=true;cfg.lifecycle.beginShutdown("administrator request");audit.record(actor(e),"CLUSTER_SHUTDOWN","cluster","");json(e,202,"{\"ok\":true,\"state\":\"SHUTTING_DOWN\"}");Thread.ofVirtual().name("foundations-admin-shutdown").start(()->{try{log.info("Administrator requested full cluster shutdown");}finally{System.exit(0);}});}

    private void dataPlaneStatus(HttpExchange e)throws IOException{
        if(!admin(e))return;var d=dataPlane.summary();var ps=dataPlane.persistenceSummary();var bes=dataPlane.blockEntityPersistenceSummary();StringBuilder b=new StringBuilder("{\"enabled\":"+d.enabled()+",\"published\":"+d.published()+",\"delivered\":"+d.delivered()+",\"acked\":"+d.acked()+",\"rejected\":"+d.rejected()+",\"dropped\":"+d.dropped()+",\"pending\":"+d.pending()+",\"inflight\":"+d.inflight()+",\"bytes\":"+d.bytes()+",\"chunkRequests\":"+d.chunkRequests()+",\"chunkSnapshots\":"+d.chunkSnapshots()+",\"snapshotBytes\":"+d.snapshotBytes()+",\"persistenceEnabled\":"+(ps.enabled()||bes.enabled())+",\"persistenceRevision\":"+Math.max(ps.revision(),bes.revision())+",\"persistenceBlocks\":"+ps.blocks()+",\"persistenceWalEntries\":"+ps.walEntries()+",\"persistenceBlockEntities\":"+bes.blockEntities()+",\"persistenceBlockEntityRevision\":"+bes.revision()+",\"persistenceBlockEntityWalEntries\":"+bes.walEntries()+",\"persistenceBlockEntityPayloadBytes\":"+bes.payloadBytes()+",\"recent\":[");boolean first=true;for(DataPlaneFrame f:dataPlane.recent(50)){if(!first)b.append(',');first=false;b.append("{\"id\":").append(Json.q(f.id)).append(",\"kind\":").append(Json.q(f.kind)).append(",\"sourceWorkerId\":").append(Json.q(f.sourceWorkerId)).append(",\"targetWorkerId\":").append(Json.q(f.targetWorkerId)).append(",\"sessionId\":").append(Json.q(f.sessionId)).append(",\"playerId\":").append(Json.q(f.playerId)).append(",\"regionId\":").append(Json.q(f.regionId)).append(",\"authorityEpoch\":").append(f.authorityEpoch).append(",\"deliveries\":").append(f.deliveries).append(",\"createdAtMillis\":").append(f.createdAtMillis).append('}');}b.append("]}");json(e,200,b.toString());
    }

    private void agentDataPlanePublish(HttpExchange e)throws IOException{if(!agent(e)||!post(e))return;try{Map<String,String>m=Json.flatObject(body(e));DataPlaneFrame f=dataPlane.publish(m);json(e,202,"{\"ok\":true,\"frameId\":"+Json.q(f.id)+",\"targetWorkerId\":"+Json.q(f.targetWorkerId)+"}");}catch(Exception ex){json(e,409,"{\"error\":"+Json.q(ex.getMessage())+"}");}}
    private void agentDataPlanePoll(HttpExchange e)throws IOException{if(!agent(e)||!post(e))return;try{Map<String,String>m=Json.flatObject(body(e));String workerId=m.getOrDefault("workerId","").trim();int max=Json.i(m,"maxFrames",0);String bundle=dataPlane.pollBundle(workerId,max);json(e,200,"{\"ok\":true,\"bundle\":"+Json.q(bundle)+"}");}catch(Exception ex){json(e,400,"{\"error\":"+Json.q(ex.getMessage())+"}");}}
    private void agentDataPlaneAck(HttpExchange e)throws IOException{if(!agent(e)||!post(e))return;try{Map<String,String>m=Json.flatObject(body(e));dataPlane.ack(m.getOrDefault("workerId",""),m.getOrDefault("frameId",""),Json.b(m,"ok",true),m.getOrDefault("error",""));json(e,200,"{\"ok\":true}");}catch(Exception ex){json(e,400,"{\"error\":"+Json.q(ex.getMessage())+"}");}}
    private void agentPersistenceReplay(HttpExchange e)throws IOException {
        if(!agent(e)||!post(e))return;
        try {
            Map<String,String> m=Json.flatObject(body(e));String worker=m.getOrDefault("workerId","").trim();
            String requested=m.getOrDefault("regions","");String bundle,entities;
            if(requested.isBlank()) { // R9 compatibility; R10 never uses an unscoped replay.
                bundle=dataPlane.persistenceBundleForWorker(worker);entities=dataPlane.blockEntityPersistenceBundleForWorker(worker);
            } else {
                synchronized(regions) {
                    var selected=regions.replayRegions(worker,m.getOrDefault("persistenceInstance",""),requested);
                    bundle=dataPlane.persistenceBundleForRegions(selected);entities=dataPlane.blockEntityPersistenceBundleForRegions(selected);
                }
            }
            var ps=dataPlane.persistenceSummary();var bes=dataPlane.blockEntityPersistenceSummary();
            json(e,200,"{\"ok\":true,\"protocol\":\"block+blockentity-journal-v3\",\"revision\":"+Math.max(ps.revision(),bes.revision())+",\"blocks\":"+ps.blocks()+",\"blockEntities\":"+bes.blockEntities()+",\"regions\":"+Json.q(requested)+",\"bundle\":"+Json.q(bundle)+",\"blockEntityBundle\":"+Json.q(entities)+"}");
        } catch(Exception ex){json(e,409,"{\"error\":"+Json.q(ex.getMessage())+"}");}
    }

    private void agentRegionCheckpoint(HttpExchange e)throws IOException {
        if(!agent(e)||!post(e))return;
        try{var m=Json.flatObject(body(e));regions.acknowledgeCheckpoint(m.getOrDefault("workerId",""),m.getOrDefault("persistenceInstance",""),m.getOrDefault("checkpointId",""),Long.parseLong(m.getOrDefault("epoch","0")),m.getOrDefault("error",""));json(e,200,"{\"ok\":true}");}
        catch(Exception ex){json(e,409,"{\"error\":"+Json.q(ex.getMessage())+"}");}
    }
    private void agentBlockBatch(HttpExchange e)throws IOException {
        if(!agent(e)||!post(e))return;
        try{var m=Json.flatObject(body(e));String worker=m.getOrDefault("workerId","");String[] rows=m.getOrDefault("mutations","").split(";",-1);
            if(rows.length>256)throw new IllegalArgumentException("Batch exceeds 256 blocks");
            int count=0;for(String row:rows){if(row.isBlank())continue;String[] p=row.split("\\|",-1);if(p.length!=7)throw new IllegalArgumentException("Invalid block mutation");
                String dim=new String(Base64.getUrlDecoder().decode(p[0]),StandardCharsets.UTF_8),region=new String(Base64.getUrlDecoder().decode(p[1]),StandardCharsets.UTF_8);
                dataPlane.publish(Map.of("kind","PERSIST_BLOCK","sourceWorkerId",worker,"worldId","foundations","dimensionId",dim,"regionId",region,"authorityEpoch",p[2],"payload",p[3]+"|"+p[4]+"|"+p[5]+"|"+p[6]));count++;}
            json(e,200,"{\"ok\":true,\"committed\":"+count+"}");
        }catch(Exception ex){json(e,409,"{\"error\":"+Json.q(ex.getMessage())+"}");}
    }

    private void agentRegister(HttpExchange e)throws IOException{if(!agent(e)||!post(e))return;try{Map<String,String>m=Json.flatObject(body(e));WorkerRecord w=workers.register(m);environment.report(w,m);w.persistenceInstance=m.getOrDefault("persistenceInstance","");w.pendingWrites=Json.i(m,"pendingWrites",0);w.failedWrites=Json.i(m,"failedWrites",0);w.lastDurableWrite=Long.parseLong(m.getOrDefault("lastDurableWrite","0"));regions.reportHydration(w,m.getOrDefault("hydratedRegions",""));playerStates.ingestBlob(w.id,m.get("playerStates"),(uuid,name)->stateUpdateAllowed(w.id,uuid,name));json(e,200,controlJson(w));}catch(Exception ex){json(e,400,"{\"error\":"+Json.q(ex.getMessage())+"}");}}
    private void agentHeartbeat(HttpExchange e)throws IOException{if(!agent(e)||!post(e))return;try{Map<String,String>m=Json.flatObject(body(e));WorkerRecord w=workers.heartbeat(m);environment.report(w,m);w.persistenceInstance=m.getOrDefault("persistenceInstance","");w.pendingWrites=Json.i(m,"pendingWrites",0);w.failedWrites=Json.i(m,"failedWrites",0);w.lastDurableWrite=Long.parseLong(m.getOrDefault("lastDurableWrite","0"));regions.reportHydration(w,m.getOrDefault("hydratedRegions",""));playerStates.ingestBlob(w.id,m.get("playerStates"),(uuid,name)->stateUpdateAllowed(w.id,uuid,name));json(e,200,controlJson(w));}catch(Exception ex){json(e,400,"{\"error\":"+Json.q(ex.getMessage())+"}");}}
    private void agentOffline(HttpExchange e)throws IOException{if(!agent(e))return;Map<String,String>m=Json.flatObject(body(e));workers.offline(m.getOrDefault("workerId",""));json(e,200,"{\"ok\":true}");}
    private void agentPlayerState(HttpExchange e)throws IOException{
        if(!agent(e)||!post(e))return;
        try{
            Map<String,String>m=Json.flatObject(body(e));String workerId=m.getOrDefault("workerId","");String uuid=m.getOrDefault("uuid","");String name=m.getOrDefault("name","");
            if(!stateUpdateAllowed(workerId,uuid,name)){json(e,202,"{\"ok\":true,\"ignored\":true,\"reason\":\"non-current session worker\"}");return;}
            ClusterPlayerState ps=playerStates.update(m);playerProfiles.capture(workerId,uuid,name);
            json(e,200,"{\"ok\":true,\"revision\":"+ps.revision+"}");
        }catch(Exception ex){json(e,400,"{\"error\":"+Json.q(ex.getMessage())+"}");}
    }
    private boolean stateUpdateAllowed(String workerId,String uuid,String name){SessionRecord s=sessions.findByPlayer(name);if(s==null)return true;return workerId==null||workerId.isBlank()||s.workerId.equals(workerId);}
    private void agentPlayerRestore(HttpExchange e)throws IOException{if(!agent(e)||!post(e))return;Map<String,String>m=Json.flatObject(body(e));json(e,200,playerStates.restoreJson(m.getOrDefault("uuid",""),m.getOrDefault("name","")));}
    private String controlJson(WorkerRecord w){String ids=String.join(",",layers.layerIdsForWorker(w.id));String authority=regions.authorityTokenForWorker(w.id);String routes=dataPlane.routeTokenForAnchor(w.id);String hc=handoffs.controlJsonForWorker(w.id);String hcb=handoffs.controlTokenForWorker(w.id);var ps=dataPlane.persistenceSummary();var bes=dataPlane.blockEntityPersistenceSummary();return "{\"ok\":true,\"draining\":"+w.draining+",\"maintenance\":"+w.maintenance+",\"state\":"+Json.q(w.state.name())+",\"layerProtocol\":\"population-v1\",\"layerIds\":"+Json.q(ids)+",\"authorityProtocol\":\"region-v1\",\"authorityRegions\":"+Json.q(authority)+",\"writeFencingEnforced\":"+regions.writeFencingEnforced()+",\"handoffProtocol\":\"session-v2-batch\",\"handoffCommand\":"+hc+",\"handoffCommands\":"+Json.q(hcb)+",\"dataPlaneProtocol\":\"relay-v2-chunk-read\",\"simulationRoutes\":"+Json.q(routes)+",\"persistenceProtocol\":\"block+blockentity-journal-v3\",\"persistenceRevision\":"+Math.max(ps.revision(),bes.revision())+",\"persistenceBlocks\":"+ps.blocks()+",\"persistenceBlockEntities\":"+bes.blockEntities()+",\"regionCheckpoints\":"+Json.q(regions.checkpointCommands(w.id))+environment.controlJson()+"}";}

    private String stopStatusJson(String workerId, WorkerEvacuationManager.StopStatus s){
        long remainingMillis=Math.max(0,s.deadlineMillis()-System.currentTimeMillis());
        return "{\"ok\":true,\"workerId\":"+Json.q(workerId)+",\"active\":"+s.active()+",\"startedAtMillis\":"+s.startedAtMillis()+",\"deadlineMillis\":"+s.deadlineMillis()+",\"remainingMillis\":"+remainingMillis+",\"initialPlayers\":"+s.initialPlayers()+",\"remainingPlayers\":"+s.remainingPlayers()+",\"migratedPlayers\":"+s.migratedPlayers()+",\"remainingRegions\":"+s.remainingRegions()+",\"message\":"+Json.q(s.message())+"}";
    }

    private String workersJson(){StringBuilder b=new StringBuilder("{\"workers\":[");boolean first=true;for(WorkerRecord w:workers.all().stream().sorted(Comparator.comparing(a->a.id)).toList()){if(!first)b.append(',');first=false;int sessionCount=sessions.countForWorker(w.id),layerCount=layers.activeLayerCountForWorker(w.id),layerPopulation=layers.populationForWorker(w.id),authorityRegionCount=regions.activeRegionCountForWorker(w.id);boolean alive=workers.processAlive(w.id);long effectivePid=workers.effectivePid(w.id);b.append("{\"id\":").append(Json.q(w.id)).append(",\"state\":").append(Json.q(w.state.name())).append(",\"pendingWrites\":").append(w.pendingWrites).append(",\"failedWrites\":").append(w.failedWrites).append(",\"lastDurableWrite\":").append(w.lastDurableWrite).append(",\"managed\":").append(w.managed).append(",\"managerControlled\":").append(workers.managerControlled(w.id)).append(",\"controlDetached\":").append(w.controlDetached).append(",\"ready\":").append(w.ready).append(",\"draining\":").append(w.draining).append(",\"maintenance\":").append(w.maintenance).append(",\"pinned\":").append(w.pinned).append(",\"migrationEligible\":").append(workers.migrationEligible(w)).append(",\"warm\":").append(w.warm).append(",\"pid\":").append(effectivePid).append(",\"processAlive\":").append(alive).append(",\"port\":").append(w.port).append(",\"players\":").append(w.players).append(",\"maxPlayers\":").append(w.maxPlayers).append(",\"sessionCount\":").append(sessionCount).append(",\"layerCount\":").append(layerCount).append(",\"layerPopulation\":").append(layerPopulation).append(",\"authorityRegionCount\":").append(authorityRegionCount).append(",\"mspt\":").append(fmt(w.mspt)).append(",\"msptP95\":").append(fmt(w.msptP95)).append(",\"msptP99\":").append(fmt(w.msptP99)).append(",\"heapUsed\":").append(w.heapUsed).append(",\"heapMax\":").append(w.heapMax).append(",\"loadedChunks\":").append(w.loadedChunks).append(",\"dimensions\":").append(w.dimensions).append(",\"threadCount\":").append(w.threadCount).append(",\"uptimeMillis\":").append(w.uptimeMillis).append(",\"javaVersion\":").append(Json.q(w.javaVersion)).append(",\"minecraftVersion\":").append(Json.q(w.minecraftVersion)).append(",\"nodeVersion\":").append(Json.q(w.nodeVersion)).append(",\"worldSeed\":").append(w.worldSeed).append(",\"worldSeedKnown\":").append(w.worldSeedKnown).append(",\"worldSeedMatch\":").append(w.worldSeedMatch).append(",\"worldIdentity\":").append(Json.q(w.worldIdentity)).append(",\"worldIdentitySchema\":").append(w.worldIdentitySchema).append(",\"worldIdentityMatch\":").append(w.worldIdentityMatch).append(",\"cpuPercent\":").append(fmt(w.cpuPercent)).append(",\"heartbeatAgeSeconds\":").append(w.heartbeatAgeSeconds()).append(",\"gracefulStopPending\":").append(w.gracefulStopPending).append(",\"gracefulStopStartedAtMillis\":").append(w.gracefulStopStartedAtMillis).append(",\"gracefulStopDeadlineMillis\":").append(w.gracefulStopDeadlineMillis).append(",\"gracefulStopInitialPlayers\":").append(w.gracefulStopInitialPlayers).append(",\"gracefulStopMigratedPlayers\":").append(w.gracefulStopMigratedPlayers).append(",\"gracefulStopMessage\":").append(Json.q(w.gracefulStopMessage)).append('}');}return b.append("]}").toString();}


    private boolean admin(HttpExchange e)throws IOException{if(adminAuthenticated(e)){
        if(!Set.of("GET","HEAD").contains(e.getRequestMethod()) && cfg.lifecycle.shuttingDown() && !e.getRequestURI().getPath().equals("/api/system/shutdown")){
            json(e,503,"{\"error\":\"Cluster is shutting down\",\"code\":\"SHUTTING_DOWN\"}");return false;
        } return true;}json(e,401,"{\"error\":\"authentication required\",\"code\":\"AUTH_REQUIRED\"}");return false;}
    private boolean adminAuthenticated(HttpExchange e){String t=e.getRequestHeaders().getFirst("X-Foundations-Admin-Token");return tokens.adminToken.equals(t)||auth.authenticated(e);}
    private String actor(HttpExchange e){String u=auth.authenticatedUser(e);return u!=null?u:"token-admin";}
    private boolean gatewayOrAdmin(HttpExchange e)throws IOException{String g=e.getRequestHeaders().getFirst("X-Foundations-Gateway-Token");if(adminAuthenticated(e)||tokens.gatewayToken.equals(g))return true;json(e,401,"{\"error\":\"gateway or admin authentication required\"}");return false;}
    private boolean agent(HttpExchange e)throws IOException{String t=e.getRequestHeaders().getFirst("X-Foundations-Agent-Token");if(tokens.agentToken.equals(t))return true;json(e,401,"{\"error\":\"agent token required\"}");return false;}

    private void context(String path, HttpHandler handler){
        server.createContext(path, exchange -> {
            try {
                if(!Set.of("GET","HEAD").contains(exchange.getRequestMethod())){
                    String origin=exchange.getRequestHeaders().getFirst("Origin");
                    String host=exchange.getRequestHeaders().getFirst("Host");
                    String site=exchange.getRequestHeaders().getFirst("Sec-Fetch-Site");
                    boolean bad="cross-site".equals(site);
                    if(origin!=null){try{var uri=URI.create(origin);bad |= host==null || !host.equalsIgnoreCase(uri.getRawAuthority()) || !Set.of("http","https").contains(uri.getScheme());}catch(Exception ex){bad=true;}}
                    if(bad){json(exchange,403,"{\"error\":\"Cross-origin mutation refused\"}");return;}
                }
                handler.handle(exchange);
            }catch(Exception ex){json(exchange,400,"{\"error\":\"Invalid request\"}");}
        });
    }

    private void acknowledgeAlert(HttpExchange e)throws IOException{
        if(!admin(e)||!post(e))return;
        try{var m=Json.flatObject(body(e));String id=m.getOrDefault("id","");alerts.acknowledge(id,actor(e));audit.record(actor(e),"ALERT_ACKNOWLEDGE",id,"");json(e,200,"{\"ok\":true}");}
        catch(Exception ex){json(e,409,"{\"error\":"+Json.q(ex.getMessage())+"}");}
    }
    private void supportEndpoint(HttpExchange e)throws IOException{
        if(!admin(e))return;
        String path=e.getRequestURI().getPath();
        if(path.equals("/api/support/download")&&e.getRequestMethod().equals("GET")){
            try{
                Path file=support.download(query(e).getOrDefault("name",""));
                securityHeaders(e);e.getResponseHeaders().set("Content-Type","application/zip");e.getResponseHeaders().set("Cache-Control","no-store");
                e.getResponseHeaders().set("Content-Disposition","attachment; filename="+file.getFileName());
                try(var in=Files.newInputStream(file)){e.sendResponseHeaders(200,Files.size(file));try(var out=e.getResponseBody()){in.transferTo(out);}}
            }catch(FileNotFoundException ex){json(e,404,"{\"error\":\"Support ZIP is no longer available\"}");}
            return;
        }
        if(!path.equals("/api/support")){json(e,404,"{}");return;}
        if(e.getRequestMethod().equals("GET")){json(e,200,support.statusJson());return;}
        if(!post(e))return;
        try{
            byte[] input=e.getRequestBody().readNBytes(1024*1024+1);if(input.length>1024*1024)throw new IllegalArgumentException("Support request too large");
            var m=Json.flatObject(new String(input,StandardCharsets.UTF_8));String reason=m.getOrDefault("reason","manual");
            if(!Set.of("manual","test-failure").contains(reason))throw new IllegalArgumentException("Unknown capture reason");
            boolean accepted=support.request(reason,m.getOrDefault("report",""));
            if(accepted)audit.record(actor(e),"SUPPORT_CAPTURE","cluster",reason);
            json(e,accepted?202:200,"{\"accepted\":"+accepted+"}");
        }catch(IllegalArgumentException|IllegalStateException ex){json(e,409,"{\"error\":"+Json.q(ex.getMessage())+"}");}
    }
    private void diagnostics(HttpExchange e)throws IOException{
        if(!admin(e))return;
        if(!e.getRequestMethod().equals("GET")){json(e,405,"{}");return;}
        byte[] data=DiagnosticBundle.create(cfg,workers,transferTrace);
        audit.record(actor(e),"DIAGNOSTICS_EXPORT","cluster","Redacted operational summary");
        securityHeaders(e);e.getResponseHeaders().set("Content-Type","application/zip");
        e.getResponseHeaders().set("Cache-Control","no-store");
        e.getResponseHeaders().set("Content-Disposition","attachment; filename=Foundations-diagnostics-"+ClusterVersion.VERSION+".zip");
        e.sendResponseHeaders(200,data.length);try(var out=e.getResponseBody()){out.write(data);}
    }
    private void gatewayTrace(HttpExchange e)throws IOException{
        if(!gatewayOrAdmin(e)||!post(e))return;
        try {var m=Json.flatObject(body(e)); String id=m.getOrDefault("handoffId","");
            if(handoffs.get(id)==null)throw new IllegalArgumentException("Unknown handoff");
            transferTrace.add(id,m.getOrDefault("stage",""),Json.l(m,"elapsedMillis",0),m.getOrDefault("detail",""));
            json(e,200,"{\"ok\":true}");
        }catch(IllegalArgumentException ex){json(e,400,"{\"error\":"+Json.q(ex.getMessage())+"}");}
    }

    private void staticFile(HttpExchange e)throws IOException{String path=e.getRequestURI().getPath();if(path.equals("/"))path="/index.html";if(path.contains("..")){text(e,400,"bad path","text/plain");return;}Path base=cfg.home().resolve("web").normalize(),file=base.resolve(path.substring(1)).normalize();if(!file.startsWith(base)||!Files.isRegularFile(file)){text(e,404,"not found","text/plain");return;}String ct=path.endsWith(".html")?"text/html; charset=utf-8":path.endsWith(".js")?"application/javascript; charset=utf-8":path.endsWith(".css")?"text/css; charset=utf-8":path.endsWith(".svg")?"image/svg+xml":path.endsWith(".json")?"application/json; charset=utf-8":"application/octet-stream";byte[]data=Files.readAllBytes(file);securityHeaders(e);e.getResponseHeaders().set("Content-Type",ct);e.getResponseHeaders().set("Cache-Control","no-store");e.sendResponseHeaders(200,data.length);try(OutputStream out=e.getResponseBody()){out.write(data);}}
    private static boolean post(HttpExchange e)throws IOException{if(e.getRequestMethod().equals("POST"))return true;json(e,405,"{\"error\":\"POST required\"}");return false;}
    private static String body(HttpExchange e)throws IOException{byte[] data=e.getRequestBody().readNBytes(32*1024*1024+1);if(data.length>32*1024*1024)throw new IOException("Request too large");return new String(data,StandardCharsets.UTF_8);}
    private static Map<String,String> query(HttpExchange e){Map<String,String>m=new HashMap<>();String q=e.getRequestURI().getRawQuery();if(q==null)return m;for(String p:q.split("&")){String[]kv=p.split("=",2);m.put(URLDecoder.decode(kv[0],StandardCharsets.UTF_8),kv.length>1?URLDecoder.decode(kv[1],StandardCharsets.UTF_8):"");}return m;}
    private static int intQuery(HttpExchange e,String key,int d,int min,int max){try{return Math.max(min,Math.min(max,Integer.parseInt(query(e).getOrDefault(key,Integer.toString(d)))));}catch(Exception x){return d;}}
    private static String stringArray(Collection<String> s){StringBuilder b=new StringBuilder("[");boolean first=true;for(String x:s){if(!first)b.append(',');first=false;b.append(Json.q(x));}return b.append(']').toString();}
    private static void json(HttpExchange e,int status,String s)throws IOException{text(e,status,s,"application/json; charset=utf-8");}
    private static void text(HttpExchange e,int status,String s,String ct)throws IOException{byte[]d=s.getBytes(StandardCharsets.UTF_8);securityHeaders(e);e.getResponseHeaders().set("Content-Type",ct);e.getResponseHeaders().set("Cache-Control","no-store");e.sendResponseHeaders(status,d.length);try(OutputStream out=e.getResponseBody()){out.write(d);}}
    private static void securityHeaders(HttpExchange e){e.getResponseHeaders().set("X-Content-Type-Options","nosniff");e.getResponseHeaders().set("X-Frame-Options","DENY");e.getResponseHeaders().set("Referrer-Policy","no-referrer");e.getResponseHeaders().set("Content-Security-Policy","default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");}
    private static String fmt(double d){return String.format(Locale.ROOT,"%.2f",d);}
    public void close(){support.close();environment.close();server.stop(0);}
}
