package com.foundations.cluster.protocol;
import com.foundations.cluster.protocol.EntityViewsWire.*;
import java.io.*;
import java.util.*;
public final class EntityViewsWireSelfTest {
    private static int checks;
    private interface Attempt{void run()throws Exception;}
    private static void check(boolean yes,String why){checks++;if(!yes)throw new AssertionError(why);}
    private static void rejects(Attempt a)throws Exception{boolean failed=false;try{a.run();}catch(IOException|IllegalArgumentException|NullPointerException e){failed=true;}check(failed,"Invalid wire input accepted");}
    public static void main(String[] args)throws Exception {
        UUID binding=UUID.randomUUID(),id=UUID.randomUUID();
        byte[] bytes={1,2,3};
        var state=new State(binding,id,Kind.COW,"minecraft:overworld",-1.125,67.5,10.25,90,15,45,.125,-.2,0,true,bytes);
        bytes[0]=9;check(state.metadata()[0]==1,"input defensively copied");var copy=state.metadata();copy[0]=8;check(state.metadata()[0]==1,"output defensively copied");
        var player=new Player(UUID.randomUUID(),"Fixture","minecraft:overworld",0,65,10);
        var request=new Request(12,List.of(state),List.of(player));var encoded=EntityViewsWire.encodeRequest(request);var decoded=EntityViewsWire.decodeRequest(encoded);
        check(decoded.sequence()==12&&decoded.states().size()==1&&decoded.players().equals(request.players()),"request envelope round trip");
        check(decoded.states().getFirst().entity().equals(id)&&decoded.states().getFirst().x()==state.x()&&Arrays.equals(decoded.states().getFirst().metadata(),state.metadata()),"identity, precision and metadata round trip");
        var pair=new Pair(binding,id,"worker-001","identity",123,"minecraft:overworld",Kind.COW,true);
        var sample=new Sample(pair,state,UUID.randomUUID(),22,1000);var response=new Response(12,UUID.randomUUID(),List.of(pair),List.of(new Viewer(player.uuid(),List.of(sample))));
        byte[] reply=EntityViewsWire.encodeResponse(response);var back=EntityViewsWire.decodeResponse(reply);
        check(back.sequence()==12&&back.boot().equals(response.boot())&&back.owned().equals(response.owned()),"reply process/definitions round trip");
        check(back.viewers().getFirst().samples().getFirst().instance().equals(sample.instance()),"source process reset boundary preserved");
        check(player.near(state),"within interest radius");check(!new Player(player.uuid(),"Fixture","minecraft:the_nether",0,65,10).near(state),"dimension isolated");
        check(!new Player(player.uuid(),"Fixture","minecraft:overworld",1000,65,10).near(state),"distance bounded");
        check(!EntityViewsWire.decodePairs(EntityViewsWire.encodePairs(List.of(pair))).getFirst().interested(),"transient interest is not persisted");
        for(int i=0;i<encoded.length;i++){byte[] cut=Arrays.copyOf(encoded,i);rejects(()->EntityViewsWire.decodeRequest(cut));}
        for(int i=0;i<reply.length;i++){byte[] cut=Arrays.copyOf(reply,i);rejects(()->EntityViewsWire.decodeResponse(cut));}
        rejects(()->EntityViewsWire.decodeRequest(reply));rejects(()->EntityViewsWire.decodeResponse(encoded));
        rejects(()->EntityViewsWire.decodeResponse(Arrays.copyOf(reply,reply.length+1)));
        rejects(()->new Request(0,List.of(),List.of()));rejects(()->new Request(1,List.of(state,state),List.of()));
        rejects(()->new Request(1,List.of(),List.of(player,player)));
        rejects(()->new State(binding,id,Kind.COW,"minecraft:overworld",Double.NaN,0,0,0,0,0,0,0,0,false,new byte[]{1}));
        rejects(()->new State(binding,id,Kind.COW,"minecraft:overworld",0,0,0,0,0,0,101,0,0,false,new byte[]{1}));
        rejects(()->new State(binding,id,Kind.COW,"minecraft:overworld",0,0,0,0,0,0,0,0,0,false,new byte[EntityViewsWire.MAX_METADATA+1]));
        rejects(()->new Sample(pair,state,UUID.randomUUID(),1,EntityViewsWire.FRESH_MILLIS+1));
        rejects(()->new Pair(binding,id,"../worker","identity",0,"minecraft:overworld",Kind.COW,false));
        rejects(()->new Player(UUID.randomUUID(),"bad\nname","minecraft:overworld",0,0,0));
        rejects(()->EntityViewsWire.decodeRequest(new byte[EntityViewsWire.MAX_BYTES+1]));
        System.out.println("Entity view wire: PASS ("+checks+" checks)");
    }
}
