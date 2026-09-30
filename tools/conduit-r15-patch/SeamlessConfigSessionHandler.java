package com.velocitypowered.proxy.connection.backend;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.MinecraftSessionHandler;
import com.velocitypowered.proxy.connection.util.ConnectionMessages;
import com.velocitypowered.proxy.connection.util.ConnectionRequestResults;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.protocol.packet.DisconnectPacket;
import com.velocitypowered.proxy.protocol.packet.KeepAlivePacket;
import com.velocitypowered.proxy.protocol.packet.PingIdentifyPacket;
import com.velocitypowered.proxy.protocol.packet.PluginMessagePacket;
import com.velocitypowered.proxy.protocol.packet.config.CodeOfConductAcceptPacket;
import com.velocitypowered.proxy.protocol.packet.config.CodeOfConductPacket;
import com.velocitypowered.proxy.protocol.packet.config.FinishedUpdatePacket;
import com.velocitypowered.proxy.protocol.packet.config.KnownPacksPacket;
import com.velocitypowered.proxy.protocol.util.PluginMessageUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Foundations R19 guarded NeoForge handshake replay over Conduit 1.8.0.
 *
 * The real client remains in PLAY. Verify destination configuration against its
 * completed login transcript, replay captured capabilities, and answer backend
 * transport challenges locally. Never send CONFIG packets to the PLAY client.
 */
class SeamlessConfigSessionHandler implements MinecraftSessionHandler {
  private static final org.slf4j.Logger TRACE = org.slf4j.LoggerFactory.getLogger("FoundationsConfigTrace");
  private final long startedNanos = System.nanoTime();
  private String lastTask = "configuration entry";
  private int tracedPackets;
  private void trace(String task) {
    lastTask = task;
    if (++tracedPackets <= 96) TRACE.info("backend={} elapsedMs={} task={}", serverConn.getServer().getServerInfo().getName(), (System.nanoTime()-startedNanos)/1_000_000, task);
  }
  private FoundationsHandshakeCapture.Cursor captured;
  private boolean rejected;
  private final VelocityServer server;
  private final VelocityServerConnection serverConn;
  private final CompletableFuture<ConnectionRequestResults.Impl> resultFuture;

  SeamlessConfigSessionHandler(VelocityServer server, VelocityServerConnection serverConn,
      CompletableFuture<ConnectionRequestResults.Impl> resultFuture) {
    this.server = server;
    this.serverConn = serverConn;
    this.resultFuture = resultFuture;
    trace("CONFIG_ENTER phase=" + serverConn.getPhase());
  }

  public boolean beforeHandle() {
    if (!serverConn.isActive()) {
      serverConn.disconnect();
      return true;
    }
    return false;
  }

  public boolean handle(KnownPacksPacket packet) {
    if(!verifyVanilla(packet))return true;
    trace("KNOWN_PACKS count="+packet.getPacks().size()+"");
    List<KnownPacksPacket.KnownPack> clientPacks = serverConn.getPlayer().getClientKnownPacks();
    List<KnownPacksPacket.KnownPack> intersection = new ArrayList<>();
    if (clientPacks != null) {
      for (KnownPacksPacket.KnownPack pack : packet.getPacks()) {
        if (clientPacks.contains(pack)) intersection.add(pack);
      }
    }
    serverConn.ensureConnected().write(new KnownPacksPacket(intersection));
    return true;
  }

  public boolean handle(CodeOfConductPacket packet) {
    serverConn.ensureConnected().write(CodeOfConductAcceptPacket.INSTANCE);
    return true;
  }

  public boolean handle(KeepAlivePacket packet) {
    trace("KEEPALIVE");
    serverConn.ensureConnected().write(packet);
    return true;
  }

  public boolean handle(PingIdentifyPacket packet) {
    if(rejected)return true;
    // NeoForge 21.1.250 startConfiguration sends ping(0), then waits for
    // handlePong(0) before runConfiguration. This is distinct from keepalive.
    // Conduit maps this packet to CONFIG pong in the SERVERBOUND encoder.
    // Echo the current backend challenge, never a captured login ping ID.
    trace("CONFIG_PING_PONG "+packet);
    serverConn.ensureConnected().write(packet);
    return true;
  }

  public void handleGeneric(com.velocitypowered.proxy.protocol.MinecraftPacket packet) {
    if(!rejected)trace("CONFIG_UNHANDLED packet="+packet.getClass().getSimpleName());
  }

