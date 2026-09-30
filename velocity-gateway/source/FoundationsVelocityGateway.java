package com.foundations.cluster.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.slf4j.Logger;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * 0.0.6a.R19 physical-migration transport on Conduit seamless proxy core with emergency backend-loss redirect.
 *
 * Velocity/Conduit owns ingress and the player's long-lived client session.
 * Foundations keeps region/simulation-only movement separate from an explicit
 * physical player transfer. For a physical transfer, the Manager performs the
 * source/destination checkpoint protocol and this adapter requests the backend
 * connection; Conduit may absorb the 1.20.2+ destination Configuration phase
 * when its compatibility checks allow a seamless same-dimension switch.
 */
@Plugin(id="foundations_gateway", name="Foundations Gateway", version="0.0.6a.R19", authors={"Foundations"})
public final class FoundationsVelocityGateway {
    private final ProxyServer proxy;
    private final Logger log;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final String manager=env("FOUNDATIONS_CLUSTER_MANAGER_URL","http://127.0.0.1:8765");
    private final String token=env("FOUNDATIONS_CLUSTER_GATEWAY_TOKEN","");
    private volatile boolean shuttingDown;
    private final Map<UUID,Long> lastEmergency=new ConcurrentHashMap<>();
    private final Set<String> inFlight=ConcurrentHashMap.newKeySet();
    private final Set<UUID> emergencyInFlight=ConcurrentHashMap.newKeySet();

    @Inject public FoundationsVelocityGateway(ProxyServer proxy, Logger log){this.proxy=proxy;this.log=log;}

    @Subscribe public void onInit(ProxyInitializeEvent event){
        refreshTopology();
        proxy.getScheduler().buildTask(this,this::refreshTopology).repeat(1,TimeUnit.SECONDS).schedule();
        proxy.getScheduler().buildTask(this,this::heartbeat).repeat(2,TimeUnit.SECONDS).schedule();
        // Explicit physical migrations become PROXY_READY only after the Manager
        // has completed destination preload plus the final source checkpoint.
        // The proxy then owns only the backend connection step and commit/abort.
        proxy.getScheduler().buildTask(this,this::pollPhysicalHandoffs).repeat(200,TimeUnit.MILLISECONDS).schedule();
        log.info("Foundations Velocity Gateway 0.0.6a.R19 initialized in physical migration mode on Conduit 1.8.0 + Foundations NeoForge seamless bridge with emergency backend-loss redirect; manager={}",manager);
    }

    @Subscribe public void chooseInitial(PlayerChooseInitialServerEvent event){
        Player p=event.getPlayer();
        try{Class.forName("com.velocitypowered.proxy.connection.backend.FoundationsHandshakeCapture").getMethod("install",Player.class).invoke(null,p);}
        catch(Exception ex){log.error("NeoForge handshake capture unavailable: {}",ex.toString());}
        try{
            String response=post("/api/sessions/place","{\"playerId\":\""+esc(p.getUsername())+"\",\"sessionId\":\""+p.getUniqueId()+"\"}");
            String worker=jsonString(response,"anchorWorkerId");
            if(worker.isBlank()) worker=jsonString(response,"workerId");
            Optional<? extends RegisteredServer> server=proxy.getServer(worker);
            if(server.isPresent())event.setInitialServer(server.get());
            else log.warn("Placement selected anchor {}, but it is not registered in Velocity yet",worker);
        }catch(Exception ex){log.error("Foundations initial placement failed for {}: {}",p.getUsername(),ex.getMessage());}
    }

    @Subscribe public void disconnect(DisconnectEvent event){
        lastEmergency.remove(event.getPlayer().getUniqueId());
        try{post("/api/sessions/"+URLEncoder.encode(event.getPlayer().getUniqueId().toString(),StandardCharsets.UTF_8)+"/release","{}");}
        catch(Exception ignored){}
    }


