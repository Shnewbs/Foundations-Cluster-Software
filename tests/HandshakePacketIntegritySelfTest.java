package com.velocitypowered.proxy.connection.backend;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.MinecraftSessionHandler;
import com.velocitypowered.proxy.protocol.MinecraftPacket;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import com.velocitypowered.proxy.protocol.packet.config.*;
import io.netty.buffer.*;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.*;
import net.kyori.adventure.key.Key;

/** Real Conduit packet objects: observe, then encode the same object for the client. */
public final class HandshakePacketIntegritySelfTest {
 static int checks;
 static final ProtocolVersion VERSION=ProtocolVersion.MINECRAFT_1_21;
 static void check(boolean condition,String reason){if(!condition)throw new AssertionError(reason);checks++;}
 static byte[] bytes(ByteBuf buffer){byte[] data=new byte[buffer.readableBytes()];buffer.getBytes(buffer.readerIndex(),data);return data;}
 static byte[] wire(MinecraftPacket packet){var out=Unpooled.buffer();try{packet.encode(out,ProtocolUtils.Direction.CLIENTBOUND,VERSION);return bytes(out);}finally{out.release();}}
 static void registry(MinecraftConnection connection,ByteBuf content,String kind){
  content.writeBytes(new byte[]{99,98,97});ProtocolUtils.writeString(content,"minecraft:dimension_type");ProtocolUtils.writeVarInt(content,0);content.readerIndex(3);
  var packet=new RegistrySyncPacket();packet.replace(content);
  byte[] expected=bytes(content);int reader=content.readerIndex(),writer=content.writerIndex(),refs=content.refCnt();
  try{
   byte[] captured=FoundationsHandshakeCapture.encode(packet,connection);
   check(Arrays.equals(expected,captured),kind+": capture differs");
   check(reader==content.readerIndex()&&writer==content.writerIndex(),kind+": observation moved packet indices");
   check(refs==content.refCnt(),kind+": observation changed reference count");
   check(Arrays.equals(captured,FoundationsHandshakeCapture.encode(packet,connection)),kind+": repeated observation differs");
   captured[0]^=1;check(Arrays.equals(expected,bytes(content)),kind+": capture shares packet storage");
   var transcript=new FoundationsHandshakeCapture.Transcript();transcript.started=true;
   FoundationsHandshakeCapture.captureVanilla(transcript,packet,connection);
   check(transcript.invalid.isEmpty(),kind+": valid registry rejected");
   byte[] actual=wire(packet);check(Arrays.equals(expected,actual),kind+": registry_data corrupted before client encoder");
   System.out.println(kind+" registry: expected="+expected.length+" forwarded="+actual.length);
  }finally{packet.release();}
 }
 public static void main(String[] args){
  var channel=new EmbeddedChannel();
  var connection=new MinecraftConnection(channel,null){@Override public ProtocolVersion getProtocolVersion(){return VERSION;}};
  try{
   registry(connection,Unpooled.buffer(),"Heap");
   registry(connection,Unpooled.directBuffer(),"Direct");
   var composite=Unpooled.compositeBuffer();composite.addComponent(true,Unpooled.buffer(12).writeZero(12));composite.addComponent(true,Unpooled.buffer(100).writeZero(100));composite.clear();
   registry(connection,composite,"Composite");
   for(MinecraftPacket packet:List.of(new TagsUpdatePacket(Map.of("minecraft:block",Map.of("minecraft:mineable/pickaxe",new int[]{1,2,3}))),new ActiveFeaturesPacket(new Key[]{Key.key("minecraft:vanilla")}),new KnownPacksPacket(List.of(new KnownPacksPacket.KnownPack("minecraft","core","1.21.1"))))){
    byte[] expected=wire(packet);var transcript=new FoundationsHandshakeCapture.Transcript();transcript.started=true;
    FoundationsHandshakeCapture.captureVanilla(transcript,packet,connection);
    check(transcript.invalid.isEmpty(),packet.getClass().getSimpleName()+": capture rejected");
    check(Arrays.equals(expected,wire(packet)),packet.getClass().getSimpleName()+": observation changed client bytes");
   }
   MinecraftPacket broken=new MinecraftPacket(){
    public void decode(ByteBuf b,ProtocolUtils.Direction d,ProtocolVersion v){}
    public void encode(ByteBuf b,ProtocolUtils.Direction d,ProtocolVersion v){throw new IllegalStateException("unsupported fixture encoding");}
    public boolean handle(MinecraftSessionHandler handler){return false;}
   };
   var transcript=new FoundationsHandshakeCapture.Transcript();transcript.started=true;
   FoundationsHandshakeCapture.captureVanilla(transcript,broken,connection);
   check(!transcript.invalid.isEmpty(),"Capture error must mark replay unavailable");
   try{transcript.cursor();throw new AssertionError("Failed capture accepted for replay");}catch(IllegalStateException expected){checks++;}
   System.out.println("R19-HF1 packet integrity: PASS ("+checks+" checks)");
  }finally{channel.finishAndReleaseAll();}
 }
}
