package com.velocitypowered.proxy.connection.backend;

import com.velocitypowered.proxy.protocol.ProtocolUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.*;

/** NeoForge 21.1.250 wire fixtures: unordered registry names, exact ID/alias maps. */
public final class RegistryReplaySelfTest {
    static final String START="neoforge:frozen_registry_sync_start",DATA="neoforge:frozen_registry",END="neoforge:frozen_registry_sync_completed";
    static int checks;
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);checks++;}
    static void rejects(Runnable run,String reason){try{run.run();throw new AssertionError("Unsafe registry replay accepted: "+reason);}catch(IllegalStateException expected){check(expected.getMessage().contains(reason),expected.getMessage());}}
    static byte[] bytes(ByteBuf b){try{byte[] a=new byte[b.readableBytes()];b.getBytes(b.readerIndex(),a);return a;}finally{b.release();}}
    static byte[] manifest(String...names){var b=Unpooled.buffer();ProtocolUtils.writeVarInt(b,names.length);for(String n:names)ProtocolUtils.writeString(b,n);return bytes(b);}
    static byte[] registry(String name,int entryId,String entry,String aliasTarget){
        var b=Unpooled.buffer();ProtocolUtils.writeString(b,name);
        ProtocolUtils.writeVarInt(b,1);ProtocolUtils.writeVarInt(b,entryId);ProtocolUtils.writeString(b,entry);
        ProtocolUtils.writeVarInt(b,1);ProtocolUtils.writeString(b,"test:old");ProtocolUtils.writeString(b,aliasTarget);
        return bytes(b);
    }
    static FoundationsHandshakeCapture.Transcript begin(){var t=new FoundationsHandshakeCapture.Transcript();t.started=true;t.put(true,"neoforge:register",new byte[]{0});t.put(false,"neoforge:register",new byte[]{0});return t;}
    static final byte[] BLOCK=registry("minecraft:block",7,"test:block","test:block"),ITEM=registry("minecraft:item",19,"test:item","test:item");
    static FoundationsHandshakeCapture.Transcript captured(){
        var t=begin();t.put(true,START,manifest("minecraft:block","minecraft:item"));t.put(true,DATA,BLOCK);t.put(true,DATA,ITEM);t.put(true,END,new byte[0]);t.put(false,END,new byte[0]);t.complete=true;return t;
    }
    static FoundationsHandshakeCapture.Cursor ready(){var c=captured().cursor();c.check("neoforge:register",new byte[]{0});c.check(START,manifest("minecraft:block","minecraft:item"));return c;}
    public static void main(String[] args){
        var c=captured().cursor();c.check("neoforge:register",new byte[]{0});
        c.check(START,manifest("minecraft:item","minecraft:block"));checks++;
        c.check(DATA,ITEM);c.check(DATA,BLOCK);checks++;
        c.check(END,new byte[0]);c.finish();checks++;
        var same=ready();same.check(DATA,BLOCK);same.check(DATA,ITEM);same.check(END,new byte[0]);same.finish();checks++;
        rejects(()->captured().cursor().check(START,manifest("minecraft:block","test:extra")),"missing=[minecraft:item]");
        rejects(()->captured().cursor().check(START,manifest("minecraft:block","minecraft:item","test:extra")),"added=[test:extra]");
        rejects(()->captured().cursor().check(START,manifest("minecraft:block")),"missing=[minecraft:item]");
        rejects(()->ready().check(DATA,registry("minecraft:block",8,"test:block","test:block")),"minecraft:block");
        rejects(()->ready().check(DATA,registry("minecraft:block",7,"test:other","test:block")),"minecraft:block");
        rejects(()->ready().check(DATA,registry("minecraft:block",7,"test:block","test:other")),"minecraft:block");
        rejects(()->ready().check(DATA,registry("test:extra",7,"test:block","test:block")),"test:extra");
        var duplicate=ready();duplicate.check(DATA,BLOCK);rejects(()->duplicate.check(DATA,BLOCK),"minecraft:block");
        var omitted=ready();omitted.check(DATA,BLOCK);rejects(()->omitted.check(END,new byte[0]),"minecraft:item");
        rejects(()->ready().finish(),"omitted");
        rejects(()->captured().cursor().check(DATA,BLOCK),"before registry manifest");
        rejects(()->ready().check(START,manifest("minecraft:block","minecraft:item")),"entry 1");
        rejects(()->ready().check(START,manifest("minecraft:block","minecraft:block")),"Duplicate");
        rejects(()->ready().check(START,new byte[]{(byte)0xff,(byte)0xff,(byte)0xff,(byte)0xff,0x7f}),"manifest");
        rejects(()->ready().check(START,new byte[]{1}),"manifest");
        rejects(()->ready().check(START,new byte[]{0,1}),"Trailing");
        rejects(()->ready().check(START,manifest("invalid name")),"registry name");
        var initial=begin();initial.put(true,START,manifest("minecraft:block"));initial.put(true,DATA,BLOCK);rejects(()->initial.put(true,DATA,BLOCK),"Duplicate");
        var immutable=ready();byte[] payload=BLOCK.clone();immutable.check(DATA,payload);check(Arrays.equals(payload,BLOCK),"Comparison changed source bytes");
        for(int seed=0;seed<25;seed++){
            var names=new ArrayList<>(List.of("minecraft:block","minecraft:item"));Collections.shuffle(names,new Random(seed));
            var cur=captured().cursor();cur.check("neoforge:register",new byte[]{0});cur.check(START,manifest(names.toArray(String[]::new)));
            var packets=new ArrayList<>(List.of(BLOCK,ITEM));Collections.shuffle(packets,new Random(seed+1));for(byte[] packet:packets)cur.check(DATA,packet);
            cur.check(END,new byte[0]);cur.finish();checks++;
        }
        System.out.println("R19-HF4 registry comparison: PASS ("+checks+" checks)");
    }
}