    @Subscribe public void backendKicked(KickedFromServerEvent event){
        // A normal server kick must remain a normal kick. The Manager independently
        // verifies that the backend JVM is actually unavailable before it authorizes
        // an emergency redirect, so this path cannot replace graceful handoff logic.
        if(shuttingDown){
            event.setResult(KickedFromServerEvent.DisconnectPlayer.create(net.kyori.adventure.text.Component.text("Foundations is shutting down. Please reconnect shortly.")));return;
        }
        if(event.kickedDuringServerConnect()||event.kickedDuringLogin())return;
        Player player=event.getPlayer();UUID uuid=player.getUniqueId();
        long now=System.currentTimeMillis();
        if(now-lastEmergency.getOrDefault(uuid,0L)<10_000L || !emergencyInFlight.add(uuid))return;
        lastEmergency.put(uuid,now);
        String failed=event.getServer().getServerInfo().getName();
        try{
            String payload="{\"playerId\":\""+esc(player.getUsername())+"\",\"sessionId\":\""+uuid+"\",\"failedWorkerId\":\""+esc(failed)+"\",\"reason\":\"Velocity backend connection lost\"}";
            String response="";Exception last=null;
            for(int attempt=0;attempt<3;attempt++){
                try{response=post("/api/gateway/failover",payload);last=null;break;}
                catch(Exception ex){last=ex;if(String.valueOf(ex.getMessage()).contains("SHUTTING_DOWN")){shuttingDown=true;event.setResult(KickedFromServerEvent.DisconnectPlayer.create(net.kyori.adventure.text.Component.text("Foundations is shutting down.")));break;}if(attempt<2)try{Thread.sleep(125L);}catch(InterruptedException ie){Thread.currentThread().interrupt();break;}}
            }
            if(last!=null)return; // healthy backend / normal kick / no safe destination
            String dest=jsonString(response,"workerId");
            if(dest.isBlank()||dest.equals(failed))return;
            Optional<? extends RegisteredServer> target=proxy.getServer(dest);
            if(target.isEmpty()){refreshTopology();target=proxy.getServer(dest);}
            if(target.isEmpty()){log.error("Emergency failover selected {}, but Velocity has no registered backend",dest);return;}
            event.setResult(KickedFromServerEvent.RedirectPlayer.create(target.get()));
            log.warn("Emergency backend-loss redirect armed for {}: {} -> {}",player.getUsername(),failed,dest);
        }finally{emergencyInFlight.remove(uuid);}
    }


    private void refreshTopology(){
        try{
            String body=get("/api/gateway/topology");
            shuttingDown="SHUTTING_DOWN".equals(jsonString(body,"lifecycle"));
            for(String object:objects(body,"workers")){
                String id=jsonString(object,"id"),host=jsonString(object,"host");int port=(int)jsonLong(object,"port",0);
                if(id.isBlank()||host.isBlank()||port<=0)continue;
                if(proxy.getServer(id).isEmpty())proxy.registerServer(new ServerInfo(id,new InetSocketAddress(host,port)));
            }
        }catch(Exception ex){log.debug("Topology refresh failed: {}",ex.getMessage());}
    }


    private void pollPhysicalHandoffs(){
        if(shuttingDown)return;
        try{
            String body=get("/api/gateway/handoffs");
            for(String object:objects(body,"handoffs")){
                String id=jsonString(object,"id"),playerName=jsonString(object,"playerId"),dest=jsonString(object,"destinationWorkerId");
                if(id.isBlank()||!inFlight.add(id))continue;
                Optional<? extends Player> player=proxy.getPlayer(playerName);Optional<? extends RegisteredServer> server=proxy.getServer(dest);
                if(player.isEmpty()||server.isEmpty()){abort(id,"player or destination backend unavailable at proxy");inFlight.remove(id);continue;}
                try{post("/api/gateway/handoffs/committing","{\"handoffId\":\""+esc(id)+"\"}");}catch(Exception ex){inFlight.remove(id);continue;}
                long started=System.currentTimeMillis();
                trace(id,"SWITCH_REQUEST",0,"destination="+dest);
                player.get().createConnectionRequest(server.get()).connect().whenComplete((result,err)->{
                    try{
                        if(err==null&&result!=null&&result.isSuccessful()){
                            trace(id,"SWITCH_ACCEPTED",System.currentTimeMillis()-started,"destination="+dest);
                            post("/api/gateway/handoffs/commit","{\"handoffId\":\""+esc(id)+"\",\"workerId\":\""+esc(dest)+"\"}");
                            trace(id,"COMMIT_ACK",System.currentTimeMillis()-started,"Manager committed anchor and simulation");
                        }
                        else {String failure=err!=null?String.valueOf(err.getMessage()):result==null?"No connection result":result.getReasonComponent().map(c->c instanceof net.kyori.adventure.text.TextComponent tc?tc.content():c.toString()).orElse("Destination configuration or connect rejected");trace(id,"SWITCH_REJECTED",System.currentTimeMillis()-started,failure);abort(id,"Backend switch rejected: "+failure);}
                    }catch(Exception ex){log.error("Could not finalize physical handoff {}: {}",id,ex.getMessage());}
                    finally{inFlight.remove(id);}
                });
            }
        }catch(Exception ex){log.debug("Physical handoff poll failed: {}",ex.getMessage());}
    }

