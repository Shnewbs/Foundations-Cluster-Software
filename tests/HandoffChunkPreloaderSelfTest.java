import com.foundations.cluster.node.HandoffChunkPreloader;
import java.util.*;

/** Reusable deterministic server-tick model, not connected-client acceptance. */
public final class HandoffChunkPreloaderSelfTest {
    private static int checks;
    private static void check(boolean b,String message) {
        if(!b)throw new AssertionError(message);checks++;
    }
    private static final class Fake implements HandoffChunkPreloader.Chunks {
        List<HandoffChunkPreloader.Chunk> asked=new ArrayList<>();
        List<HandoffChunkPreloader.Chunk> freed=new ArrayList<>();
        Set<HandoffChunkPreloader.Chunk> resident=new HashSet<>();
        @Override public void request(HandoffChunkPreloader.Chunk c){asked.add(c);}
        @Override public boolean loaded(HandoffChunkPreloader.Chunk c){return resident.contains(c);}
        @Override public void release(HandoffChunkPreloader.Chunk c){freed.add(c);}
    }
    public static void main(String[] args) {
        var rings=HandoffChunkPreloader.order(12,-7,2);
        check(rings.size()==25&&new HashSet<>(rings).size()==25,"Full 5x5 unique square");
        check(rings.getFirst().equals(new HandoffChunkPreloader.Chunk(12,-7)),"Center chunk requested first");
        check(rings.stream().allMatch(c->Math.abs(c.x()-12)<=2&&Math.abs(c.z()+7)<=2),"Only intended square");
        var events=new ArrayList<String>();Fake backend=new Fake();
        var loader=new HandoffChunkPreloader(new HandoffChunkPreloader.Completion(){
            public void ready(String id,int n,long time){events.add("ready "+id+" "+n+" "+time);}
            public void abort(String id,String reason){events.add("abort "+id+" "+reason);}
        });
        long clock=123456789;
        check(loader.start("handoff-1","minecraft:overworld",12,-7,2,backend,clock),"New handoff accepted");
        check(!loader.start("handoff-1","minecraft:overworld",12,-7,2,backend,clock),"Identical Manager command deduplicated");
        loader.tick(clock);check(backend.asked.size()==4&&events.isEmpty(),"At most four nonblocking chunk tickets per tick");
        for(int i=1;i<=6;i++)loader.tick(clock+i*50_000_000L);
        check(backend.asked.size()==25&&new HashSet<>(backend.asked).size()==25,"All chunks ticketed without duplicate request");
        check(events.isEmpty(),"Ticket issuance is not ready acknowledgment");
        backend.resident.addAll(backend.asked);backend.resident.remove(backend.asked.getLast());
        loader.tick(clock+400_000_000L);check(events.isEmpty(),"Readiness waits for last loaded chunk");
        backend.resident.add(backend.asked.getLast());
        loader.tick(clock+450_000_000L);check(events.size()==1&&events.getFirst().startsWith("ready handoff-1 25"),"Exactly one ready after all chunks loaded");
        loader.tick(clock+2_000_000_000L);check(backend.freed.isEmpty(),"Tickets survive final checkpoint/backend-switch grace");
        loader.tick(clock+10_451_000_000L);check(backend.freed.size()==25&&loader.active()==0,"All tickets released after grace");
        loader.close();check(backend.freed.size()==25,"Shutdown does not duplicate ticket releases");
        Fake slow=new Fake();var failures=new ArrayList<String>();
        var timeout=new HandoffChunkPreloader(new HandoffChunkPreloader.Completion(){
            public void ready(String id,int n,long d){throw new AssertionError("Unready handoff acknowledged");}
            public void abort(String id,String why){failures.add(why);}
        });
        timeout.start("slow","minecraft:overworld",0,0,1,slow,0);
        timeout.tick(1);check(slow.asked.size()==4,"First ticket batch issued");
        timeout.tick(HandoffChunkPreloader.WAIT_NANOS+1);
        check(failures.size()==1 && slow.freed.size()==4 && timeout.active()==0,"Timeout abort and ticket cleanup");
        timeout.tick(HandoffChunkPreloader.WAIT_NANOS+5);
        check(failures.size()==1,"Timeout not duplicated on later ticks");
        var cancelled=new Fake();timeout.start("cancel","minecraft:the_nether",0,0,1,cancelled,100);
        timeout.tick(150);timeout.cancel("cancel","Manager cancelled");
        check(cancelled.freed.size()==4&&timeout.active()==0&&failures.size()==2,"Cancellation releases only acquired tickets");
        var one=new Fake();var two=new Fake();
        timeout.start("one","minecraft:overworld",1,1,0,one,100);
        timeout.start("two","minecraft:overworld",1,1,0,two,100);
        timeout.tick(150);
        timeout.cancel("one","cancel first");
        check(one.freed.size()==1&&two.freed.isEmpty(),"Overlapping separate jobs retain independent ticket IDs");
        timeout.close();check(two.freed.size()==1,"Shutdown releases second ticket without touching first");
        try {timeout.start("three","minecraft:overworld",1,1,0,new Fake(),100);throw new AssertionError("Closed loader allowed start");}
        catch(IllegalStateException expected){checks++;}
        try {HandoffChunkPreloader.order(Integer.MAX_VALUE,0,1);throw new AssertionError("coordinate overflow accepted");}
        catch(ArithmeticException expected){checks++;}
        System.out.println("Handoff ticket preloader: PASS ("+checks+" checks; deterministic, no real client)");
    }
}
