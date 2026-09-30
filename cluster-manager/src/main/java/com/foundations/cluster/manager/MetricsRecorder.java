package com.foundations.cluster.manager;

import java.util.*;
import java.util.concurrent.*;

/** Bounded in-memory histories: 5s/1m/1h cluster tiers and one-hour worker detail. */
final class MetricsRecorder implements AutoCloseable {
    private final ManagerConfig cfg; private final WorkerManager workers;
    private final Deque<Sample> samples=new ArrayDeque<>(), minutes=new ArrayDeque<>(), hours=new ArrayDeque<>();
    private final Map<String,Deque<WorkerSample>> workerSamples=new HashMap<>();
    private long lastMinute,lastHour;
    private final ScheduledExecutorService exec=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"foundations-metrics");t.setDaemon(true);return t;});
    MetricsRecorder(ManagerConfig cfg,WorkerManager workers){this.cfg=cfg;this.workers=workers;sample();exec.scheduleAtFixedRate(this::sample,5,5,TimeUnit.SECONDS);}
    static boolean hasFreshTelemetry(WorkerRecord w,long timeout){
        return w.lastHeartbeatMillis>0 && w.heartbeatAgeSeconds()<=timeout && w.usableForExistingSession();
    }
    private synchronized void sample(){
        var all=workers.all();var live=all.stream().filter(w->hasFreshTelemetry(w,cfg.getLong("worker.heartbeatTimeoutSeconds",10))).toList();
        int players=live.stream().mapToInt(w->w.players).sum();double mspt=live.stream().mapToDouble(w->w.mspt).average().orElse(0),cpu=live.stream().mapToDouble(w->w.cpuPercent).average().orElse(0);
        long hu=live.stream().mapToLong(w->w.heapUsed).sum(),hm=live.stream().mapToLong(w->w.heapMax).sum(),ready=live.stream().filter(WorkerRecord::routableForNewSession).count(),now=System.currentTimeMillis();
        Sample s=new Sample(now,all.size(),(int)ready,players,mspt,cpu,hu,hm);
        append(samples,s,Math.max(60,Math.min(2000,cfg.getInt("metrics.retentionSamples",1440))));
        if(now-lastMinute>=60_000){append(minutes,s,1440);lastMinute=now;}
        if(now-lastHour>=3_600_000){append(hours,s,168);lastHour=now;}
        Set<String> seen=new HashSet<>();
        for(WorkerRecord w:all.stream().sorted(Comparator.comparing(x->x.id)).limit(128).toList()){
            seen.add(w.id);if(!hasFreshTelemetry(w,cfg.getLong("worker.heartbeatTimeoutSeconds",10)))continue;
            var queue=workerSamples.computeIfAbsent(w.id,k->new ArrayDeque<>());
            append(queue,new WorkerSample(now,w.mspt,w.msptP95,w.msptP99,w.cpuPercent,w.heapUsed,w.heapMax,w.players,w.loadedChunks),720);
        }
        workerSamples.keySet().retainAll(seen);
    }
    private static <T> void append(Deque<T> queue,T value,int max){queue.addLast(value);while(queue.size()>max)queue.removeFirst();}
    synchronized List<Sample> tail(int limit){return samples.stream().skip(Math.max(0,samples.size()-Math.max(1,limit))).toList();}
    synchronized List<Sample> range(long seconds){
        long start=System.currentTimeMillis()-Math.min(604800,Math.max(300,seconds))*1000L;
        var source=seconds>86400?hours:seconds>7200?minutes:samples;
        return source.stream().filter(s->s.time()>=start).toList();
    }
    synchronized String workerJson(String id){
        var out=new StringJoiner(",","{\"retentionSeconds\":3600,\"samples\":[","]}");
        for(var s:workerSamples.getOrDefault(id,new ArrayDeque<>()))out.add("{\"time\":"+s.time()+",\"mspt\":"+s.mspt()+",\"p95\":"+s.p95()+",\"p99\":"+s.p99()+",\"cpu\":"+s.cpu()+",\"heapUsed\":"+s.heapUsed()+",\"heapMax\":"+s.heapMax()+",\"players\":"+s.players()+",\"chunks\":"+s.chunks()+"}");
        return out.toString();
    }
    public void close(){exec.shutdownNow();}
    record Sample(long time,int workers,int readyWorkers,int players,double mspt,double cpu,long heapUsed,long heapMax){}
    record WorkerSample(long time,double mspt,double p95,double p99,double cpu,long heapUsed,long heapMax,int players,int chunks){}
}
