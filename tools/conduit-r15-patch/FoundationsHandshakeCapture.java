package com.velocitypowered.proxy.connection.backend;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.network.Connections;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.protocol.MinecraftPacket;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import com.velocitypowered.proxy.protocol.packet.PluginMessagePacket;
import com.velocitypowered.proxy.protocol.packet.config.FinishedUpdatePacket;
import com.velocitypowered.proxy.protocol.packet.config.RegistrySyncPacket;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.util.AttributeKey;
import java.util.*;

/** Per-connection, bounded capture of the client's real completed configuration.
 * Never invents client capabilities. Unknown tasks or changed data reject the switch.
 * Buffers are copied without changing reader indices; no retained Netty ownership.
 */
public final class FoundationsHandshakeCapture {
    private static final AttributeKey<Transcript> KEY=AttributeKey.valueOf("foundations.r19.handshake");
    static final int MAX_BYTES=16*1024*1024, MAX_PACKETS=8192;
    static final String FROZEN_START="neoforge:frozen_registry_sync_start",FROZEN_DATA="neoforge:frozen_registry",FROZEN_END="neoforge:frozen_registry_sync_completed";
    static final Set<String> PASSIVE=Set.of("neoforge:network","neoforge:frozen_registry_sync_start","neoforge:frozen_registry","neoforge:config_file");
    static final Map<String,String> REPLIES=Map.of(
        "neoforge:register","neoforge:register",
        "neoforge:frozen_registry_sync_completed","neoforge:frozen_registry_sync_completed",
        "c:version","c:version","c:register","c:register",
        "neoforge:known_registry_data_maps","neoforge:known_registry_data_maps_reply",
        "neoforge:extensible_enum_data","neoforge:extensible_enum_ack",
        "neoforge:feature_flags","neoforge:feature_flags_ack");
    static final Set<String> VANILLA=Set.of("RegistrySyncPacket","TagsUpdatePacket","ActiveFeaturesPacket","KnownPacksPacket");
    static final class Transcript {
        final Map<String,List<byte[]>> requests=new HashMap<>(),replies=new HashMap<>();
        int bytes,packets;boolean started,complete;String invalid="";
        synchronized void put(boolean outbound,String channel,byte[] bytes){
            if(complete||!invalid.isEmpty())return;
            if(bytes.length>MAX_BYTES||this.bytes+bytes.length>MAX_BYTES||++packets>MAX_PACKETS){invalid="Configuration capture exceeded memory limit";return;}
            String key=outbound?requestKey(channel,bytes):channel;
            if(outbound&&(channel.equals(FROZEN_START)||channel.equals(FROZEN_DATA)||channel.equals(FROZEN_END))&&requests.containsKey(key))throw new IllegalStateException("Duplicate captured registry task: "+key);
            byte[] value=outbound?canonicalRequest(channel,bytes):bytes.clone();
            this.bytes+=bytes.length;(outbound?requests:replies).computeIfAbsent(key,k->new ArrayList<>()).add(value);
        }
        synchronized Cursor cursor(){
            if(!complete||!invalid.isEmpty()||!replies.containsKey("neoforge:register"))throw new IllegalStateException(invalid.isEmpty()?"No completed NeoForge client handshake; reconnect before seamless testing":invalid);
            return new Cursor(this);
        }
    }
    static final class Cursor {
        final Transcript transcript;final Map<String,Integer> used=new HashMap<>();
        Cursor(Transcript t){transcript=t;}
        void check(String key,byte[] bytes){
            String channel=key;
            if(channel.equals(FROZEN_DATA)&&used.getOrDefault(FROZEN_START,0)!=1)throw new IllegalStateException("Frozen registry received before registry manifest");
            if(channel.equals(FROZEN_END)){
                if(used.getOrDefault(FROZEN_START,0)!=1)throw new IllegalStateException("Frozen registry completion before registry manifest");
                for(var e:transcript.requests.entrySet())if(e.getKey().startsWith(FROZEN_DATA+"[")&&used.getOrDefault(e.getKey(),0)!=e.getValue().size())throw new IllegalStateException("Destination omitted registry before completion: "+e.getKey());
            }
            key=requestKey(channel,bytes);bytes=canonicalRequest(channel,bytes);
            List<byte[]> expected=transcript.requests.get(key);int n=used.getOrDefault(key,0);
            if(expected==null||n>=expected.size())throw new IllegalStateException("Destination configuration differs: "+key+" (entry "+n+"; unexpected or duplicate)");
            if(!Arrays.equals(expected.get(n),bytes)){
                String detail;
                if(channel.equals(FROZEN_START)){
                    var before=registryNames(expected.get(n));var after=registryNames(bytes);
                    var missing=new TreeSet<>(before);missing.removeAll(after);var added=new TreeSet<>(after);added.removeAll(before);
                    detail="missing="+shortNames(missing)+" added="+shortNames(added);
                }else if(channel.equals(FoundationsTagConfiguration.KEY))detail=FoundationsTagConfiguration.difference(expected.get(n),bytes);
                else if(FoundationsNeoForgeConfiguration.supports(channel))detail=FoundationsNeoForgeConfiguration.difference(channel,expected.get(n),bytes);
                else detail="bytes="+expected.get(n).length+"/"+bytes.length+" sha256="+fingerprint(expected.get(n))+"/"+fingerprint(bytes);
                throw new IllegalStateException("Destination configuration differs: "+key+" (entry "+n+"; "+detail+")");
            }
            used.put(key,n+1);
        }
        List<byte[]> replies(String channel){
            List<byte[]> bytes=transcript.replies.get(channel);
            if(bytes==null||bytes.isEmpty())throw new IllegalStateException("Client never acknowledged "+channel);
            return bytes;
        }
        void finish(){
            for(var e:transcript.requests.entrySet())if(used.getOrDefault(e.getKey(),0)!=e.getValue().size())throw new IllegalStateException("Destination omitted configuration: "+e.getKey());
        }
    }
    // In NeoForge 21.1.250 the start list is consumed as a set. RegistrySnapshot
    // already sorts IDs and aliases: keep its entire bytes exact, and match each
    // packet by registry name, never by JVM-dependent registry iteration order.
    static String requestKey(String channel,byte[] raw){
        if(!channel.equals(FROZEN_DATA))return channel;
        var input=Unpooled.wrappedBuffer(raw);
        try{return channel+"["+readRegistryName(input)+"]";}
        catch(RuntimeException ex){throw new IllegalStateException("Invalid frozen registry identity",ex);}
        finally{input.release();}
    }
    static byte[] canonicalRequest(String channel,byte[] raw){
        if(channel.equals("neoforge:network"))return canonicalNetwork(raw);
        if(channel.equals(FoundationsTagConfiguration.KEY))return FoundationsTagConfiguration.canonical(raw);
        if(FoundationsNeoForgeConfiguration.supports(channel))return FoundationsNeoForgeConfiguration.canonical(channel,raw);
        if(!channel.equals(FROZEN_START))return raw.clone();
        var names=registryNames(raw);var out=Unpooled.buffer();
        try{ProtocolUtils.writeVarInt(out,names.size());for(String name:names)ProtocolUtils.writeString(out,name);byte[] bytes=new byte[out.readableBytes()];out.getBytes(out.readerIndex(),bytes);return bytes;}
        finally{out.release();}
    }
    static SortedSet<String> registryNames(byte[] raw){
        var input=Unpooled.wrappedBuffer(raw);
        try{
            int count=ProtocolUtils.readVarInt(input);if(count<0||count>MAX_PACKETS)throw new IllegalStateException("Invalid registry manifest count");
            SortedSet<String> names=new TreeSet<>();
            for(int i=0;i<count;i++)if(!names.add(readRegistryName(input)))throw new IllegalStateException("Duplicate registry name in manifest");
            if(input.isReadable())throw new IllegalStateException("Trailing registry manifest data");
            return names;
        }catch(IllegalStateException ex){throw ex;}
        catch(RuntimeException ex){throw new IllegalStateException("Invalid registry manifest encoding",ex);}
        finally{input.release();}
    }
    private static String readRegistryName(io.netty.buffer.ByteBuf input){
        String name=ProtocolUtils.readString(input);
        if(!name.matches("[a-z0-9_.-]+:[a-z0-9/._-]+"))throw new IllegalStateException("Invalid registry name");
        return name;
    }
    private static String shortNames(SortedSet<String> names){return names.stream().limit(8).toList()+(names.size()>8?" (+"+(names.size()-8)+" more)":"");}
    private static String fingerprint(byte[] bytes){
        try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)).substring(0,12);}
        catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException("SHA-256 unavailable",ex);}
    }
    // NeoForge 21.1.250 serializes maps with JVM-dependent iteration order.
    // Normalize only that documented map structure, retaining every channel/version.
    static byte[] canonicalNetwork(byte[] raw){
        var input=Unpooled.wrappedBuffer(raw);var output=Unpooled.buffer();
        try{
            Map<Integer,Map<String,List<String>>> protocols=new TreeMap<>();
            int size=ProtocolUtils.readVarInt(input);if(size<0||size>16)throw new IllegalStateException("Invalid network protocol count");
            for(int i=0;i<size;i++){
                int protocol=ProtocolUtils.readVarInt(input),count=ProtocolUtils.readVarInt(input);if(count<0||count>32768)throw new IllegalStateException("Invalid network channel count");
                Map<String,List<String>> channels=new TreeMap<>();
                for(int j=0;j<count;j++){
                    String key=ProtocolUtils.readString(input),id=ProtocolUtils.readString(input),version=ProtocolUtils.readString(input);
                    if(channels.put(key,List.of(id,version))!=null)throw new IllegalStateException("Duplicate network channel");
                }
                if(protocols.put(protocol,channels)!=null)throw new IllegalStateException("Duplicate network protocol");
            }
            if(input.isReadable())throw new IllegalStateException("Trailing network negotiation data");
            ProtocolUtils.writeVarInt(output,protocols.size());for(var p:protocols.entrySet()){
                ProtocolUtils.writeVarInt(output,p.getKey());ProtocolUtils.writeVarInt(output,p.getValue().size());
                for(var c:p.getValue().entrySet()){ProtocolUtils.writeString(output,c.getKey());ProtocolUtils.writeString(output,c.getValue().get(0));ProtocolUtils.writeString(output,c.getValue().get(1));}
            }
            byte[] bytes=new byte[output.readableBytes()];output.readBytes(bytes);return bytes;
        }finally{input.release();output.release();}
    }
    public static void install(Player player){
        if(!(player instanceof ConnectedPlayer p))return;
        MinecraftConnection connection=p.getConnection();Channel ch=connection.getChannel();
        Runnable install=()->{
            if(ch.pipeline().get("foundations-handshake-capture")!=null)return;
            ch.attr(KEY).set(new Transcript());
            ch.pipeline().addBefore(Connections.HANDLER,"foundations-handshake-capture",new ChannelDuplexHandler(){
                private Transcript current(){return ch.attr(KEY).get();}
                @Override public void write(ChannelHandlerContext ctx,Object msg,ChannelPromise promise)throws Exception{
                    if(connection.getState()==StateRegistry.CONFIG){
                        Transcript t=current();
                        if(msg instanceof PluginMessagePacket p&&p.getChannel().equals("neoforge:register")){
                            if(t.complete){t=new Transcript();ch.attr(KEY).set(t);}t.started=true;
                        }
                        if(t.started&&msg instanceof PluginMessagePacket p&&!p.getChannel().startsWith("minecraft:")){try{t.put(true,p.getChannel(),payload(p));}catch(Exception ex){t.invalid="Unsupported initial configuration encoding: "+p.getChannel();}}
                        else if(t.started&&msg instanceof MinecraftPacket packet&&VANILLA.contains(msg.getClass().getSimpleName()))captureVanilla(t,packet,connection);
                    }
                    super.write(ctx,msg,promise);
                }
                @Override public void channelRead(ChannelHandlerContext ctx,Object msg)throws Exception{
                    Transcript t=current();
                    if(connection.getState()==StateRegistry.CONFIG&&t.started){
                        if(msg instanceof PluginMessagePacket p)t.put(false,p.getChannel(),payload(p));
                        if(msg instanceof FinishedUpdatePacket)synchronized(t){t.complete=true;}
                    }
                    super.channelRead(ctx,msg);
                }
            });
        };
        if(ch.eventLoop().inEventLoop())install.run();else ch.eventLoop().submit(install).syncUninterruptibly();
    }
    static Cursor cursor(ConnectedPlayer player){Transcript t=player.getConnection().getChannel().attr(KEY).get();if(t==null)throw new IllegalStateException("Handshake capture missing; reconnect");return t.cursor();}
    static byte[] payload(PluginMessagePacket p){byte[] b=new byte[p.content().readableBytes()];p.content().getBytes(p.content().readerIndex(),b);return b;}
    static void captureVanilla(Transcript transcript,MinecraftPacket packet,MinecraftConnection connection){
        // Observation must never break the real login. An unsupported capture disables
        // seamless replay for this connection, while normal configuration continues.
        try{transcript.put(true,"vanilla:"+packet.getClass().getSimpleName(),encode(packet,connection));}
        catch(Exception ex){transcript.invalid="Unsupported initial configuration encoding: "+packet.getClass().getSimpleName();}
    }
    static byte[] encode(MinecraftPacket packet,MinecraftConnection connection){
        if(packet instanceof RegistrySyncPacket registry){
            // Conduit 1.8.0's encode uses writeBytes(content()), which advances the
            // source reader index. Copy its raw payload with absolute reads instead.
            // Calling encode here drained the original registry_data before delivery.
            var content=registry.content();byte[] bytes=new byte[content.readableBytes()];
            content.getBytes(content.readerIndex(),bytes);return bytes;
        }
        var buffer=Unpooled.buffer();
        try{packet.encode(buffer,ProtocolUtils.Direction.CLIENTBOUND,connection.getProtocolVersion());byte[] bytes=new byte[buffer.readableBytes()];buffer.getBytes(buffer.readerIndex(),bytes);return bytes;}
        finally{buffer.release();}
    }
}
