package com.foundations.cluster.manager;

import com.foundations.cluster.protocol.WorldEnvironmentSnapshot;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.LongSupplier;

/** One overworld environment writer with bounded leases and restore-before-publish fencing. */
final class WorldEnvironmentCoordinator implements AutoCloseable {
    static final long LEASE_MILLIS=10000;
    private final ManagerConfig cfg; private final ClusterLog log; private final LongSupplier clock;
    private final Path file; private WorldEnvironmentSnapshot value;
    private String owner="", ownerInstance=""; private long epoch, revision, seed, receivedAt, leaseUntil, lastSaved;
    private long ownerSequence=-1, electionRevision; private boolean restored;
    private final Map<String,Observation> observations=new HashMap<>();
    private record Observation(long seen,long epoch,long revision,WorldEnvironmentSnapshot value){}

    WorldEnvironmentCoordinator(ManagerConfig cfg,ClusterLog log)throws IOException{this(cfg,log,System::currentTimeMillis);}
    WorldEnvironmentCoordinator(ManagerConfig cfg,ClusterLog log,LongSupplier clock)throws IOException{
        this.cfg=cfg;this.log=log;this.clock=clock;file=cfg.home().resolve("runtime/world-environment.properties");
        if(Files.isRegularFile(file)){
            Properties p=new Properties();try(InputStream in=Files.newInputStream(file)){p.load(in);}
            try{value=WorldEnvironmentSnapshot.decode(p.getProperty("snapshot"));epoch=Long.parseLong(p.getProperty("epoch"))+1;revision=Long.parseLong(p.getProperty("revision"));seed=Long.parseLong(p.getProperty("seed"));restored=true;}
            catch(RuntimeException e){throw new IOException("Invalid persisted world environment; restore its backup before starting",e);}
        }
        receivedAt=clock.getAsLong();log.info("World environment coordinator initialized: enabled="+enabled()+" restored="+restored+" protocol=overworld-v1");
    }
    boolean enabled(){return cfg.getBoolean("world.environment.enabled",true);}
    synchronized void report(WorkerRecord w,Map<String,String> m){
        if(!enabled()||!w.worldSeedKnown||!w.worldSeedMatch||!w.worldIdentityMatch||!w.usableForExistingSession())return;
        String token=m.getOrDefault("worldEnvironment","");if(token.isBlank())return;
        WorldEnvironmentSnapshot sample=WorldEnvironmentSnapshot.decode(token);
        long now=clock.getAsLong(),sampleAge=Json.l(m,"worldEnvironmentAgeMillis",0);
        if(sampleAge<0||sampleAge>5000)return; // Do not renew a lease using a stalled worker's old sample.
        sample=sample.advance(sampleAge);
        long ackEpoch=Json.l(m,"worldEnvironmentEpoch",0),ackRevision=Json.l(m,"worldEnvironmentRevision",0),sequence=Json.l(m,"worldEnvironmentSequence",0);
        String instance=m.getOrDefault("worldEnvironmentInstance","");
        if(instance.isBlank()||instance.length()>80)return;
        if(value!=null&&seed!=w.worldSeed)return;
        observations.put(w.id,new Observation(now,ackEpoch,ackRevision,sample));
        if(owner.isBlank()||now>=leaseUntil||(owner.equals(w.id)&&!ownerInstance.equals(instance))){
            // Freeze at the lease boundary rather than advancing through downtime.
            if(value!=null&&!restored)value=current(now);
            owner=w.id;ownerInstance=instance;ownerSequence=-1;epoch++;electionRevision=revision;
            receivedAt=now;leaseUntil=now+LEASE_MILLIS;restored=false;
            if(value==null){value=sample;seed=w.worldSeed;revision++;electionRevision=revision;}
            save();log.info("World environment owner="+owner+" epoch="+epoch+"; new owner must restore before publishing");
        }
        if(owner.equals(w.id)&&ownerInstance.equals(instance)&&ackEpoch==epoch&&ackRevision>=electionRevision&&sequence>ownerSequence){
            value=sample;receivedAt=now;leaseUntil=now+LEASE_MILLIS;ownerSequence=sequence;revision++;
            if(now-lastSaved>=5000)save();
        }
        w.environmentReady=ackEpoch==epoch&&ackRevision>0&&now<leaseUntil;
        w.environmentLeaseUntilMillis=w.environmentReady?leaseUntil:0;
    }
    private WorldEnvironmentSnapshot current(long now){
        if(value==null)return null;
        return value.advance(restored||owner.isBlank()?0:Math.max(0,Math.min(now,leaseUntil)-receivedAt));
    }
    synchronized String controlJson(){
        long now=clock.getAsLong();WorldEnvironmentSnapshot snapshot=current(now);
        return ",\"worldEnvironmentEnabled\":"+enabled()+",\"worldEnvironmentOwner\":"+Json.q(owner)
            +",\"worldEnvironmentEpoch\":"+epoch+",\"worldEnvironmentRevision\":"+revision
            +",\"worldEnvironmentLeaseMillis\":"+Math.max(0,leaseUntil-now)
            +",\"worldEnvironment\":"+Json.q(enabled()&&snapshot!=null?snapshot.encode():"");
    }
    synchronized String statusJson(){
        long now=clock.getAsLong();WorldEnvironmentSnapshot snapshot=current(now);
        StringBuilder b=new StringBuilder("{\"enabled\":").append(enabled()).append(",\"protocol\":\"overworld-v1\",\"ownerWorkerId\":").append(Json.q(owner))
            .append(",\"epoch\":").append(epoch).append(",\"revision\":").append(revision).append(",\"fresh\":").append(snapshot!=null&&now<leaseUntil)
            .append(",\"dayTime\":").append(snapshot==null?0:snapshot.dayTime()).append(",\"raining\":").append(snapshot!=null&&snapshot.raining())
            .append(",\"thundering\":").append(snapshot!=null&&snapshot.thundering()).append(",\"workers\":[");
        boolean first=true;for(var e:new TreeMap<>(observations).entrySet()){
            if(!first)b.append(',');first=false;Observation o=e.getValue();
            b.append("{\"workerId\":").append(Json.q(e.getKey())).append(",\"ageMillis\":").append(Math.max(0,now-o.seen))
                .append(",\"synchronized\":").append(o.epoch==epoch&&o.revision>0&&now-o.seen<LEASE_MILLIS&&now<leaseUntil)
                .append(",\"dayTime\":").append(o.value.dayTime()).append(",\"raining\":").append(o.value.raining()).append('}');
        }return b.append("]}").toString();
    }
    private void save(){
        if(value==null)return;
        try{Files.createDirectories(file.getParent());Properties p=new Properties();p.setProperty("snapshot",current(clock.getAsLong()).encode());p.setProperty("epoch",Long.toString(epoch));p.setProperty("revision",Long.toString(revision));p.setProperty("seed",Long.toString(seed));
            Path temp=file.resolveSibling(file.getFileName()+".tmp");try(OutputStream out=Files.newOutputStream(temp)){p.store(out,"Foundations authoritative overworld environment");}
            try{Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException ex){Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING);}
            lastSaved=clock.getAsLong();
        }catch(IOException ex){log.warn("World environment checkpoint failed: "+ex.getMessage());}
    }
    @Override public synchronized void close(){save();}
}
