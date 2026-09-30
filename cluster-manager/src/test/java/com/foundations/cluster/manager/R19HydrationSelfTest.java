package com.foundations.cluster.manager;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
public final class R19HydrationSelfTest {
 static int checks;
 static void check(boolean ok,String reason){if(!ok)throw new AssertionError(reason);checks++;}
 static String token(RegionRecord r){return Base64.getUrlEncoder().withoutPadding().encodeToString(r.key.id().getBytes(StandardCharsets.UTF_8))+"|"+r.epoch;}
 static void tick(RegionAuthorityManager regions)throws Exception{var method=RegionAuthorityManager.class.getDeclaredMethod("tick");method.setAccessible(true);method.invoke(regions);}
 static void rejects(Runnable op){try{op.run();throw new AssertionError("Expected rejection");}catch(IllegalArgumentException|IllegalStateException expected){checks++;}}
 public static void main(String[]args)throws Exception{
  Path home=Files.createTempDirectory("r19-hydration-");Files.createDirectories(home.resolve("config"));Files.writeString(home.resolve("config/cluster.properties"),"world.seed=1234\nworld.environment.enabled=false\nworld.identity.enforce=false\nregion.autoFailover=false\nregion.checkSeconds=3600\nregion.recoverySeconds=0\n");
  ManagerConfig cfg=ManagerConfig.load(home);ClusterLog log=new ClusterLog(home.resolve("logs/test.log"),100);
  try(WorkerManager workers=new WorkerManager(cfg,new TokenStore(home.resolve("runtime")),log,new TemplateManager(cfg,log),new NodeAgentManager(cfg,new TemplateManager(cfg,log),log))){
   var a=workers.register(Map.of("workerId","worker-001","port","25566","ready","true","worldSeed","1234","nodeVersion","0.0.6a.R10"));a.persistenceInstance="process-a";
   var b=workers.register(Map.of("workerId","worker-002","port","25567","ready","true","worldSeed","1234","nodeVersion","0.0.6a.R10"));b.persistenceInstance="process-b";
   String id;
   try(var regions=new RegionAuthorityManager(cfg,workers,log)){
    var r=regions.ensure(new RegionKey("foundations","minecraft:overworld",0,0),a.id,"test");id=r.key.id();
    check(r.state==RegionState.RECOVERING,"New region active before hydration");tick(regions);check(r.state==RegionState.RECOVERING,"Zero recovery timer bypassed hydration");
    rejects(()->regions.replayRegions(a.id,"old-process",token(r)));rejects(()->regions.replayRegions(b.id,b.persistenceInstance,token(r)));
    rejects(()->regions.replayRegions(a.id,a.persistenceInstance,token(r)+"0"));rejects(()->regions.replayRegions(a.id,a.persistenceInstance,""));
    rejects(()->RegionAuthorityManager.parseHydrationToken(token(r)+";"+token(r)));rejects(()->RegionAuthorityManager.parseHydrationToken("bad-token"));
    check(regions.replayRegions(a.id,a.persistenceInstance,token(r)).equals(List.of(r)),"Valid pending scope rejected");
    regions.reportHydration(b,token(r));tick(regions);check(r.state==RegionState.RECOVERING,"Other worker ack activated region");
    regions.reportHydration(a,token(r)+"0");tick(regions);check(r.state==RegionState.RECOVERING,"Other epoch activated region");
    regions.reportHydration(a,token(r));tick(regions);check(r.state==RegionState.ACTIVE,"Correct ack did not activate region");
    var other=regions.ensure(new RegionKey("foundations","minecraft:overworld",1,0),a.id,"unrelated");tick(regions);
    check(r.state==RegionState.ACTIVE&&other.state==RegionState.RECOVERING,"Pending neighbor paused already hydrated region");
    try(var journal=new PersistentBlockJournal(cfg,log)){
     journal.record("foundations","minecraft:overworld",id,r.epoch,8,100,8,"minecraft:oak_log");
     journal.record("foundations","minecraft:overworld",other.key.id(),other.epoch,520,100,8,"minecraft:chest");
     String scoped=new String(Base64.getUrlDecoder().decode(journal.bundleForRegions(regions.replayRegions(a.id,a.persistenceInstance,token(other)))),StandardCharsets.UTF_8);
     check(scoped.lines().count()==1&&!scoped.contains("bWluZWNyYWZ0Om9ha19sb2c"),"Unrelated ACTIVE region included in replay");
    }
    a.persistenceInstance="restarted-process";tick(regions);check(r.state==RegionState.RECOVERING,"New process inherited old ack");
    regions.reportHydration(a,token(r));tick(regions);check(r.state==RegionState.ACTIVE,"Restarted process restore ack rejected");
    regions.freeze(id,true);tick(regions);check(r.state==RegionState.FROZEN,"Ack bypassed manual freeze");regions.freeze(id,false);check(r.state==RegionState.ACTIVE,"Hydrated unfreeze blocked");
   }
   try(var restarted=new RegionAuthorityManager(cfg,workers,log)){
    var r=restarted.get(id);tick(restarted);check(r.state==RegionState.RECOVERING,"Manager restart trusted heartbeat without restore ack");
    restarted.reportHydration(a,token(r));tick(restarted);check(r.state==RegionState.ACTIVE,"Manager restart rejected current process ack");
   }
  }
  try(var paths=Files.walk(home)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}
  System.out.println("R19 destination hydration barrier: PASS ("+checks+" checks)");
 }
}
