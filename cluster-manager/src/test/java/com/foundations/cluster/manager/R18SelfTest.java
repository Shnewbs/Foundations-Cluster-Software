package com.foundations.cluster.manager;
import com.foundations.cluster.protocol.WorldEnvironmentSnapshot;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public final class R18SelfTest {
    private static int checks;
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);checks++;}
    private static WorkerRecord worker(String id){WorkerRecord w=new WorkerRecord(id,25566,true);w.ready=true;w.state=WorkerState.READY;w.worldSeedKnown=true;w.worldSeed=1234;return w;}
    private static WorldEnvironmentSnapshot sample(long time,boolean rain){return new WorldEnvironmentSnapshot(time,0,-1,true,true,0,5000,9000,rain,false,rain?1:0,0);}
    private static Map<String,String> report(WorldEnvironmentSnapshot value,String instance,long epoch,long revision,long sequence){Map<String,String> m=new HashMap<>();m.put("worldEnvironment",value.encode());m.put("worldEnvironmentInstance",instance);m.put("worldEnvironmentEpoch",""+epoch);m.put("worldEnvironmentRevision",""+revision);m.put("worldEnvironmentSequence",""+sequence);return m;}
    private static Map<String,String> control(WorldEnvironmentCoordinator c){return Json.flatObject("{\"ok\":true"+c.controlJson()+"}");}
    private static long epoch(WorldEnvironmentCoordinator c){return Json.l(control(c),"worldEnvironmentEpoch",-1);}
    private static long revision(WorldEnvironmentCoordinator c){return Json.l(control(c),"worldEnvironmentRevision",-1);}
    private static WorldEnvironmentSnapshot snapshot(WorldEnvironmentCoordinator c){return WorldEnvironmentSnapshot.decode(control(c).get("worldEnvironment"));}
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("foundations-r18-");Files.createDirectories(root.resolve("config"));Files.writeString(root.resolve("config/cluster.properties"),"world.seed=1234\nworld.environment.enabled=true\n");
        ManagerConfig cfg=ManagerConfig.load(root);ClusterLog log=new ClusterLog(root.resolve("logs/test.log"),100);AtomicLong clock=new AtomicLong(100000);
        var a=worker("worker-001");var b=worker("worker-002");
        try(var c=new WorldEnvironmentCoordinator(cfg,log,clock::get)){
            c.report(a,report(sample(24000,false),"a",0,0,1));long firstEpoch=epoch(c);
            check(control(c).get("worldEnvironmentOwner").equals(a.id),"First healthy worker not elected");
            check(!a.environmentReady,"Unacknowledged owner was ready");
            c.report(b,report(sample(90000,true),"b",0,0,1));check(snapshot(c).dayTime()==24000&&!snapshot(c).raining(),"Follower overwrote environment");
            c.report(a,report(sample(24001,false),"a",firstEpoch,revision(c),2));check(a.environmentReady,"Owner acknowledgement rejected");
            c.report(b,report(sample(24001,false),"b",firstEpoch,revision(c),2));check(b.environmentReady,"Follower acknowledgement rejected");
            long prior=revision(c);c.report(a,report(sample(9999,true),"a",firstEpoch,prior,1));check(revision(c)==prior&&!snapshot(c).raining(),"Out-of-order report accepted");
            clock.addAndGet(1000);check(snapshot(c).dayTime()==24021,"Clock prediction drift");
            c.report(a,report(sample(6000,true),"a",firstEpoch,revision(c),3));check(snapshot(c).dayTime()==6000&&snapshot(c).raining(),"Owner time/weather command not propagated");
            clock.addAndGet(11000);long held=snapshot(c).dayTime();clock.addAndGet(90000);check(snapshot(c).dayTime()==held,"Expired lease advanced through downtime");
            c.report(b,report(sample(99999,false),"b",firstEpoch,prior,3));long secondEpoch=epoch(c);
            check(secondEpoch>firstEpoch&&control(c).get("worldEnvironmentOwner").equals(b.id),"Failover did not elect reporter");
            check(snapshot(c).dayTime()==held&&snapshot(c).raining(),"Failover imported stale local weather/time");
            c.report(a,report(sample(1,false),"a",firstEpoch,revision(c),4));check(snapshot(c).dayTime()==held,"Old owner bypassed epoch fence");
            c.report(b,report(sample(held,true),"b",secondEpoch,revision(c),4));check(b.environmentReady,"New owner restore acknowledgement rejected");
            long beforeRestart=snapshot(c).dayTime();c.report(b,report(sample(50000,false),"b-restarted",secondEpoch,revision(c),1));
            check(epoch(c)>secondEpoch&&snapshot(c).dayTime()==beforeRestart,"Restarted owner bypassed restore fence");
            var wrong=worker("wrong");wrong.worldSeed=999;c.report(wrong,report(sample(1,false),"x",0,0,1));check(control(c).get("worldEnvironmentOwner").equals(b.id),"Mismatched seed became owner");
            Map<String,String> stale=report(sample(1,false),"b-restarted",epoch(c),revision(c),2);stale.put("worldEnvironmentAgeMillis","6000");prior=revision(c);c.report(b,stale);check(revision(c)==prior,"Stale sample renewed ownership");
        }
        try(var restored=new WorldEnvironmentCoordinator(cfg,log,clock::get)){
            check(snapshot(restored).raining(),"Rain checkpoint not restored");long day=snapshot(restored).dayTime();clock.addAndGet(100000);check(snapshot(restored).dayTime()==day,"Offline clock progressed");
            restored.report(a,report(sample(1,false),"new",0,0,1));check(snapshot(restored).dayTime()==day&&snapshot(restored).raining(),"Restart used stale worker disk state");
        }
        WorldEnvironmentSnapshot paused=new WorldEnvironmentSnapshot(10,0,-1,false,false,0,100,100,true,false,1,0);
        check(paused.advance(5000).dayTime()==10&&paused.advance(5000).rainTicks()==100,"Paused game rules ignored");
        var slow=new WorldEnvironmentSnapshot(10,.25f,.5f,true,true,0,100,100,false,false,0,0);
        check(slow.advance(1000).dayTime()==20&&slow.advance(1000).fraction()==.25f,"NeoForge day rate lost");
        for(String bad:List.of("",sample(1,false).encode().replace("|1|0.0|","|-1|0.0|"),sample(1,false).encode().replace("|0.0|","|NaN|"),sample(1,false).encode().replace("true","invalid"))){try{WorldEnvironmentSnapshot.decode(bad);throw new AssertionError("Invalid snapshot accepted");}catch(IllegalArgumentException expected){checks++;}}
        Path toml=root.resolve("conduit.toml");Files.writeString(toml,"[other]\nseamless-server-switches = true\n[advanced]\nseamless-server-switches = false # normal\n");check(Boolean.FALSE.equals(GatewayPolicy.readLaunchPolicy(toml)),"Wrong TOML section used");
        Files.writeString(toml,"[advanced]\nseamless-server-switches = true\n");check(Boolean.TRUE.equals(GatewayPolicy.readLaunchPolicy(toml)),"Enabled launch policy missing");
        Files.writeString(toml,"[advanced]\n");check(GatewayPolicy.readLaunchPolicy(toml)==null,"Missing policy treated as proven");
        cfg.updateEditable(Map.of("world.environment.enabled","false"));check(!cfg.getBoolean("world.environment.enabled",true),"Environment switch not configurable");
        try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}
        System.out.println("R18 environment / policy checks: PASS ("+checks+" assertions)");
    }
}
