package com.velocitypowered.proxy.connection.backend;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.util.ConnectionRequestResults;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.protocol.netty.MinecraftEncoder;
import com.velocitypowered.proxy.protocol.packet.PingIdentifyPacket;
import com.velocitypowered.proxy.server.VelocityRegisteredServer;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;

/** Uses Conduit's actual ping dispatch and CONFIG encoder. No Minecraft client required. */
public final class SeamlessPingSelfTest {
    static int checks;
    static void check(boolean ok,String reason){if(!ok)throw new AssertionError(reason);checks++;}
    static PingIdentifyPacket ping(int id){
        ByteBuf b=Unpooled.buffer().writeInt(id);
        try{var p=new PingIdentifyPacket();p.decode(b,ProtocolUtils.Direction.CLIENTBOUND,ProtocolVersion.MINECRAFT_1_21);return p;}
        finally{b.release();}
    }
    public static void main(String[] args)throws Exception{
        var encoder=new MinecraftEncoder(ProtocolUtils.Direction.SERVERBOUND);
        encoder.setProtocolVersion(ProtocolVersion.MINECRAFT_1_21);encoder.setState(StateRegistry.CONFIG);
        var channel=new EmbeddedChannel(encoder);
        var connection=new MinecraftConnection(channel,null);
        var registered=new VelocityRegisteredServer(null,new ServerInfo("worker-test",new InetSocketAddress("127.0.0.1",25567)));
        var backend=new VelocityServerConnection(registered,null,null,null){
            @Override public MinecraftConnection ensureConnected(){return connection;}
        };
        var result=new CompletableFuture<ConnectionRequestResults.Impl>();
        var handler=new SeamlessConfigSessionHandler(null,backend,result);
        try{
            // NeoForge 21.1.250 startConfiguration sends ping(0); handlePong(0)
            // invokes runConfiguration. The HF2 handler silently swallowed this.
            for(int id:new int[]{0,42,-1,Integer.MIN_VALUE,Integer.MAX_VALUE}){
                check(ping(id).handle(handler),"CONFIG ping "+id+" was ignored; NeoForge cannot advance");
                ByteBuf wire=channel.readOutbound();
                check(wire!=null,"No pong emitted for CONFIG ping "+id);
                try{
                    check(ProtocolUtils.readVarInt(wire)==5,"Response was not the 1.21.1 CONFIG pong packet");
                    check(wire.readInt()==id&&!wire.isReadable(),"Pong did not preserve the current backend challenge");
                }finally{wire.release();}
                check(channel.readOutbound()==null,"Duplicate pong emitted");
                check(!result.isDone(),"Ping must not commit or bypass configuration validation");
            }
            // Once rejected, even transport acknowledgements must stop.
            var rejected=SeamlessConfigSessionHandler.class.getDeclaredField("rejected");rejected.setAccessible(true);rejected.setBoolean(handler,true);
            check(ping(0).handle(handler),"Rejected ping should be absorbed");
            check(channel.readOutbound()==null,"Rejected configuration emitted a pong");
            System.out.println("R19-HF3 seamless configuration ping: PASS ("+checks+" checks)");
        }finally{channel.finishAndReleaseAll();}
    }
}
