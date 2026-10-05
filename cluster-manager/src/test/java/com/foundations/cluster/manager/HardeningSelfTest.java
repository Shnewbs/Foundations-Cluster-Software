package com.foundations.cluster.manager;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;

/** Recovery, credential and queue regressions from the October 2026 audit. */
public final class HardeningSelfTest {
    static int checks;
    interface Action { void run() throws Exception; }
    static void check(boolean pass, String why) { if (!pass) throw new AssertionError(why); checks++; }
    static void rejects(Class<? extends Exception> type, Action action) throws Exception {
        try { action.run(); } catch (Exception ex) { if (!type.isInstance(ex)) throw ex; checks++; return; }
        throw new AssertionError("Expected " + type.getSimpleName());
    }
    static ManagerConfig config(Path home) throws IOException {
        Files.createDirectories(home.resolve("config"));
        Files.writeString(home.resolve("config/cluster.properties"), "world.seed=1234\nworld.environment.enabled=false\nworld.identity.enforce=false\nregion.autoFailover=false\nregion.checkSeconds=3600\nregion.recoverySeconds=0\nlayer.enabled=false\ndataplane.maxQueuePerWorker=32\n");
        return ManagerConfig.load(home);
    }
    public static void main(String[] args) throws Exception {
        Path root=Files.createTempDirectory("foundations hardening ");
        try {
            json(); partialWrites(); credentials(root.resolve("auth")); vault(root.resolve("vault"));
            journals(root.resolve("journal")); dataPlane(root.resolve("relay"));
            System.out.println("Hardening regressions: PASS (" + checks + " checks)");
        } finally { try (var walk=Files.walk(root)) { for (Path p:walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); } }
    }
    static void json() throws Exception {
        for (String value : List.of("C:\\new\\test", "literal \\n and \\u0041", "\"\\\b\f\n\r\t\u0001", "Unicode 日本語 🙂", ""))
            check(Json.flatObject("{\"value\":"+Json.q(value)+"}").get("value").equals(value), "JSON string did not round trip");
        check(Json.flatObject("{\"x\":[1,{\"a\":true}],\"y\":null,\"z\":-1.25e+2}").get("x").equals("[1,{\"a\":true}]"), "Nested value changed");
        for (String bad:List.of("{", "{\"a\":1", "{\"a\":1,}", "{\"a\":1,\"a\":2}", "{\"a\":01}", "{\"a\":NaN}", "{\"a\":\"\\q\"}", "{\"a\":\"x\ny\"}", "{} garbage", "[]", "{\"a\":[1,]}", "{\"a\":trueX}"))
            rejects(IllegalArgumentException.class, () -> Json.flatObject(bad));
        rejects(IllegalArgumentException.class, () -> Json.flatObject("{\"a\":"+"[".repeat(66)+"0"+"]".repeat(66)+"}"));
    }
    static void partialWrites() throws Exception {
        var result=new ByteArrayOutputStream();
        var channel=new WritableByteChannel() {
            public int write(ByteBuffer b) { int n=Math.min(3,b.remaining()); for(int i=0;i<n;i++)result.write(b.get()); return n; }
            public boolean isOpen(){return true;} public void close(){}
        };
        byte[] expected="a complete durable record\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        DurableFiles.writeFully(channel,ByteBuffer.wrap(expected));
        check(Arrays.equals(expected,result.toByteArray()), "Short channel writes lost bytes");
    }
    static void credentials(Path root) throws Exception {
        var cfg=config(root);var log=new ClusterLog(root.resolve("logs/test.log"),100);
        var auth=new AuthManager(cfg,log);var exchange=new Exchange();
        Path admin=root.resolve("runtime/security/admin.json"), sessions=admin.resolveSibling("sessions.jsonl");
        Files.createDirectory(admin);
        rejects(IOException.class,()->auth.setup("operator","long password one",true,exchange));
        check(auth.setupRequired(),"Failed setup changed in-memory credentials");Files.delete(admin);
        var login=auth.setup("operator","long password one",true,exchange);exchange.token(login.token());
        check(auth.authenticated(exchange),"Fresh session rejected");
        var restarted=new AuthManager(cfg,log);check(restarted.authenticated(exchange),"Remembered session lost on restart");
        String oldSessions=Files.readString(sessions);
        auth.changePassword("long password one","long password two",exchange);
        Files.writeString(sessions,oldSessions); // Crash before old sessions could be rewritten.
        restarted=new AuthManager(cfg,log);check(!restarted.authenticated(exchange),"Old session survived credential change");
        exchange.token(restarted.login("operator","long password two",true,"127.0.0.1").token());
        check(restarted.authenticated(exchange),"New password rejected");
        final var limited=restarted;
        for(int i=0;i<5;i++) rejects(SecurityException.class,()->limited.login("operator","wrong password",false,"192.0.2.1"));
        rejects(SecurityException.class,()->limited.login("operator","long password two",false,"192.0.2.1"));
        Files.delete(sessions);Files.createDirectory(sessions);
        rejects(IOException.class,limited::revokeAllSessions);
        check(limited.authenticated(exchange),"Failed revocation silently changed state");Files.delete(sessions);
        for(String corrupt:List.of("", "{}", "{\"username\":\"operator\",\"passwordHash\":\"bad\"}", "{\"username\":\"operator\"")) {
            Files.writeString(admin,corrupt);rejects(IOException.class,()->new AuthManager(cfg,log));
        }
    }
    static void vault(Path dir) throws Exception {
        var vault=new SecretVault(dir);vault.put("token","original");
        check(new SecretVault(dir).get("token").equals("original"),"Vault restart failed");
        Path file=dir.resolve("secrets.vault");byte[] original=Files.readAllBytes(file);
        Files.delete(file);Files.createDirectory(file);
        rejects(IOException.class,()->vault.put("token","replacement"));
        check(vault.get("token").equals("original"),"Failed vault write changed memory");Files.delete(file);Files.write(file,original);
        Path key=dir.resolve(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")?"vault-key.dpapi":"vault-key.bin");Files.delete(key);
        rejects(IOException.class,()->new SecretVault(dir));
        check(!Files.exists(key)&&Arrays.equals(original,Files.readAllBytes(file)),"Missing vault key silently regenerated");
    }
    static void journals(Path root) throws Exception {
        var cfg=config(root);var log=new ClusterLog(root.resolve("logs/test.log"),100);
        String region="foundations/minecraft:overworld/r.0.0";
        try(var j=new PersistentBlockJournal(cfg,log);var be=new PersistentBlockEntityJournal(cfg,log)) {
            j.record("foundations","minecraft:overworld",region,2,1,64,1,"minecraft:stone");
            j.record("foundations","minecraft:overworld",region,2,1,64,1,"minecraft:air");
            be.record("foundations","minecraft:overworld",region,2,2,64,2,true,"minecraft:chest","minecraft:chest","payload");
        }
        try(var j=new PersistentBlockJournal(cfg,log);var be=new PersistentBlockEntityJournal(cfg,log)) {
            check(j.summary().revision()==2&&j.summary().blocks()==1,"Block snapshot failed recovery");
            check(be.summary().revision()==1&&be.summary().blockEntities()==1,"BlockEntity snapshot failed recovery");
        }
        for(String name:List.of("persistent-blocks.wal","persistent-block-entities.wal")) {
            Path wal=root.resolve("runtime/"+name);Files.writeString(wal,"malformed\n");
            if(name.equals("persistent-blocks.wal")) rejects(IOException.class,()->new PersistentBlockJournal(cfg,log));
            else rejects(IOException.class,()->new PersistentBlockEntityJournal(cfg,log));
            Files.writeString(wal,"incomplete");rejects(IOException.class,()->DurableFiles.validateJournal(wal));Files.delete(wal);
        }
        var j=new PersistentBlockJournal(cfg,log);Path wal=root.resolve("runtime/persistent-blocks.wal");Files.createDirectory(wal);
        rejects(IllegalStateException.class,()->j.record("foundations","minecraft:overworld",region,2,3,64,3,"minecraft:stone"));
        Files.delete(wal);
        rejects(IllegalStateException.class,()->j.record("foundations","minecraft:overworld",region,2,3,64,3,"minecraft:stone"));
        j.close();check(!Files.exists(wal),"Uncertain WAL compacted on close");
    }
    static void dataPlane(Path root) throws Exception {
        var cfg=config(root);var log=new ClusterLog(root.resolve("logs/test.log"),100);var templates=new TemplateManager(cfg,log);
        try(var workers=new WorkerManager(cfg,new TokenStore(root.resolve("runtime")),log,templates,new NodeAgentManager(cfg,templates,log));
            var regions=new RegionAuthorityManager(cfg,workers,log);
            var layers=new LayerPlanner(cfg,workers,log);
            var states=new PlayerStateManager(cfg,log);
            var sessions=new SessionManager(cfg,workers,layers,regions,states,log);
            var relay=new DataPlaneManager(cfg,workers,sessions,regions,log)) {
            var owner=workers.register(Map.of("workerId","worker-001","port","25566","ready","true","worldSeed","1234","nodeVersion","0.0.6a.R9"));
            var target=workers.register(Map.of("workerId","worker-002","port","25567","ready","true","worldSeed","1234","nodeVersion","0.0.6a.R9"));
            var r=regions.ensure(new RegionKey("foundations","minecraft:overworld",0,0),owner.id,"fixture");
            var s=sessions.place(new HashMap<>(Map.of("playerId","fixture-player","cellX","0","cellZ","0"))).session();s.anchorWorkerId=target.id;
            var frame=new HashMap<>(Map.of("kind","PERSIST_BLOCK","sourceWorkerId",owner.id,"regionId",r.key.id(),"authorityEpoch",Long.toString(r.epoch),"payload","1|64|1|"+Base64.getUrlEncoder().encodeToString("minecraft:stone".getBytes())));
            var first=relay.publish(frame);relay.publish(frame);
            check(relay.summary().pending()==1,"Pending invalidations did not coalesce");
            check(!relay.pollBundle(target.id,1).isEmpty(),"Invalidation not delivered");
            relay.publish(frame);check(relay.summary().pending()==1,"Post-delivery write lost its invalidation");
            relay.pollBundle(target.id,1);check(relay.summary().pending()==0,"New invalidation not delivered");
            frame.put("authorityEpoch",Long.toString(r.epoch+1));rejects(IllegalArgumentException.class,()->relay.publish(frame));
            frame.put("authorityEpoch",Long.toString(r.epoch));r.leaseExpiresAtMillis=0;rejects(IllegalArgumentException.class,()->relay.publish(frame));
            var generic=new HashMap<>(Map.of("kind","ENTITY_DELTA","sourceWorkerId",owner.id,"targetWorkerId",target.id));
            try(var restarted=new DataPlaneManager(config(root.resolve("new-process")),workers,sessions,regions,log)) {
                var current=restarted.publish(generic);restarted.pollBundle(target.id,1);
                check(!current.id.equals(first.id),"Frame identity reused across manager processes");
                restarted.ack(target.id,first.id,true,"");
                check(restarted.summary().inflight()==1,"Delayed old acknowledgement discarded a new frame");
            }
            for(int i=0;i<100;i++){relay.publish(generic);relay.pollBundle(target.id,1);}
            check(relay.summary().inflight()<=32&&relay.summary().pending()<=32,"Relay backlog exceeded bounds");
            workers.stop(owner.id);
            workers.heartbeat(Map.of("workerId",owner.id,"ready","true"));
            check(!owner.ready && owner.state==WorkerState.STOPPING,"Late heartbeat revived stopped worker");
            workers.drain(owner.id,true);workers.maintenance(owner.id,true);
            workers.register(Map.of("workerId",owner.id,"ready","true"));
            check(!owner.ready && owner.state==WorkerState.STOPPING,"Late registration or policy edit removed stop fence");
        }
    }
    static final class Exchange extends HttpExchange {
        final Headers headers=new Headers();void token(String t){headers.set("Cookie","foundations_session="+t);}
        public Headers getRequestHeaders(){return headers;}public Headers getResponseHeaders(){return new Headers();}
        public URI getRequestURI(){return URI.create("http://localhost/");}public String getRequestMethod(){return "POST";}
        public HttpContext getHttpContext(){return null;}public void close(){}public InputStream getRequestBody(){return InputStream.nullInputStream();}
        public OutputStream getResponseBody(){return OutputStream.nullOutputStream();}public void sendResponseHeaders(int r,long n){}
        public InetSocketAddress getRemoteAddress(){return new InetSocketAddress("127.0.0.1",10001);}public int getResponseCode(){return 200;}
        public InetSocketAddress getLocalAddress(){return new InetSocketAddress("127.0.0.1",8080);}public String getProtocol(){return "HTTP/1.1";}
        public Object getAttribute(String n){return null;}public void setAttribute(String n,Object v){}public void setStreams(InputStream i,OutputStream o){}
        public HttpPrincipal getPrincipal(){return null;}
    }
}
