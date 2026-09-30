package com.foundations.cluster.manager;

import java.util.*;
import java.nio.file.*;
import java.io.*;

final class AlertManager {
    private final ManagerConfig cfg; private final WorkerManager workers; private final StartupController startup; private final GatewayServer gateway;
    AlertManager(ManagerConfig cfg,WorkerManager workers,StartupController startup,GatewayServer gateway){this.cfg=cfg;this.workers=workers;this.startup=startup;this.gateway=gateway;}
    private final Map<String,Incident> incidents=new HashMap<>();
    private final Properties savedAcknowledgements=new Properties();
    private boolean acknowledgementsLoaded;
    record Incident(long firstSeen,String actor) {}
    static String key(Alert a){return a.severity()+":"+a.type()+":"+a.target();}
    synchronized List<Alert> current(){
        List<Alert> out=new ArrayList<>();double warn=cfg.getDouble("alerts.msptWarn",40),crit=cfg.getDouble("alerts.msptCritical",50),heapWarn=cfg.getDouble("alerts.heapWarnPercent",90);long hb=cfg.getLong("worker.heartbeatTimeoutSeconds",10);
        for(WorkerRecord w:workers.all()){
            if(!w.usableForExistingSession() && w.state!=WorkerState.UNHEALTHY && w.state!=WorkerState.CRASHED)continue;
            if(w.state==WorkerState.CRASHED||w.state==WorkerState.UNHEALTHY)out.add(new Alert("critical","WORKER_HEALTH",w.id,w.state.name()+" worker requires attention"));
            if(w.mspt>=crit)out.add(new Alert("critical","MSPT",w.id,String.format(Locale.ROOT,"MSPT %.1f exceeds critical %.1f",w.mspt,crit)));
            else if(w.mspt>=warn)out.add(new Alert("warning","MSPT",w.id,String.format(Locale.ROOT,"MSPT %.1f exceeds warning %.1f",w.mspt,warn)));
            if(w.heapMax>0){double pct=100.0*w.heapUsed/w.heapMax;if(pct>=heapWarn)out.add(new Alert("warning","HEAP",w.id,String.format(Locale.ROOT,"Heap usage %.1f%%",pct)));}
            long age=w.heartbeatAgeSeconds();if(age>hb*2&&w.lastHeartbeatMillis>0)out.add(new Alert("critical","HEARTBEAT",w.id,"Heartbeat age "+age+"s"));
            if(w.worldSeedKnown&&!w.worldSeedMatch)out.add(new Alert(cfg.getBoolean("world.identity.enforce",false)?"critical":"warning","WORLD_SEED",w.id,"Worker seed does not match the locked cluster seed"));
            if(w.worldIdentity!=null&&!w.worldIdentity.isBlank()&&!w.worldIdentityMatch)out.add(new Alert(cfg.getBoolean("world.identity.enforce",false)?"critical":"warning","WORLD_IDENTITY",w.id,"Worker world identity fingerprint does not match the cluster"));
        }
        var budget=workers.capacityBudget();
        if(budget.hardLimit()&&budget.availableSlots()==0)out.add(new Alert("warning","MEMORY_BUDGET","cluster","Worker memory budget is at its safe JVM limit ("+budget.effectiveMaxWorkers()+")"));
        if(!startup.state().equals("READY"))out.add(new Alert("info","STARTUP","cluster","Startup pipeline: "+startup.state()));
        for(WorkerRecord w:workers.all())if(w.ready&&w.heartbeatAgeSeconds()<10){
            if(w.failedWrites>0)out.add(new Alert("critical","PERSISTENCE_FAILED",w.id,w.failedWrites+" failed durable writes; planned transfers blocked. Preserve this worker and inspect its log."));
            else if(w.pendingWrites>100)out.add(new Alert("warning","PERSISTENCE_PENDING",w.id,w.pendingWrites+" world writes await acknowledgement"));
        }
        var g=gateway.snapshot();if(g.enabled()&&!g.running())out.add(new Alert("critical","GATEWAY","gateway","Gateway is not listening on "+g.bind()+":"+g.port()+(g.lastError().isBlank()?"":" — "+g.lastError())));
        if(cfg.lifecycle.shuttingDown())out.clear();
        if(!acknowledgementsLoaded){
            try(var in=Files.newInputStream(cfg.home().resolve("runtime/alert-acknowledgements.properties"))){savedAcknowledgements.load(in);}catch(IOException ignored){}
            acknowledgementsLoaded=true;
        }
        Set<String> active=new HashSet<>();
        for(Alert a:out){String key=key(a);active.add(key);incidents.computeIfAbsent(key,k->new Incident(System.currentTimeMillis(),savedAcknowledgements.getProperty(k,"")));}
        var removed=new HashSet<>(incidents.keySet());removed.removeAll(active);incidents.keySet().retainAll(active);
        boolean dirty=savedAcknowledgements.keySet().removeIf(k->!active.contains(k));
        if(dirty)try{saveAcknowledgements();}catch(IOException ignored){}
        return out;
    }
    synchronized String actor(Alert alert){var i=incidents.get(key(alert));return i==null?"":i.actor();}
    synchronized long firstSeen(Alert alert){var i=incidents.get(key(alert));return i==null?0:i.firstSeen();}
    synchronized void acknowledge(String key,String actor)throws IOException{
        current();var prior=incidents.get(key);if(prior==null)throw new IllegalArgumentException("Alert has already resolved");
        String previous=savedAcknowledgements.getProperty(key);savedAcknowledgements.setProperty(key,actor);
        try {saveAcknowledgements();}catch(IOException ex){if(previous==null)savedAcknowledgements.remove(key);else savedAcknowledgements.setProperty(key,previous);throw ex;}
        incidents.put(key,new Incident(prior.firstSeen(),actor));
    }
    private void saveAcknowledgements()throws IOException{
        Path file=cfg.home().resolve("runtime/alert-acknowledgements.properties"),tmp=file.resolveSibling("alert-acknowledgements.tmp");Files.createDirectories(file.getParent());
        try(var out=Files.newOutputStream(tmp)){savedAcknowledgements.store(out,"Active incident acknowledgements");}
        try{Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException ex){Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING);}
    }
    record Alert(String severity,String type,String target,String message){}
}
