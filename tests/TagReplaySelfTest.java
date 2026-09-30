package com.velocitypowered.proxy.connection.backend;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import com.velocitypowered.proxy.protocol.packet.config.TagsUpdatePacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.*;

/** Real Conduit 1.8.0 tag packets: map order is irrelevant; member order stays exact. */
public final class TagReplaySelfTest {
    static final String KEY="vanilla:TagsUpdatePacket";
    static final ProtocolVersion VERSION=ProtocolVersion.MINECRAFT_1_21;
    static int checks;
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);checks++;}
    static void rejects(Runnable run,String reason){
        try{run.run();throw new AssertionError("Unsafe tag replay accepted: "+reason);}
        catch(IllegalStateException expected){check(expected.getMessage().contains(reason),expected.getMessage());}
    }
    static byte[] bytes(ByteBuf b){try{byte[] a=new byte[b.readableBytes()];b.getBytes(b.readerIndex(),a);return a;}finally{b.release();}}
    static byte[] wire(TagsUpdatePacket p){var b=Unpooled.buffer();p.encode(b,ProtocolUtils.Direction.CLIENTBOUND,VERSION);return bytes(b);}
    static TagsUpdatePacket decode(byte[] raw){var b=Unpooled.wrappedBuffer(raw);try{var p=new TagsUpdatePacket();p.decode(b,ProtocolUtils.Direction.CLIENTBOUND,VERSION);check(!b.isReadable(),"Conduit did not consume fixture");return p;}finally{b.release();}}
    static LinkedHashMap<String,Map<String,int[]>> fixture(){
        var m=new LinkedHashMap<String,Map<String,int[]>>();
        var blocks=new LinkedHashMap<String,int[]>();
        blocks.put("minecraft:mineable/pickaxe",new int[]{0,1,127,128,8192});
        blocks.put("c:ores",new int[]{11,4,17});blocks.put("test:empty",new int[0]);
        m.put("minecraft:block",blocks);
        m.put("minecraft:item",new LinkedHashMap<>(Map.of("c:ingots",new int[]{6,3},"test:duplicates",new int[]{7,7})));
        m.put("test:empty_registry",new LinkedHashMap<>());
        return m;
    }
    static <T> LinkedHashMap<String,T> reorder(Map<String,T> m,Random random){
        var keys=new ArrayList<>(m.keySet());if(random==null)Collections.reverse(keys);else Collections.shuffle(keys,random);
        var result=new LinkedHashMap<String,T>();for(String key:keys)result.put(key,m.get(key));return result;
    }
    static Map<String,Map<String,int[]>> reordered(Random random){
        var result=reorder(fixture(),random);result.replaceAll((k,v)->reorder(v,random));return result;
    }
    static FoundationsHandshakeCapture.Transcript captured(byte[] raw){
        var t=new FoundationsHandshakeCapture.Transcript();t.started=true;
        t.put(true,"neoforge:register",new byte[]{0});t.put(false,"neoforge:register",new byte[]{0});
        t.put(true,KEY,raw);t.complete=true;return t;
    }
    static FoundationsHandshakeCapture.Cursor cursor(){
        var c=captured(wire(new TagsUpdatePacket(fixture()))).cursor();c.check("neoforge:register",new byte[]{0});return c;
    }
    static void accepts(Map<String,Map<String,int[]>> tags){var c=cursor();c.check(KEY,wire(decode(wire(new TagsUpdatePacket(tags)))));c.finish();checks++;}
    static void rejects(Map<String,Map<String,int[]>> tags,String reason){rejects(()->cursor().check(KEY,wire(new TagsUpdatePacket(tags))),reason);}
    static byte[] raw(int registries,String registry,int tags,String tag,int...ids){
        var b=Unpooled.buffer();ProtocolUtils.writeVarInt(b,registries);
        if(registry!=null){ProtocolUtils.writeString(b,registry);ProtocolUtils.writeVarInt(b,tags);}
        if(tag!=null){ProtocolUtils.writeString(b,tag);ProtocolUtils.writeVarIntArray(b,ids);}return bytes(b);
    }
    public static void main(String[] args){
        byte[] original=wire(new TagsUpdatePacket(fixture())),reversed=wire(new TagsUpdatePacket(reordered(null)));
        check(!Arrays.equals(original,reversed),"Fixture must expose HF4 byte-order rejection");
        accepts(reordered(null)); // Reproduces the rejection with unchanged HF4.
        accepts(fixture());
        for(int seed=0;seed<20;seed++)accepts(reordered(new Random(seed)));
        var changed=fixture();changed.remove("minecraft:item");rejects(changed,"missingRegistries=[minecraft:item]");
        changed=fixture();changed.put("test:extra",Map.of());rejects(changed,"addedRegistries=[test:extra]");
        changed=fixture();changed.remove("test:empty_registry");rejects(changed,"missingRegistries=[test:empty_registry]");
        changed=fixture();changed.get("minecraft:block").remove("test:empty");rejects(changed,"missingTags=[test:empty]");
        changed=fixture();changed.get("minecraft:block").put("test:extra",new int[0]);rejects(changed,"addedTags=[test:extra]");
        changed=fixture();changed.get("minecraft:block").put("c:ores",new int[]{11,5,17});rejects(changed,"memberIndex=1 expectedId=4 destinationId=5");
        changed=fixture();changed.get("minecraft:block").put("c:ores",new int[]{11,17,4});rejects(changed,"sameMembersDifferentOrder=true");
        changed=fixture();changed.get("minecraft:block").put("c:ores",new int[]{11,4});rejects(changed,"memberCounts=3/2");
        changed=fixture();changed.get("minecraft:block").put("c:ores",new int[]{11,4,17,17});rejects(changed,"memberCounts=3/4");
        changed=fixture();changed.get("minecraft:item").put("test:duplicates",new int[]{7});rejects(changed,"memberCounts=2/1");
        var c=cursor();c.check(KEY,original);rejects(()->c.check(KEY,original),"unexpected or duplicate");
        rejects(()->cursor().finish(),"omitted");
        for(int i=0;i<original.length;i++){
            final byte[] truncated=Arrays.copyOf(original,i);
            rejects(()->cursor().check(KEY,truncated),"tag configuration");
        }
        rejects(()->cursor().check(KEY,Arrays.copyOf(original,original.length+1)),"Trailing tag configuration");
        rejects(()->cursor().check(KEY,raw(-1,null,0,null)),"tag configuration");
        rejects(()->cursor().check(KEY,raw(Integer.MAX_VALUE,null,0,null)),"tag configuration");
        rejects(()->cursor().check(KEY,raw(1,"minecraft:block",-1,null)),"tag configuration");
        rejects(()->cursor().check(KEY,raw(1,"minecraft:block",Integer.MAX_VALUE,null)),"tag configuration");
        rejects(()->cursor().check(KEY,raw(1,"bad name",0,null)),"tag configuration");
        rejects(()->cursor().check(KEY,raw(1,"minecraft:block",1,"bad tag",0)),"tag configuration");
        rejects(()->cursor().check(KEY,raw(1,"minecraft:block",1,"test:tag",-1)),"tag configuration");
        var malformed=Unpooled.buffer();ProtocolUtils.writeVarInt(malformed,1);ProtocolUtils.writeString(malformed,"minecraft:block");ProtocolUtils.writeVarInt(malformed,1);ProtocolUtils.writeString(malformed,"test:tag");ProtocolUtils.writeVarInt(malformed,Integer.MAX_VALUE);
        final byte[] excessiveMembers=bytes(malformed);rejects(()->cursor().check(KEY,excessiveMembers),"tag configuration");
        malformed=Unpooled.buffer();ProtocolUtils.writeVarInt(malformed,2);for(int i=0;i<2;i++){ProtocolUtils.writeString(malformed,"minecraft:block");ProtocolUtils.writeVarInt(malformed,0);}
        final byte[] duplicateRegistry=bytes(malformed);rejects(()->cursor().check(KEY,duplicateRegistry),"Duplicate tag configuration registry");
        malformed=Unpooled.buffer();ProtocolUtils.writeVarInt(malformed,1);ProtocolUtils.writeString(malformed,"minecraft:block");ProtocolUtils.writeVarInt(malformed,2);for(int i=0;i<2;i++){ProtocolUtils.writeString(malformed,"test:tag");ProtocolUtils.writeVarInt(malformed,0);}
        final byte[] duplicateTag=bytes(malformed);rejects(()->cursor().check(KEY,duplicateTag),"Duplicate tag configuration tag");
        rejects(()->captured(duplicateTag),"Duplicate tag configuration tag");
        var packet=new TagsUpdatePacket(reordered(null));byte[] before=wire(packet);var transcript=captured(before);before[0]^=1;
        var replay=transcript.cursor();replay.check("neoforge:register",new byte[]{0});replay.check(KEY,original);replay.finish();checks++;
        check(Arrays.equals(reversed,wire(packet)),"Comparison changed packet forwarded to the real client");
        check(Arrays.equals(original,wire(new TagsUpdatePacket(fixture()))),"Comparison mutated input member arrays");
        var empty=captured(wire(new TagsUpdatePacket(Map.of()))).cursor();empty.check("neoforge:register",new byte[]{0});empty.check(KEY,new byte[]{0});empty.finish();checks++;
        var channel=new EmbeddedChannel();
        var connection=new MinecraftConnection(channel,null){@Override public ProtocolVersion getProtocolVersion(){return VERSION;}};
        try{
            var observed=new FoundationsHandshakeCapture.Transcript();observed.started=true;
            FoundationsHandshakeCapture.captureVanilla(observed,packet,connection);
            check(observed.invalid.isEmpty(),"Valid actual tag packet failed capture");
            check(Arrays.equals(reversed,wire(packet)),"Actual observation changed client packet");
            var badPacket=new TagsUpdatePacket(Map.of("minecraft:block",Map.of("test:tag",new int[]{-1})));
            var failed=new FoundationsHandshakeCapture.Transcript();failed.started=true;
            byte[] invalidBefore=wire(badPacket);
            FoundationsHandshakeCapture.captureVanilla(failed,badPacket,connection);
            check(!failed.invalid.isEmpty(),"Bad tag capture must disable replay");
            check(Arrays.equals(invalidBefore,wire(badPacket)),"Failed capture changed normal packet forwarding");
            rejects(()->failed.cursor(),"Unsupported initial configuration encoding");
        }finally{channel.finishAndReleaseAll();}
        System.out.println("R19-HF5 tag replay: PASS ("+checks+" checks)");
    }
}
