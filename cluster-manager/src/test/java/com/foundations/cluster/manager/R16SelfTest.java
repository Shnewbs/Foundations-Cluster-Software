package com.foundations.cluster.manager;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;
import java.io.*;

public final class R16SelfTest {
    private static int checks;
    static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);checks++;}
    static void rejects(Throwing code,String message)throws Exception{try{code.run();}catch(IllegalArgumentException|IllegalStateException expected){checks++;return;}throw new AssertionError(message);}
    interface Throwing {void run()throws Exception;}
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("foundations-r16-test-");Files.createDirectories(root.resolve("config"));
        Files.writeString(root.resolve("config/cluster.properties"),"cluster.name=Original\nworld.seed=1234\nstartup.autoStartWorkers=false\ntemplate.autoBootstrap=false\nnodeAgent.required=false\ngateway.enabled=false\nworld.identity.enforce=false\n");
        ManagerConfig cfg=ManagerConfig.load(root);
        LinkedHashMap<String,String> invalid=new LinkedHashMap<>();invalid.put("cluster.name","Must not leak");invalid.put("web.port","999999");
        rejects(()->cfg.updateEditable(invalid),"Invalid update accepted");
        check(cfg.get("cluster.name","").equals("Original"),"Rejected update changed memory");
        check(!Files.readString(root.resolve("config/cluster.properties")).contains("Must not leak"),"Rejected update changed disk");
        cfg.updateEditable(Map.of("cluster.name","New name","placement.maxHeapPercent","82","gateway.seamless.enabled","false"));
        check(ManagerConfig.load(root).get("cluster.name","").equals("New name"),"Settings failed to round-trip");
        rejects(()->cfg.updateEditable(Map.of("placement.maxHeapPercent","NaN")),"NaN accepted");
        var lifecycle=new ClusterLifecycle();
        try(var pool=Executors.newFixedThreadPool(8)){
            var jobs=new ArrayList<Future<Boolean>>();for(int i=0;i<32;i++)jobs.add(pool.submit(()->lifecycle.beginShutdown("test")));
            int winners=0;for(var f:jobs)if(f.get())winners++;check(winners==1,"Shutdown is not single-flight");
        }
        rejects(lifecycle::requireRunning,"Shutdown gate did not close");
        ClusterLog log=new ClusterLog(root.resolve("logs/manager.log"),100);
        var tokens=new TokenStore(root.resolve("runtime"));var templates=new TemplateManager(cfg,log);var agent=new NodeAgentManager(cfg,templates,log);
        try(var workers=new WorkerManager(cfg,tokens,log,templates,agent)){
            var w=workers.register(Map.of("workerId","worker-001","port","25566","players","3","maxPlayers","10","heapUsed","40","heapMax","100","mspt","20","nodeVersion","0.0.6a.R7"));
            w.environmentReady=true;w.environmentLeaseUntilMillis=System.currentTimeMillis()+30000;
            check(workers.migrationEligible(w),"Healthy target rejected");
            w.heapUsed=83;check(!workers.migrationEligible(w),"High heap accepted");w.heapUsed=40;
            w.players=10;check(!workers.migrationEligible(w),"Full target accepted");w.players=3;
            w.lastHeartbeatMillis=System.currentTimeMillis()-30000;check(!workers.migrationEligible(w),"Stale target accepted");
            check(!MetricsRecorder.hasFreshTelemetry(w,10),"Stale telemetry included");w.lastHeartbeatMillis=System.currentTimeMillis();
            w.draining=true;check(!workers.migrationEligible(w),"Draining target accepted");w.draining=false;
            w.state=WorkerState.OFFLINE;check(!MetricsRecorder.hasFreshTelemetry(w,10),"Stopped telemetry included");w.state=WorkerState.ACTIVE;
            workers.pin(w.id,true);check(w.pinned,"Pin was not applied");check(Files.readString(root.resolve("runtime/worker-pins.properties")).contains("worker-001=true"),"Pin not persisted");
            workers.pin(w.id,false);check(!w.pinned,"Unpin failed");
            try(var metrics=new MetricsRecorder(cfg,workers)){
                check(metrics.tail(10).getFirst().players()==3,"Player aggregate wrong");check(metrics.workerJson(w.id).contains("\"p95\""),"Worker samples missing");
            }
            var trace=new TransferTrace();for(int i=0;i<1100;i++)trace.add("handoff-00000001","SWITCH_REQUEST",i,"destination=worker-002");
            check(trace.json().split("handoffId").length-1==1000,"Trace retention unbounded");
            rejects(()->trace.add("handoff-1","RAW_PACKET",0,""),"Trace accepts arbitrary raw stage");
            check(!DiagnosticBundle.redact("token=abc password:xyz secret=demo").contains("abc"),"Diagnostic redaction failed");
            byte[] zip=DiagnosticBundle.create(cfg,workers,trace);Set<String> names=new HashSet<>();
            try(var zin=new ZipInputStream(new ByteArrayInputStream(zip))){ZipEntry entry;while((entry=zin.getNextEntry())!=null)names.add(entry.getName());}
            check(names.equals(Set.of("README.txt","host.json","transfer-trace.json","workers.csv","runtime.json")),"Diagnostic allowlist changed");
            cfg.lifecycle.beginShutdown("test");WorkerState prior=w.state;
            check(!workers.noteBackendFailure(w.id,"intentional shutdown"),"Shutdown triggered failover");check(w.state==prior,"Shutdown quarantined worker");
            rejects(()->workers.spawn(false),"Spawn accepted during shutdown");rejects(()->workers.restart(w.id),"Restart accepted during shutdown");
        }
        System.out.println("R16 self-tests: PASS ("+checks+" assertions)");
        try(var walk=Files.walk(root)){for(Path path:walk.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}
    }
}
