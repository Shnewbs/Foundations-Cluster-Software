package com.velocitypowered.proxy.connection.backend;

import com.velocitypowered.proxy.protocol.ProtocolUtils;
import com.velocitypowered.proxy.protocol.packet.PluginMessagePacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.*;

/** Fixtures follow the exact NeoForge 21.1.250 payload codecs and client consumers. */
public final class NeoForgeDeclarationSelfTest {
    static final String MAPS="neoforge:known_registry_data_maps",ENUMS="neoforge:extensible_enum_data",FLAGS="neoforge:feature_flags";
    static int checks;
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);checks++;}
    static void rejects(Runnable run,String reason){try{run.run();throw new AssertionError("Unsafe declaration accepted: "+reason);}catch(IllegalStateException expected){check(expected.getMessage().contains(reason),expected.getMessage());}}
    static byte[] bytes(ByteBuf b){try{byte[] a=new byte[b.readableBytes()];b.getBytes(b.readerIndex(),a);return a;}finally{b.release();}}
    static byte[] maps(Map<String,Map<String,Boolean>> maps){
        var b=Unpooled.buffer();ProtocolUtils.writeVarInt(b,maps.size());
        maps.forEach((reg,entries)->{ProtocolUtils.writeString(b,reg);ProtocolUtils.writeVarInt(b,entries.size());entries.forEach((id,mandatory)->{ProtocolUtils.writeString(b,id);b.writeBoolean(mandatory);});});return bytes(b);
    }
    static LinkedHashMap<String,Map<String,Boolean>> fixture(){
        var m=new LinkedHashMap<String,Map<String,Boolean>>();
        var items=new LinkedHashMap<String,Boolean>();items.put("neoforge:compostables",false);items.put("test:mandatory",true);items.put("test:optional",false);
        m.put("minecraft:item",items);m.put("minecraft:block",new LinkedHashMap<>(Map.of("neoforge:oxidizables",false,"neoforge:waxables",true)));m.put("test:empty",new LinkedHashMap<>());return m;
    }
    static <T> LinkedHashMap<String,T> reorder(Map<String,T> map,Random random){
        var names=new ArrayList<>(map.keySet());if(random==null)Collections.reverse(names);else Collections.shuffle(names,random);
        var out=new LinkedHashMap<String,T>();for(String n:names)out.put(n,map.get(n));return out;
    }
    static Map<String,Map<String,Boolean>> shuffled(Random random){var m=reorder(fixture(),random);m.replaceAll((k,v)->reorder(v,random));return m;}
    static byte[] names(String...names){var b=Unpooled.buffer();ProtocolUtils.writeVarInt(b,names.length);for(String s:names)ProtocolUtils.writeString(b,s);return bytes(b);}
    static byte[] enumEntry(String clazz,String direction,int vanillaCount,int total,String...names){
        var b=Unpooled.buffer();ProtocolUtils.writeString(b,clazz);ProtocolUtils.writeString(b,direction);b.writeBoolean(names!=null);
        if(names!=null){ProtocolUtils.writeVarInt(b,vanillaCount);ProtocolUtils.writeVarInt(b,total);b.writeBytes(names(names));}return bytes(b);
    }
    static byte[] enums(byte[]...entries){var b=Unpooled.buffer();ProtocolUtils.writeVarInt(b,entries.length);for(byte[] e:entries)b.writeBytes(e);return bytes(b);}
    static final byte[] A=enumEntry("test.Mode","BIDIRECTIONAL",2,4,"ALPHA","BETA"),B=enumEntry("test.Other$Nested","CLIENTBOUND",0,0,(String[])null),C=enumEntry("test.Third","SERVERBOUND",1,2,"GAMMA");
    static FoundationsHandshakeCapture.Transcript captured(String channel,byte[] raw){
        var t=new FoundationsHandshakeCapture.Transcript();t.started=true;t.put(true,"neoforge:register",new byte[]{0});t.put(false,"neoforge:register",new byte[]{0});
        t.put(true,channel,raw);t.put(false,FoundationsHandshakeCapture.REPLIES.get(channel),new byte[]{23,45});t.complete=true;return t;
    }
    static FoundationsHandshakeCapture.Cursor cursor(String channel,byte[] raw){var c=captured(channel,raw).cursor();c.check("neoforge:register",new byte[]{0});return c;}
    static void accepts(String channel,byte[] from,byte[] to){var c=cursor(channel,from);c.check(channel,to);c.finish();checks++;check(Arrays.equals(c.replies(FoundationsHandshakeCapture.REPLIES.get(channel)).getFirst(),new byte[]{23,45}),"Captured client reply changed");}
    static void rejectsMaps(Map<String,Map<String,Boolean>> after,String reason){rejects(()->cursor(MAPS,maps(fixture())).check(MAPS,maps(after)),reason);}
    static void invalid(String channel,byte[] data){rejects(()->cursor(channel,switch(channel){case MAPS->maps(fixture());case ENUMS->enums(A,B,C);default->names("test:a","test:b");}).check(channel,data),"Invalid NeoForge declaration");}
    public static void main(String[] args){
        byte[] original=maps(fixture()),reversed=maps(shuffled(null));
        check(!Arrays.equals(original,reversed),"Baseline fixture must differ in serialization order");
        accepts(MAPS,original,reversed); // This throws the user's channel error on unchanged HF5.
        accepts(MAPS,original,original);
        for(int seed=0;seed<12;seed++)accepts(MAPS,original,maps(shuffled(new Random(seed))));
        var m=fixture();m.remove("minecraft:block");rejectsMaps(m,"missingRegistries=[minecraft:block]");
        m=fixture();m.put("test:extra",Map.of());rejectsMaps(m,"addedRegistries=[test:extra]");
        m=fixture();m.remove("test:empty");rejectsMaps(m,"missingRegistries=[test:empty]");
        m=fixture();m.get("minecraft:item").remove("test:optional");rejectsMaps(m,"missingDataMaps=[test:optional]");
        m=fixture();m.get("minecraft:item").put("test:extra",false);rejectsMaps(m,"addedDataMaps=[test:extra]");
        m=fixture();m.get("minecraft:item").put("test:mandatory",false);rejectsMaps(m,"mandatory=true/false");
        m=fixture();m.get("minecraft:item").put("test:optional",true);rejectsMaps(m,"mandatory=false/true");
        m=fixture();m.get("minecraft:item").remove("test:mandatory");m.get("minecraft:block").put("test:mandatory",true);rejectsMaps(m,"addedDataMaps=[test:mandatory]");
        accepts(MAPS,new byte[]{0},new byte[]{0});
        var malformed=Unpooled.buffer();ProtocolUtils.writeVarInt(malformed,2);for(int i=0;i<2;i++){ProtocolUtils.writeString(malformed,"minecraft:item");ProtocolUtils.writeVarInt(malformed,0);}invalid(MAPS,bytes(malformed));
        malformed=Unpooled.buffer();ProtocolUtils.writeVarInt(malformed,1);ProtocolUtils.writeString(malformed,"minecraft:item");ProtocolUtils.writeVarInt(malformed,2);for(int i=0;i<2;i++){ProtocolUtils.writeString(malformed,"test:duplicate");malformed.writeBoolean(false);}invalid(MAPS,bytes(malformed));
        malformed=Unpooled.buffer();ProtocolUtils.writeVarInt(malformed,1);ProtocolUtils.writeString(malformed,"minecraft:item");ProtocolUtils.writeVarInt(malformed,1);ProtocolUtils.writeString(malformed,"test:bad_bool");malformed.writeByte(2);invalid(MAPS,bytes(malformed));
        invalid(MAPS,maps(Map.of("bad registry",Map.of())));invalid(MAPS,maps(Map.of("minecraft:item",Map.of("bad map",false))));
        byte[] enumOriginal=enums(A,B,C);accepts(ENUMS,enumOriginal,enums(C,A,B));accepts(ENUMS,enums(),enums());
        rejects(()->cursor(ENUMS,enumOriginal).check(ENUMS,enums(A,B)),"missingEnums=[test.Third]");
        rejects(()->cursor(ENUMS,enumOriginal).check(ENUMS,enums(A,B,C,enumEntry("test.Added","CLIENTBOUND",0,0,(String[])null))),"addedEnums=[test.Added]");
        for(byte[] changed:List.of(enumEntry("test.Mode","CLIENTBOUND",2,4,"ALPHA","BETA"),enumEntry("test.Mode","BIDIRECTIONAL",2,4,"BETA","ALPHA"),enumEntry("test.Mode","BIDIRECTIONAL",2,4,"ALPHA","OTHER"),enumEntry("test.Mode","BIDIRECTIONAL",3,5,"ALPHA","BETA"),enumEntry("test.Mode","BIDIRECTIONAL",2,5,"ALPHA","BETA","EXTRA"),enumEntry("test.Mode","BIDIRECTIONAL",0,0,(String[])null))){
            rejects(()->cursor(ENUMS,enumOriginal).check(ENUMS,enums(changed,B,C)),"enumClass=test.Mode");
        }
        invalid(ENUMS,enums(A,A));invalid(ENUMS,enums(enumEntry("","CLIENTBOUND",0,0,(String[])null)));
        invalid(ENUMS,enums(enumEntry("test.Mode","CLIENTBOUND",2,1)));invalid(ENUMS,enums(enumEntry("test.Mode","CLIENTBOUND",2,4,"ONLY_ONE")));
        invalid(ENUMS,enums(enumEntry("test.Mode","CLIENTBOUND",-1,0,"ONLY_ONE")));
        for(int badCount:new int[]{-1,Integer.MAX_VALUE}){
            malformed=Unpooled.buffer();ProtocolUtils.writeVarInt(malformed,1);ProtocolUtils.writeString(malformed,"minecraft:item");ProtocolUtils.writeVarInt(malformed,badCount);invalid(MAPS,bytes(malformed));
            malformed=Unpooled.buffer();ProtocolUtils.writeVarInt(malformed,1);ProtocolUtils.writeString(malformed,"test.Enum");ProtocolUtils.writeString(malformed,"CLIENTBOUND");malformed.writeBoolean(true);ProtocolUtils.writeVarInt(malformed,0);ProtocolUtils.writeVarInt(malformed,0);ProtocolUtils.writeVarInt(malformed,badCount);invalid(ENUMS,bytes(malformed));
        }
        byte[] badOptional=enums(B);badOptional[badOptional.length-1]=2;invalid(ENUMS,badOptional);
        byte[] flags=names("test:a","test:b","test:c");accepts(FLAGS,flags,names("test:c","test:a","test:b"));accepts(FLAGS,names(),names());
        rejects(()->cursor(FLAGS,flags).check(FLAGS,names("test:a","test:b")),"missingFlags=[test:c]");
        rejects(()->cursor(FLAGS,flags).check(FLAGS,names("test:a","test:b","test:c","test:d")),"addedFlags=[test:d]");
        invalid(FLAGS,names("test:a","test:a"));invalid(FLAGS,names("Bad Name"));
        for(var entry:Map.of(MAPS,original,ENUMS,enumOriginal,FLAGS,flags).entrySet()){
            String channel=entry.getKey();byte[] raw=entry.getValue();
            for(int n=0;n<raw.length;n++)invalid(channel,Arrays.copyOf(raw,n));
            invalid(channel,Arrays.copyOf(raw,raw.length+1));
            for(int badCount:new int[]{-1,Integer.MAX_VALUE}){var b=Unpooled.buffer();ProtocolUtils.writeVarInt(b,badCount);invalid(channel,bytes(b));}
            var c=cursor(channel,raw);c.check(channel,raw);rejects(()->c.check(channel,raw),"unexpected or duplicate");rejects(()->cursor(channel,raw).finish(),"omitted");
            var packet=new PluginMessagePacket(channel,Unpooled.buffer().writeByte(99).writeBytes(raw));packet.content().readerIndex(1);
            try{
                int reader=packet.content().readerIndex(),writer=packet.content().writerIndex(),refs=packet.refCnt();
                var t=captured(channel,FoundationsHandshakeCapture.payload(packet));
                check(reader==packet.content().readerIndex()&&writer==packet.content().writerIndex()&&refs==packet.refCnt(),"Capture changed packet ownership");
                check(Arrays.equals(raw,FoundationsHandshakeCapture.payload(packet)),"Capture changed packet bytes");
                byte[] copy=FoundationsHandshakeCapture.payload(packet);t= captured(channel,copy);copy[0]^=1;
                var owned=t.cursor();owned.check("neoforge:register",new byte[]{0});owned.check(channel,raw);owned.finish();checks++;
            }finally{packet.release();}
        }
        // Mixed declaration sequence with all acknowledgements still supplied by the real login.
        var t=captured(MAPS,original);t.complete=false;t.put(true,ENUMS,enumOriginal);t.put(false,"neoforge:extensible_enum_ack",new byte[0]);t.put(true,FLAGS,flags);t.put(false,"neoforge:feature_flags_ack",new byte[0]);t.complete=true;
        var c=t.cursor();c.check("neoforge:register",new byte[]{0});c.check(MAPS,reversed);c.check(ENUMS,enums(C,B,A));c.check(FLAGS,names("test:b","test:c","test:a"));c.finish();checks++;
        System.out.println("R19-HF6 NeoForge declaration replay: PASS ("+checks+" checks)");
    }
}
