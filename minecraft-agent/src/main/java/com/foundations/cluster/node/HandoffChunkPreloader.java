package com.foundations.cluster.node;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded, nonblocking handoff preload coordinator. It asks Minecraft's normal
 * ticket system to load chunks and observes already-loaded chunks; it NEVER
 * synchronously calls getChunk or waits on a future from the server tick.
 *
 * The owner must invoke start/tick/close on the single server thread. A job's
 * tickets remain until a short post-ready grace interval expires, to bridge
 * the Manager's final snapshot / backend-switch stages. Tickets are NEVER a
 * durable world/ownership barrier, and this class does not waive the existing
 * source save/final checkpoint requirements.
 */
public final class HandoffChunkPreloader implements AutoCloseable {
    public static final int MAX_JOBS = 4, MAX_RADIUS = 4, TICKETS_PER_TICK = 4;
    public static final long WAIT_NANOS = 18_000_000_000L;
    public static final long READY_GRACE_NANOS = 10_000_000_000L;

    public record Chunk(int x, int z) { }
    public interface Chunks {
        void request(Chunk chunk);
        boolean loaded(Chunk chunk);
        void release(Chunk chunk);
    }
    public interface Completion {
        void ready(String handoffId, int count, long elapsedNanos);
        void abort(String handoffId, String reason);
    }
    private static final class Job {
        final String id;
        final String dimension;
        final Chunks chunks;
        final List<Chunk> ordered;
        final long start;
        int requested;
        long readyAt = -1;
        Job(String id, String dimension, Chunks chunks, List<Chunk> ordered, long start) {
            this.id=id;this.dimension=dimension;this.chunks=chunks;this.ordered=ordered;this.start=start;
        }
    }
    private final Map<String,Job> jobs = new HashMap<>();
    private final Completion completion;
    private boolean closed;

    public HandoffChunkPreloader(Completion completion) {
        this.completion=Objects.requireNonNull(completion);
    }
    /** Returns false for an identical already-in-flight command. */
    public boolean start(String id, String dimension, int cx, int cz, int radius,
                         Chunks chunks, long monotonicNow) {
        if (closed) throw new IllegalStateException("Preloader stopped");
        if (id==null || !id.matches("[A-Za-z0-9_.-]{1,96}")) throw new IllegalArgumentException("Invalid handoff ID");
        if (dimension==null || !dimension.matches("[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,128}"))
            throw new IllegalArgumentException("Invalid destination dimension");
        if (radius<0 || radius>MAX_RADIUS) throw new IllegalArgumentException("Unbounded handoff preload radius");
        Objects.requireNonNull(chunks);
        Job existing=jobs.get(id);
        if (existing!=null) {
            List<Chunk> requested=order(cx,cz,radius);
            if (!existing.dimension.equals(dimension) || !existing.ordered.equals(requested))
                throw new IllegalStateException("Handoff ID reused with different destination");
            return false;
        }
        if (jobs.size()>=MAX_JOBS) throw new IllegalStateException("Too many active preloads");
        jobs.put(id,new Job(id,dimension,chunks,order(cx,cz,radius),monotonicNow));
        return true;
    }
    public int active() { return jobs.size(); }
    public int outstanding() {
        int n=0;for(Job job:jobs.values())n+=job.requested;
        return n;
    }
    /** Called once per server tick; no synchronous chunk IO. */
    public void tick(long monotonicNow) {
        if (closed) return;
        for (Job job:List.copyOf(jobs.values())) {
            if (!jobs.containsKey(job.id)) continue;
            if (job.readyAt>=0) {
                if (monotonicNow-job.readyAt>=READY_GRACE_NANOS) finish(job);
                continue;
            }
            if (monotonicNow-job.start>=WAIT_NANOS) {
                fail(job,"Destination preload timed out waiting for already-ticketed chunks");
                continue;
            }
            try {
                int budget=TICKETS_PER_TICK;
                while (budget-->0 && job.requested<job.ordered.size())
                    job.chunks.request(job.ordered.get(job.requested++));
                if (job.requested==job.ordered.size()) {
                    boolean all=true;
                    for (Chunk c:job.ordered) if (!job.chunks.loaded(c)) { all=false;break; }
                    if (all) {
                        job.readyAt=monotonicNow;
                        completion.ready(job.id,job.ordered.size(),monotonicNow-job.start);
                    }
                }
            } catch (RuntimeException ex) {
                fail(job,"Destination chunk preload failed");
            }
        }
    }
    /** Abort removes only this handoff's tickets and notifies Manager once. */
    public void cancel(String id, String reason) {
        Job job=jobs.get(id);if(job!=null)fail(job,reason);
    }
    private void fail(Job job, String reason) {
        finish(job);
        completion.abort(job.id,reason);
    }
    private void finish(Job job) {
        if(jobs.remove(job.id)!=job)return;
        for (int i=0;i<job.requested;i++) {
            try { job.chunks.release(job.ordered.get(i)); }
            catch (RuntimeException ignored) { /* continue best-effort release */ }
        }
    }
    /** Center-first concentric rings. Overflow is rejected rather than wrapped. */
    public static List<Chunk> order(int cx,int cz,int radius) {
        if(radius<0||radius>MAX_RADIUS)throw new IllegalArgumentException("Radius out of bounds");
        var positions=new ArrayList<Chunk>((2*radius+1)*(2*radius+1));
        for(int dx=-radius;dx<=radius;dx++)for(int dz=-radius;dz<=radius;dz++)
            positions.add(new Chunk(Math.addExact(cx,dx),Math.addExact(cz,dz)));
        positions.sort(Comparator.<Chunk>comparingInt(c->Math.max(Math.abs(c.x()-cx),Math.abs(c.z()-cz)))
            .thenComparingInt(c->Math.abs(c.x()-cx)+Math.abs(c.z()-cz))
            .thenComparingInt(Chunk::x).thenComparingInt(Chunk::z));
        return List.copyOf(positions);
    }
    @Override public void close() {
        closed=true;
        for(Job job:List.copyOf(jobs.values()))finish(job);
    }
}
