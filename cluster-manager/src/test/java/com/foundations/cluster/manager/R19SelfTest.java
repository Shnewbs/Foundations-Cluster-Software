package com.foundations.cluster.manager;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
public final class R19SelfTest {
 static int checks;
 static void check(boolean ok,String reason){if(!ok)throw new AssertionError(reason);checks++;}
 static String command(RegionAuthorityManager regions,String worker)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(System.nanoTime()<until){String v=regions.checkpointCommands(worker);if(!v.isBlank())return v;Thread.sleep(10);}throw new AssertionError("No checkpoint");}
 public static void main(String[]args)throws Exception{
  Path home=Files.createTempDirectory("r19-region-test-");Files.createDirectories(home.resolve("config"));Files.writeString(home.resolve("config/cluster.properties"),"world.seed=1234\nworld.environment.enabled=false\nworld.identity.enforce=false\nregion.autoFailover=false\n");
  ManagerConfig cfg=ManagerConfig.load(home);ClusterLog log=new ClusterLog(home.resolve("logs/test.log"),100);
  try(WorkerManager workers=new WorkerManager(cfg,new TokenStore(home.resolve("runtime")),log,new TemplateManager(cfg,log),new NodeAgentManager(cfg,new TemplateManager(cfg,log),log));RegionAuthorityManager regions=new RegionAuthorityManager(cfg,workers,log)){
   for(int i=1;i<=2;i++)workers.register(Map.of("workerId","worker-00"+i,"port",""+(25565+i),"ready","true","worldSeed","1234","nodeVersion","0.0.6a.R9"));
   var r=regions.ensure(new RegionKey("foundations","minecraft:overworld",0,0),"worker-001","test");long epoch=r.epoch;
   var move=CompletableFuture.supplyAsync(()->regions.transfer(r.key.id(),"worker-002","test"));
   String[] c=command(regions,"worker-001").split("\\|");
   check(r.ownerWorkerId.equals("worker-001")&&r.epoch==epoch,"Ownership moved before save");
   try{regions.acknowledgeCheckpoint("worker-002","",c[0],epoch,"");throw new AssertionError();}catch(IllegalStateException expected){checks++;}
   try{regions.acknowledgeCheckpoint("worker-001","",c[0],epoch+1,"");throw new AssertionError();}catch(IllegalStateException expected){checks++;}
   try{regions.acknowledgeCheckpoint("worker-001","restarted-process",c[0],epoch,"");throw new AssertionError();}catch(IllegalStateException expected){checks++;}
   regions.acknowledgeCheckpoint("worker-001","",c[0],epoch,"");move.get(3,TimeUnit.SECONDS);check(r.ownerWorkerId.equals("worker-002")&&r.epoch>epoch,"Confirmed save did not transfer");
   r.state=RegionState.ACTIVE;
   var failed=CompletableFuture.supplyAsync(()->regions.transfer(r.key.id(),"worker-001","test"));c=command(regions,"worker-002").split("\\|");
   regions.acknowledgeCheckpoint("worker-002","",c[0],r.epoch,"Disk write failed");
   try{failed.get(3,TimeUnit.SECONDS);throw new AssertionError();}catch(ExecutionException expected){checks++;}
   check(r.ownerWorkerId.equals("worker-002"),"Save failure lost source ownership");
   var cancelled=CompletableFuture.supplyAsync(()->regions.transfer(r.key.id(),"worker-001","test"));command(regions,"worker-002");regions.cancelCheckpoint(r.key.id());
   try{cancelled.get(3,TimeUnit.SECONDS);throw new AssertionError();}catch(ExecutionException expected){checks++;}
   check(regions.checkpointCommands("worker-002").isEmpty(),"Cancelled checkpoint still sealed");
  }
  System.out.println("R19 save barrier checks: PASS ("+checks+")");
 }
}
