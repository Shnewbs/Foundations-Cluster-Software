package com.foundations.cluster.node.entity;

import com.foundations.cluster.node.FoundationsClusterNode;
import com.foundations.cluster.playtest.FoundationsPlaytest;
import com.foundations.cluster.protocol.EntityViewsWire;
import com.foundations.cluster.protocol.EntityViewsWire.*;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import net.minecraft.commands.*;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;

/** Opt-in live paired views, not entity ownership transfer. All world reads/ticket
 * operations/packet publication run on the server thread. HTTP never does. */
public final class PairedEntityViews implements AutoCloseable {
    private static final Map<MinecraftServer,PairedEntityViews> SERVICES=new WeakHashMap<>();
    private static final TicketType<UUID> TICKET=TicketType.create("foundations_paired_entity_view",Comparator.comparing(UUID::toString));
    private final MinecraftServer server;
    private final String worker=System.getenv().getOrDefault("FOUNDATIONS_CLUSTER_WORKER_ID","standalone");
    private final String token=System.getenv().getOrDefault("FOUNDATIONS_CLUSTER_AGENT_TOKEN","");
    private final URI endpoint=URI.create(System.getenv().getOrDefault("FOUNDATIONS_CLUSTER_MANAGER_URL","http://127.0.0.1:8765")+"/api/agent/entity-views");
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ThreadPoolExecutor io=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(8),r->{Thread t=new Thread(r,"foundations-entity-views-http");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private record ClientView(ServerPlayer player,EntityViewRenderer renderer) {}
    private record Ticket(ServerLevel level,ChunkPos chunk) {}
    private final Map<UUID,ClientView> clients=new HashMap<>();
    private final Map<UUID,Ticket> tickets=new HashMap<>();
    private final Set<UUID> disabledHere=new HashSet<>();
    private List<Pair> owned=List.of();
    private UUID managerBoot;
    private long sequence,lastAttempt,lastValid;
    private boolean inFlight,closed;
    private String lastProblem="not connected to Manager";
    private int unavailable;
    private PairedEntityViews(MinecraftServer server){this.server=server;}
    private static synchronized PairedEntityViews get(MinecraftServer server){return SERVICES.computeIfAbsent(server,PairedEntityViews::new);}
    public static void tickServer(MinecraftServer server){get(server).tick();}
    public static synchronized void stopServer(MinecraftServer server){var s=SERVICES.remove(server);if(s!=null)s.close();}
    private static long now(){return System.nanoTime()/1000000L;}
    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root=Commands.literal("foundations_entities").requires(s->s.hasPermission(2));
        root.then(Commands.literal("pair").then(Commands.argument("entity",EntityArgument.entity()).executes(c->{
            return get(c.getSource().getServer()).pair(c.getSource(),EntityArgument.getEntity(c,"entity"));})));
        root.then(Commands.literal("unpair").then(Commands.argument("uuid",StringArgumentType.word()).executes(c->
            get(c.getSource().getServer()).unpair(c.getSource(),StringArgumentType.getString(c,"uuid")))));
        for(boolean value:new boolean[]{true,false})root.then(Commands.literal("view").then(Commands.literal(value?"on":"off").executes(c->
            get(c.getSource().getServer()).view(c.getSource(),value))));
        root.then(Commands.literal("list").executes(c->get(c.getSource().getServer()).list(c.getSource())));
        root.then(Commands.literal("status").executes(c->get(c.getSource().getServer()).status(c.getSource())));
        dispatcher.register(root);
    }
    private ServerPlayer operator(CommandSourceStack source)throws Exception {
        var p=source.getPlayerOrException();
        if(!source.hasPermission(2)||!FoundationsPlaytest.isConnectedPlayer(p))throw new IllegalStateException("A connected real operator is required");
        if(token.isBlank()||FoundationsClusterNode.entityViewProcessInstance().isBlank())throw new IllegalStateException("A registered Cluster Node is required");
        return p;
    }
    private int pair(CommandSourceStack source,Entity entity) {
        try {
            var player=operator(source);
            if(entity.level()!=player.level()||player.distanceToSqr(entity)>16*16||player.serverLevel().getEntity(entity.getUUID())!=entity)
                throw new IllegalArgumentException("Select one loaded source entity within 16 blocks");
            if(!FoundationsClusterNode.entityViewSourceReady(player.serverLevel(),entity.blockPosition()))
                throw new IllegalStateException("Pair on the ACTIVE, hydrated region owner");
            State initial=EntityViewPackets.capture(entity,new UUID(0,0));
            String bundle=base64(EntityViewsWire.encodeRequest(new Request(1,List.of(initial),List.of())));
            command(source,player,"pair",",\"bundle\":"+q(bundle),response->{
                message(player,"Paired "+entity.getUUID()+" on "+worker+". Remote copies are display-only: no damage/use/pickup. Enable /foundations_entities view on to test.");lastAttempt=0;
            });return 1;
        }catch(Exception e){return error(source,e);}
    }
    private int unpair(CommandSourceStack source,String id) {
        try{var p=operator(source);UUID uuid=UUID.fromString(id);command(source,p,"unpair",",\"entity\":"+q(uuid.toString()),r->message(p,"Unpaired view "+uuid+". The real entity was not changed."));return 1;}
        catch(Exception e){return error(source,e);}
    }
    private int view(CommandSourceStack source,boolean value) {
        try {
            var p=operator(source);
            if(!value){disabledHere.add(p.getUUID());var old=clients.remove(p.getUUID());if(old!=null)old.renderer.clear();}
            command(source,p,"view",",\"playerUuid\":"+q(p.getUUID().toString())+",\"enabled\":"+value,r->{
                if(value)disabledHere.remove(p.getUUID());
                message(p,value?"Paired views ON for 15 minutes, including backend changes. Remote entities are DISPLAY ONLY; interact on the source worker.":"Paired views OFF.");lastAttempt=0;
            });return 1;
        }catch(Exception e){return error(source,e);}
    }
    private int list(CommandSourceStack source) {
        try{var p=operator(source);command(source,p,"list","",r->{
            try{var pairs=EntityViewsWire.decodePairs(unbase64(field(r,"pairs")));message(p,"Paired views: "+pairs.size()+" (source-bound; no ownership transfer)");
                for(Pair pair:pairs)message(p,pair.entity()+" "+pair.kind()+" source="+pair.worker()+" dimension="+pair.dimension());
            }catch(IOException e){message(p,"Invalid pair-list response; no state changed.");}
        });return 1;}catch(Exception e){return error(source,e);}
    }
    private int status(CommandSourceStack source) {
        source.sendSuccess(()->Component.literal("ENTITY_VIEWS node="+FoundationsClusterNode.VERSION+" worker="+worker+
            " mode=PAIRED_DISPLAY_ONLY sourcePairs="+owned.size()+" unavailableSources="+unavailable+" temporaryTickets="+tickets.size()+
            " remoteViews="+clients.values().stream().mapToInt(v->v.renderer.size()).sum()+" identityCollisions="+clients.values().stream().mapToInt(v->v.renderer.collisions()).sum()+
            " remoteSurvival=false ownershipTransfer=false remotePickup=false status="+lastProblem),false);return 1;
    }
    private void command(CommandSourceStack source,ServerPlayer player,String action,String extra,Consumer<String> done) {
        String body=envelope(action)+",\"operatorName\":"+q(player.getGameProfile().getName())+extra+"}";
        try{io.execute(()->{
            try{String response=request(body);server.execute(()->{if(current(player))done.accept(response);});}
            catch(Exception e){server.execute(()->{if(current(player))message(player,"Entity-view request not confirmed: "+safe(e));});}
        });source.sendSuccess(()->Component.literal("Entity-view request queued; waiting for Manager confirmation."),false);}
        catch(RejectedExecutionException e){throw new IllegalStateException("Entity-view request queue is full or closed");}
    }
    private String envelope(String action){return "{\"workerId\":"+q(worker)+",\"instance\":"+q(FoundationsClusterNode.entityViewProcessInstance())+",\"action\":"+q(action);}
    private boolean current(ServerPlayer p){return !closed&&server.getPlayerList().getPlayer(p.getUUID())==p&&p.connection!=null;}
    private void tick() {
        if(closed)return;long now=now();
        for(var e:new ArrayList<>(clients.entrySet())){
            ClientView v=e.getValue();if(!current(v.player)||disabledHere.contains(e.getKey())){v.renderer.clear();clients.remove(e.getKey());}
            else v.renderer.expire(now);
        }
        disabledHere.removeIf(id->server.getPlayerList().getPlayer(id)==null);
        if(token.isBlank()||FoundationsClusterNode.entityViewProcessInstance().isBlank())return;
        maintainTickets(now);
        long interval=owned.isEmpty()&&server.getPlayerList().getPlayerCount()==0?2000:200;
        if(inFlight||now-lastAttempt<interval)return;
        lastAttempt=now;unavailable=0;
        var states=new ArrayList<State>();
        for(Pair p:owned){Entity e=find(p);
            if(e==null||!FoundationsClusterNode.entityViewSourceReady((ServerLevel)e.level(),e.blockPosition())){unavailable++;continue;}
            try{states.add(EntityViewPackets.capture(e,p.binding()));}catch(Exception ex){unavailable++;}
        }
        var playerRefs=new LinkedHashMap<UUID,ServerPlayer>();var positions=new ArrayList<Player>();
        for(ServerPlayer p:server.getPlayerList().getPlayers()) {
            if(positions.size()>=EntityViewsWire.MAX_PLAYERS)break;
            if(!FoundationsPlaytest.isConnectedPlayer(p))continue;
            playerRefs.put(p.getUUID(),p);positions.add(new Player(p.getUUID(),p.getGameProfile().getName(),p.serverLevel().dimension().location().toString(),p.getX(),p.getY(),p.getZ()));
        }
        try {
            long seq=Math.incrementExact(sequence);sequence=seq;
            String body=envelope("exchange")+",\"bundle\":"+q(base64(EntityViewsWire.encodeRequest(new Request(seq,states,positions))))+"}";
            inFlight=true;
            io.execute(()->{
                try {
                    if(now()-now>1000)throw new IOException("Entity-view exchange expired in queue");
                    var response=EntityViewsWire.decodeResponse(unbase64(field(request(body),"bundle")));
                    server.execute(()->{
                        try{if(!closed&&response.sequence()==seq&&now()-now<=1000)apply(response,playerRefs,now);}
                        catch(Exception e){lastProblem="presentation validation failed";clearClients();}
                        finally{inFlight=false;}
                    });
                }catch(Exception ex){server.execute(()->{inFlight=false;lastProblem=safe(ex);});}
            });
        }catch(Exception ex){inFlight=false;lastProblem="exchange could not be queued/encoded";}
    }
    private void apply(Response response,Map<UUID,ServerPlayer> refs,long started)throws IOException {
        long now=now();
        if(managerBoot!=null&&!managerBoot.equals(response.boot()))clearClients();managerBoot=response.boot();
        owned=response.owned();lastValid=now;lastProblem="connected; preview only";
        Set<UUID> keep=new HashSet<>();
        for(Viewer v:response.viewers()) {
            ServerPlayer p=refs.get(v.uuid());if(p==null||!current(p)||disabledHere.contains(v.uuid()))continue;
            ClientView prior=clients.get(v.uuid());if(prior!=null&&prior.player!=p){prior.renderer.clear();clients.remove(v.uuid());}
            var client=clients.computeIfAbsent(v.uuid(),id->new ClientView(p,new EntityViewRenderer(packet->p.connection.send(packet))));
            client.renderer.apply(p.serverLevel(),p.position(),v.samples(),now,now-started);keep.add(v.uuid());
        }
        for(UUID id:new ArrayList<>(clients.keySet()))if(!keep.contains(id)){clients.remove(id).renderer.clear();}
    }
    private Entity find(Pair p) {
        var dimension=ResourceLocation.tryParse(p.dimension());if(dimension==null)return null;
        ServerLevel level=server.getLevel(ResourceKey.create(Registries.DIMENSION,dimension));return level==null?null:level.getEntity(p.entity());
    }
    private void maintainTickets(long now) {
        Map<UUID,Ticket> keep=new HashMap<>();
        if(now-lastValid<EntityViewsWire.FRESH_MILLIS)for(Pair p:owned) {
            if(!p.interested())continue;Entity entity=find(p);
            if(entity==null||entity.isRemoved()||!FoundationsClusterNode.entityViewSourceReady((ServerLevel)entity.level(),entity.blockPosition()))continue;
            try{EntityViewPackets.supported(entity);}catch(IllegalArgumentException ex){continue;}
            keep.put(p.binding(),new Ticket((ServerLevel)entity.level(),entity.chunkPosition()));
        }
        for(var e:new ArrayList<>(tickets.entrySet()))if(!e.getValue().equals(keep.get(e.getKey()))){
            Ticket t=e.getValue();t.level.getChunkSource().removeRegionTicket(TICKET,t.chunk,2,e.getKey(),true);tickets.remove(e.getKey());
        }
        for(var e:keep.entrySet())if(!tickets.containsKey(e.getKey())){
            Ticket t=e.getValue();t.level.getChunkSource().addRegionTicket(TICKET,t.chunk,2,e.getKey(),true);tickets.put(e.getKey(),t);
        }
    }
    private String request(String body)throws IOException,InterruptedException {
        var req=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(2)).header("Content-Type","application/json")
            .header("X-Foundations-Agent-Token",token).POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var response=http.send(req,HttpResponse.BodyHandlers.ofInputStream());
        try(var input=response.body()){
            if(response.statusCode()!=200)throw new IOException("Manager rejected request (HTTP "+response.statusCode()+")");
            byte[] bytes=input.readNBytes(1700001);if(bytes.length>1700000)throw new IOException("Manager response exceeds preview limit");return new String(bytes,StandardCharsets.UTF_8);
        }
    }
    private static String field(String json,String key)throws IOException {
        var m=Pattern.compile("\""+key+"\"\\s*:\\s*\"([A-Za-z0-9_-]*)\"").matcher(json);
        if(!m.find())throw new IOException("Missing entity-view response field");return m.group(1);
    }
    private static String base64(byte[] bytes){return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    private static byte[] unbase64(String text)throws IOException{
        if(text.length()>(EntityViewsWire.MAX_BYTES*4L+2)/3)throw new IOException("Entity-view response too large");
        try{return Base64.getUrlDecoder().decode(text);}catch(IllegalArgumentException e){throw new IOException("Invalid entity-view response",e);}
    }
    private static String q(String s){return "\""+s.replace("\\","\\\\").replace("\"","\\\"")+"\"";}
    private static String safe(Exception e){return e instanceof IOException&&e.getMessage()!=null&&e.getMessage().startsWith("Manager rejected")?e.getMessage():"transport unavailable or invalid response; views expire automatically";}
    private static void message(ServerPlayer p,String text){p.sendSystemMessage(Component.literal(text));}
    private static int error(CommandSourceStack source,Exception e){source.sendFailure(Component.literal(e instanceof IllegalArgumentException||e instanceof IllegalStateException?Objects.toString(e.getMessage(),"Invalid request"):"Entity preview unavailable; check player/source readiness and supported entity type"));return 0;}
    private void clearClients(){for(ClientView c:clients.values())c.renderer.clear();clients.clear();}
    @Override public void close(){closed=true;io.shutdownNow();clearClients();for(var e:tickets.entrySet()){
        Ticket t=e.getValue();t.level.getChunkSource().removeRegionTicket(TICKET,t.chunk,2,e.getKey(),true);
    }tickets.clear();owned=List.of();}
}