  public boolean handle(PluginMessagePacket packet) {
    trace("CONFIG_PAYLOAD channel="+packet.getChannel()+"");
    if(rejected)return true;
    String channel=packet.getChannel();
    if(channel.equals("minecraft:register")||channel.equals("minecraft:unregister"))return true;
    if(!PluginMessageUtil.isMcBrand(packet)){
      try{
        if(captured==null)captured=FoundationsHandshakeCapture.cursor(serverConn.getPlayer());
        if(!FoundationsHandshakeCapture.PASSIVE.contains(channel)&&!FoundationsHandshakeCapture.REPLIES.containsKey(channel))throw new IllegalStateException("Unsupported configuration task: "+channel);
        captured.check(channel,FoundationsHandshakeCapture.payload(packet));
        String reply=FoundationsHandshakeCapture.REPLIES.get(channel);
        if(reply!=null)sendCaptured(reply);
        if(channel.equals("neoforge:network"))sendCaptured("minecraft:register");
        trace("CAPTURE_VERIFIED channel="+channel+(reply==null?"":" reply="+reply));
      }catch(Exception ex){reject(ex.getMessage());}
      return true;
    }
    // Preserve Conduit 1.8.0's safe brand special-case. Everything else stays
    // absorbed because the client is still in PLAY and cannot receive arbitrary
    // CONFIGURATION-state payloads.

    if (PluginMessageUtil.isMcBrand(packet)) {
      MinecraftConnection client = serverConn.getPlayer().getConnection();
      PluginMessagePacket rewritten = PluginMessageUtil.rewriteMinecraftBrand(
          packet,
          server.getVersion(),
          client.getProtocolVersion(),
          server.getConfiguration().getServerBrand(),
          server.getConfiguration().getProxyBrandCustom(),
          server.getConfiguration().getBackendBrandCustom(),
          serverConn.getServer().getServerInfo().getName(),
          ProtocolVersion.getVersionByName(server.getConfiguration().getMinimumVersion()).getVersionIntroducedIn());
      client.write(rewritten);
    }
    return true;
  }

  private void sendCaptured(String channel){
    for(byte[] bytes:captured.replies(channel))serverConn.ensureConnected().write(new PluginMessagePacket(channel,io.netty.buffer.Unpooled.wrappedBuffer(bytes.clone())));
  }
  private boolean verifyVanilla(com.velocitypowered.proxy.protocol.MinecraftPacket packet){
    if(rejected)return false;
    try{if(captured!=null)captured.check("vanilla:"+packet.getClass().getSimpleName(),FoundationsHandshakeCapture.encode(packet,serverConn.getPlayer().getConnection()));return true;}
    catch(Exception ex){reject(ex.getMessage());return false;}
  }
  public boolean handle(com.velocitypowered.proxy.protocol.packet.config.RegistrySyncPacket p){verifyVanilla(p);return true;}
  public boolean handle(com.velocitypowered.proxy.protocol.packet.config.TagsUpdatePacket p){verifyVanilla(p);return true;}
  public boolean handle(com.velocitypowered.proxy.protocol.packet.config.ActiveFeaturesPacket p){verifyVanilla(p);return true;}
  private void reject(String reason){
    rejected=true;trace("CONFIG_REJECTED reason="+reason);
    resultFuture.complete(ConnectionRequestResults.forDisconnect(DisconnectPacket.create(net.kyori.adventure.text.Component.text("Foundations seamless: "+reason),serverConn.getPlayer().getProtocolVersion(),StateRegistry.CONFIG),serverConn.getServer()));
    serverConn.disconnect();
  }

  public boolean handle(FinishedUpdatePacket packet) {
    if(rejected)return true;
    try{if(captured!=null)captured.finish();}catch(Exception ex){reject(ex.getMessage());return true;}
    trace("CONFIG_FINISH_ACK_AND_PLAY");
    MinecraftConnection connection = serverConn.ensureConnected();
    connection.write(FinishedUpdatePacket.INSTANCE);
    connection.setActiveSessionHandler(StateRegistry.PLAY,
        new TransitionSessionHandler(server, serverConn, resultFuture));
    return true;
  }

  public boolean handle(DisconnectPacket packet) {
    trace("BACKEND_DISCONNECT");
    serverConn.disconnect();
    resultFuture.complete(ConnectionRequestResults.forDisconnect(packet, serverConn.getServer()));
    return true;
  }

  public void disconnected() {
    TRACE.warn("backend={} configuration disconnected after {}ms; lastTask={}",serverConn.getServer().getServerInfo().getName(),(System.nanoTime()-startedNanos)/1_000_000,lastTask);
    resultFuture.complete(ConnectionRequestResults.forDisconnect(
        ConnectionMessages.INTERNAL_SERVER_CONNECTION_ERROR, serverConn.getServer()));
  }
}