    private void trace(String id,String stage,long elapsed,String detail){
        log.info("[Foundations transfer] handoff={} stage={} elapsedMs={} {}",id,stage,elapsed,detail);
        try{post("/api/gateway/trace","{\"handoffId\":\""+esc(id)+"\",\"stage\":\""+esc(stage)+"\",\"elapsedMillis\":"+elapsed+",\"detail\":\""+esc(detail)+"\"}");}catch(Exception ignored){}
    }

    private void abort(String id,String error){try{post("/api/gateway/handoffs/abort","{\"handoffId\":\""+esc(id)+"\",\"error\":\""+esc(error)+"\"}");}catch(Exception ignored){}}

    private void heartbeat(){
        try{String response=post("/api/gateway/heartbeat","{\"transport\":\"conduit-1.8.0-foundations-r19-guarded-replay\",\"pluginVersion\":\"0.0.6a.R19\",\"sessionModel\":\"persistent-session\",\"players\":"+proxy.getPlayerCount()+"}");shuttingDown="SHUTTING_DOWN".equals(jsonString(response,"lifecycle"));}
        catch(Exception ignored){}
    }

    private String get(String path)throws Exception{return request("GET",path,"");}
    private String post(String path,String body)throws Exception{return request("POST",path,body);}
    private String request(String method,String path,String body)throws Exception{
        HttpRequest.Builder b=HttpRequest.newBuilder(URI.create(manager+path)).timeout(Duration.ofSeconds(3)).header("X-Foundations-Gateway-Token",token).header("Content-Type","application/json");
        b.method(method,method.equals("GET")?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> r=http.send(b.build(),HttpResponse.BodyHandlers.ofString());if(r.statusCode()>=300)throw new IllegalStateException("HTTP "+r.statusCode()+" "+r.body());return r.body();
    }

    private static List<String> objects(String json,String arrayKey){
        String needle="\""+arrayKey+"\":[";int i=json.indexOf(needle);if(i<0)return List.of();i+=needle.length();List<String> out=new ArrayList<>();int depth=0,start=-1;boolean str=false,esc=false;
        for(;i<json.length();i++){char c=json.charAt(i);if(str){if(esc)esc=false;else if(c=='\\')esc=true;else if(c=='\"')str=false;continue;}if(c=='\"'){str=true;continue;}if(c=='{'){if(depth++==0)start=i;}else if(c=='}'){if(--depth==0&&start>=0){out.add(json.substring(start,i+1));start=-1;}}else if(c==']'&&depth==0)break;}return out;
    }
    private static String jsonString(String body,String key){String n="\""+key+"\":\"";int i=body.indexOf(n);if(i<0)return "";i+=n.length();StringBuilder o=new StringBuilder();boolean e=false;for(;i<body.length();i++){char c=body.charAt(i);if(e){o.append(c);e=false;}else if(c=='\\')e=true;else if(c=='\"')break;else o.append(c);}return o.toString();}
    private static long jsonLong(String body,String key,long d){String n="\""+key+"\":";int i=body.indexOf(n);if(i<0)return d;i+=n.length();int j=i;while(j<body.length()&&"-0123456789".indexOf(body.charAt(j))>=0)j++;try{return Long.parseLong(body.substring(i,j));}catch(Exception e){return d;}}
    private static String env(String k,String d){String v=System.getenv(k);return v==null||v.isBlank()?d:v;}
    private static String esc(String s){return s==null?"":s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r");}
}
