package com.foundations.cluster.manager;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * 0.0.5a.R5 Minecraft gateway. Velocity owns ingress and the long-lived client
 * session anchor. Normal region movement does not replace the backend connection;
 * Foundations changes simulation/authority targets behind the anchored session.
 * Transparent mode remains a join-time-only diagnostics fallback.
 */
final class GatewayServer implements AutoCloseable {
    record Route(long id, String playerId, String sessionId, String workerId, int workerPort,
                 String layerId, String regionId, String authorityWorkerId, long authorityEpoch, String remoteAddress, long connectedAtMillis) {}
    record Snapshot(boolean enabled, boolean running, String bind, int port, int activeConnections,
                    long totalConnections, long routedConnections, long statusRequests, long failedConnections,
                    long startedAtMillis, String lastError, List<Route> routes, String mode, String transportVersion,
                    long processPid, boolean processOwned, boolean processAdopted) {}

    private static final int MAX_INITIAL_FRAME = 1_048_576;

    private volatile Boolean seamlessAtLaunch;
    Boolean seamlessAtLaunch(){return running?seamlessAtLaunch:null;}
    private final ManagerConfig cfg;
    private final TokenStore tokens;
    private final WorkerManager workers;
    private final SessionManager sessions;
    private final PlayerStateManager playerStates;
    private final LayerPlanner layers;
    private final ClusterLog log;
    private final ConcurrentHashMap<Long, Route> activeRoutes = new ConcurrentHashMap<>();
    private final AtomicLong connectionSeq = new AtomicLong();
    private final AtomicLong totalConnections = new AtomicLong();
    private final AtomicLong routedConnections = new AtomicLong();
    private final AtomicLong statusRequests = new AtomicLong();
    private final AtomicLong failedConnections = new AtomicLong();
    private final AtomicInteger activeSockets = new AtomicInteger();
    private volatile ServerSocket serverSocket;
    private volatile Thread acceptThread;
    private volatile boolean running;
    private volatile long startedAtMillis;
    private volatile String lastError = "";
    private volatile Process velocityProcess;
    private volatile ProcessHandle velocityHandle;
    private volatile boolean velocityAdopted;
    private volatile int externalPlayers;
    private volatile String transportVersion = "";

    GatewayServer(ManagerConfig cfg, TokenStore tokens, WorkerManager workers, SessionManager sessions, PlayerStateManager playerStates, LayerPlanner layers, ClusterLog log) {
        this.cfg = cfg;
        this.tokens = tokens;
        this.workers = workers;
        this.sessions = sessions;
        this.playerStates = playerStates;
        this.layers = layers;
        this.log = log;
    }

    synchronized void start() {
        if (!cfg.getBoolean("gateway.enabled", true)) {
            log.info("Foundations Gateway disabled by configuration");
            return;
        }
        if (running) return;
        String mode = cfg.get("gateway.mode", "velocity").trim().toLowerCase(Locale.ROOT);
        if (mode.equals("velocity")) {
            Thread.ofVirtual().name("foundations-velocity-bootstrap").start(this::startVelocityGateway);
            return;
        }
        String bind = cfg.get("gateway.bind", "0.0.0.0");
        int port = cfg.getInt("gateway.port", 25565);
        try {
            ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(false);
            ss.bind(new InetSocketAddress(bind, port));
            serverSocket = ss;
            running = true;
            startedAtMillis = System.currentTimeMillis();
            lastError = "";
            acceptThread = Thread.ofVirtual().name("foundations-gateway-accept").start(this::acceptLoop);
            log.info("Foundations Gateway READY on " + bind + ":" + port + " -> backendHost=" + cfg.get("gateway.backendHost", "127.0.0.1"));
        } catch (IOException e) {
            running = false;
            lastError = e.getMessage() == null ? e.toString() : e.getMessage();
            log.warn("Foundations Gateway failed to bind " + bind + ":" + port + ": " + lastError);
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = serverSocket.accept();
                totalConnections.incrementAndGet();
                int max = Math.max(1, cfg.getInt("gateway.maxConnections", 5000));
                if (activeSockets.incrementAndGet() > max) {
                    activeSockets.decrementAndGet();
                    failedConnections.incrementAndGet();
                    closeQuietly(client);
                    continue;
                }
                long connectionId = connectionSeq.incrementAndGet();
                Thread.ofVirtual().name("foundations-gateway-conn-" + connectionId).start(() -> {
                    try { handle(client, connectionId); }
                    finally { activeSockets.decrementAndGet(); }
                });
            } catch (SocketException e) {
                if (running) {
                    lastError = e.getMessage() == null ? e.toString() : e.getMessage();
                    log.warn("Gateway accept loop socket error: " + lastError);
                }
            } catch (IOException e) {
                if (running) {
                    lastError = e.getMessage() == null ? e.toString() : e.getMessage();
                    log.warn("Gateway accept loop error: " + lastError);
                }
            }
        }
    }

    private void handle(Socket client, long routeId) {
        String sessionId = null;
        try (client) {
            int handshakeTimeout = Math.max(2, cfg.getInt("gateway.handshakeTimeoutSeconds", 10));
            client.setSoTimeout(handshakeTimeout * 1000);
            client.setTcpNoDelay(true);
            InputStream cin = new BufferedInputStream(client.getInputStream());
            OutputStream cout = new BufferedOutputStream(client.getOutputStream());

            Frame handshakeFrame = readFrame(cin);
            Handshake hs = parseHandshake(handshakeFrame.payload());
            if (hs.nextState() == 1) {
                statusRequests.incrementAndGet();
                handleStatus(cin, cout, hs.protocolVersion());
                return;
            }
            if (hs.nextState() != 2) throw new IOException("Unsupported Minecraft handshake nextState=" + hs.nextState());

            Frame loginFrame = readFrame(cin);
            String player = parseLoginStart(loginFrame.payload());
            sessionId = player;

            Map<String,String> placement = new HashMap<>();
            placement.put("playerId", player);
            placement.put("sessionId", sessionId);
            placement.put("worldId", cfg.get("layer.defaultWorld", "foundations"));
            placement.put("dimensionId", cfg.get("layer.defaultDimension", "minecraft:overworld"));
            playerStates.enrichPlacement(player, placement, layers.cellChunks());
            SessionManager.Placement selected = sessions.place(placement);
            WorkerRecord worker = selected.worker();
            if (worker == null || !worker.routableForNewSession()) throw new IOException("No routable worker available");

            String backendHost = cfg.get("gateway.backendHost", "127.0.0.1");
            int connectTimeout = Math.max(1, cfg.getInt("gateway.connectTimeoutSeconds", 5)) * 1000;
            Socket backend = new Socket();
            try (backend) {
                backend.setTcpNoDelay(true);
                backend.connect(new InetSocketAddress(backendHost, worker.port), connectTimeout);
                OutputStream bout = new BufferedOutputStream(backend.getOutputStream());
                bout.write(handshakeFrame.raw());
                bout.write(loginFrame.raw());
                bout.flush();

                client.setSoTimeout(0);
                String remote = String.valueOf(client.getRemoteSocketAddress());
                String layerId = selected.layer() == null ? "" : selected.layer().id;
                SessionRecord sr = selected.session();
                Route route = new Route(routeId, player, sessionId, worker.id, worker.port, layerId, sr.regionId, sr.authorityWorkerId, sr.authorityEpoch, remote, System.currentTimeMillis());
                activeRoutes.put(routeId, route);
                routedConnections.incrementAndGet();
                log.info("Gateway route player=" + player + " -> " + worker.id + ":" + worker.port
                        + (layerId.isBlank() ? "" : " layer=" + layerId) + " region=" + selected.session().regionId + " authority=" + selected.session().authorityWorkerId + "@" + selected.session().authorityEpoch + " remote=" + remote);

                CountDownLatch done = new CountDownLatch(1);
                Thread a = Thread.ofVirtual().name("gateway-c2b-" + routeId).start(() -> pipe(cin, bout, done));
                Thread b = Thread.ofVirtual().name("gateway-b2c-" + routeId).start(() -> {
                    try { pipe(new BufferedInputStream(backend.getInputStream()), cout, done); }
                    catch (IOException e) { done.countDown(); }
                });
                done.await();
                closeQuietly(client);
                closeQuietly(backend);
                try { a.join(1000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                try { b.join(1000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }
        } catch (Exception e) {
            failedConnections.incrementAndGet();
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            log.warn("Gateway connection failed: " + msg);
        } finally {
            activeRoutes.remove(routeId);
            if (sessionId != null) sessions.release(sessionId);
        }
    }

    private void handleStatus(InputStream in, OutputStream out, int clientProtocol) throws IOException {
        Frame request = readFrame(in);
        int packetId = readVarInt(new ByteArrayInputStream(request.payload()));
        if (packetId != 0) throw new IOException("Expected status request packet, got " + packetId);

        int online = workers.all().stream().mapToInt(w -> w.players).sum();
        int max = workers.all().stream().filter(WorkerRecord::routableForNewSession).mapToInt(w -> Math.max(0, w.maxPlayers)).sum();
        if (max <= 0) max = Math.max(1, cfg.getInt("gateway.statusMaxPlayers", 100));
        int protocol = cfg.getInt("gateway.protocolVersion", 767);
        String versionName = cfg.get("gateway.minecraftVersion", "1.21.1");
        String motd = cfg.get("gateway.motd", "Foundations Cluster");
        String json = "{\"version\":{\"name\":" + Json.q(versionName) + ",\"protocol\":" + protocol + "},"
                + "\"players\":{\"max\":" + max + ",\"online\":" + online + ",\"sample\":[]},"
                + "\"description\":{\"text\":" + Json.q(motd) + "}}";
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        writeVarInt(payload, 0);
        writeString(payload, json);
        writeFrame(out, payload.toByteArray());
        out.flush();

        try {
            Frame ping = readFrame(in);
            ByteArrayInputStream p = new ByteArrayInputStream(ping.payload());
            int id = readVarInt(p);
            if (id == 1 && p.available() == 8) {
                writeFrame(out, ping.payload());
                out.flush();
            }
        } catch (EOFException | SocketTimeoutException ignored) {
            // Status response is already complete; ping is optional for our purposes.
        }
    }


    private void startVelocityGateway() {
        try {
            Path root=cfg.home();
            Path jar=root.resolve("velocity/velocity.jar").toAbsolutePath().normalize();

            // Proxy ownership: a Manager restart must never launch a second proxy over a
            // still-running Foundations proxy.  A prior Manager cannot retain a
            // java.lang.Process handle, but the Velocity JVM is safely identifiable
            // by the absolute velocity.jar argument belonging to this Cluster root.
            ProcessHandle existing=findExistingVelocityProcess(jar);
            if(existing!=null){
                velocityProcess=null;velocityHandle=existing;velocityAdopted=true;
                seamlessAtLaunch=null;running=true;startedAtMillis=System.currentTimeMillis();lastError="";transportVersion="conduit-1.8.0-foundations-r16-adopted";
                log.warn("Adopted existing Foundations Velocity process pid="+existing.pid()+" instead of launching a duplicate");
                watchVelocityHandle(existing,"adopted");
                return;
            }
            if(gatewayPortListening())throw new IOException("gateway port "+cfg.getInt("gateway.port",25565)+" is already in use by an unrecognized process; refusing to start a second Velocity instance");

            Path setup=root.resolve("tools/setup_velocity.ps1");
            if (!Files.isRegularFile(setup)) throw new IOException("tools/setup_velocity.ps1 is missing");
            if (System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("win")) {
                ProcessBuilder prep=new ProcessBuilder("powershell.exe","-NoLogo","-NoProfile","-ExecutionPolicy","Bypass","-File",setup.toAbsolutePath().toString());
                prep.directory(root.toFile());prep.redirectErrorStream(true);prep.redirectOutput(root.resolve("logs/velocity-bootstrap.log").toFile());
                Process pp=prep.start();
                long timeout=Math.max(60,cfg.getLong("gateway.velocity.setupTimeoutSeconds",300));
                if(!pp.waitFor(timeout,TimeUnit.SECONDS)){pp.destroyForcibly();throw new IOException("Velocity bootstrap timed out after "+timeout+" seconds");}
                if(pp.exitValue()!=0)throw new IOException("Velocity bootstrap failed with exit code "+pp.exitValue()+"; see logs/velocity-bootstrap.log");
            }
            if(!Files.isRegularFile(jar))throw new IOException("velocity/velocity.jar is missing after bootstrap");
            boolean windows=System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("win");
            Path bundledJava=root.resolve("velocity/runtime/java25/bin/"+(windows?"java.exe":"java")).toAbsolutePath().normalize();
            Path selectedJava=Files.isRegularFile(bundledJava)
                    ? bundledJava
                    : Path.of(System.getProperty("java.home"),"bin",windows?"java.exe":"java").toAbsolutePath().normalize();
            String javaExe=selectedJava.toString();
            log.info("Foundations Gateway Java runtime="+javaExe+(Files.isRegularFile(bundledJava)?" (bundled JRE 25)":" (Manager JVM fallback)"));
            List<String> command=new ArrayList<>();command.add(javaExe);command.add("-Xms"+cfg.get("gateway.velocity.xms","256M"));command.add("-Xmx"+cfg.get("gateway.velocity.xmx","512M"));
            String extra=cfg.get("gateway.velocity.extraJvmArgs","-Dvelocity.max-known-packs=2048");if(!extra.isBlank())for(String arg:extra.split("\\|"))if(!arg.isBlank())command.add(arg.trim());
            command.add("-jar");command.add(jar.toString());
            ProcessBuilder pb=new ProcessBuilder(command);
            pb.directory(root.resolve("velocity").toFile());
            pb.environment().put("FOUNDATIONS_CLUSTER_MANAGER_URL",cfg.get("manager.publicUrl","http://127.0.0.1:"+cfg.getInt("web.port",8765)));
            pb.environment().put("FOUNDATIONS_CLUSTER_GATEWAY_TOKEN",tokens.gatewayToken);
            pb.redirectErrorStream(true);pb.redirectOutput(root.resolve("logs/velocity.log").toFile());
            seamlessAtLaunch=GatewayPolicy.readLaunchPolicy(root.resolve("velocity/conduit.toml"));
            log.info("Gateway switch policy at launch: "+(seamlessAtLaunch==null?"unknown":seamlessAtLaunch?"experimental seamless":"normal reconfiguration"));
            velocityProcess=pb.start();velocityHandle=velocityProcess.toHandle();velocityAdopted=false;
            running=true;startedAtMillis=System.currentTimeMillis();lastError="";transportVersion="conduit-1.8.0-foundations-r16-starting";
            log.info("Foundations Gateway starting in Conduit physical session-proxy mode pid="+velocityHandle.pid()+" on "+cfg.get("gateway.bind","0.0.0.0")+":"+cfg.getInt("gateway.port",25565));
            watchVelocityHandle(velocityHandle,"owned");
        } catch(Exception e){running=false;lastError=e.getMessage()==null?e.toString():e.getMessage();log.warn("Foundations Conduit/Velocity Gateway failed: "+lastError);}
    }

    private void watchVelocityHandle(ProcessHandle handle,String ownership){
        handle.onExit().thenRun(()->{
            if(velocityHandle!=handle)return;
            running=false;
            lastError="Velocity "+ownership+" process exited pid="+handle.pid();
            log.warn(lastError);
        });
    }

    private ProcessHandle findExistingVelocityProcess(Path velocityJar){
        final String wanted=normalizePath(velocityJar.toString());
        try{
            return ProcessHandle.allProcesses().filter(ProcessHandle::isAlive).filter(ph->{
                if(ph.pid()==ProcessHandle.current().pid())return false;
                String[] args=ph.info().arguments().orElse(new String[0]);
                for(String arg:args)if(normalizePath(arg).equals(wanted))return true;
                String cmd=ph.info().commandLine().orElse("");
                return normalizePath(cmd).contains(wanted);
            }).findFirst().orElse(null);
        }catch(Exception ignored){return null;}
    }

    private static String normalizePath(String value){
        if(value==null)return "";
        String v=value.replace('\\','/').replace("\"","").trim();
        return System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("win")?v.toLowerCase(Locale.ROOT):v;
    }

    private boolean gatewayPortListening(){
        int port=cfg.getInt("gateway.port",25565);
        try(ServerSocket probe=new ServerSocket()){
            probe.setReuseAddress(false);
            probe.bind(new InetSocketAddress(cfg.get("gateway.bind","0.0.0.0"),port));
            return false;
        }catch(IOException ex){return true;}
    }

    void externalHeartbeat(int players,String version){externalPlayers=Math.max(0,players);if(version!=null&&!version.isBlank())transportVersion=version;running=true;if(startedAtMillis==0)startedAtMillis=System.currentTimeMillis();lastError="";}

    Snapshot snapshot() {
        List<Route> routes = activeRoutes.values().stream().sorted(Comparator.comparingLong(Route::id)).toList();
        String mode=cfg.get("gateway.mode","velocity");
        int active=mode.equalsIgnoreCase("velocity")?externalPlayers:activeRoutes.size();
        ProcessHandle vh=velocityHandle;
        return new Snapshot(cfg.getBoolean("gateway.enabled", true), running,
                cfg.get("gateway.bind", "0.0.0.0"), cfg.getInt("gateway.port", 25565),
                active, totalConnections.get(), routedConnections.get(), statusRequests.get(), failedConnections.get(),
                startedAtMillis, lastError, routes, mode, transportVersion,
                vh!=null&&vh.isAlive()?vh.pid():-1L, velocityProcess!=null&&velocityProcess.isAlive(), velocityAdopted);
    }

    private static void pipe(InputStream in, OutputStream out, CountDownLatch done) {
        byte[] buf = new byte[64 * 1024];
        try {
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n == 0) continue;
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (IOException ignored) {
        } finally {
            done.countDown();
        }
    }

    private static String parseLoginStart(byte[] payload) throws IOException {
        ByteArrayInputStream in = new ByteArrayInputStream(payload);
        int packetId = readVarInt(in);
        if (packetId != 0) throw new IOException("Expected Login Start packet (0), got " + packetId);
        String username = readString(in, 64);
        if (!username.matches("[A-Za-z0-9_]{1,16}")) throw new IOException("Invalid Minecraft username in Login Start");
        return username;
    }

    private static Handshake parseHandshake(byte[] payload) throws IOException {
        ByteArrayInputStream in = new ByteArrayInputStream(payload);
        int packetId = readVarInt(in);
        if (packetId != 0) throw new IOException("Expected handshake packet (0), got " + packetId);
        int protocol = readVarInt(in);
        String address = readString(in, 2048);
        int hi = in.read(), lo = in.read();
        if (hi < 0 || lo < 0) throw new EOFException("Handshake missing server port");
        int port = (hi << 8) | lo;
        int nextState = readVarInt(in);
        return new Handshake(protocol, address, port, nextState);
    }

    private record Handshake(int protocolVersion, String serverAddress, int serverPort, int nextState) {}
    private record Frame(byte[] raw, byte[] payload) {}

    private static Frame readFrame(InputStream in) throws IOException {
        ByteArrayOutputStream prefix = new ByteArrayOutputStream(5);
        int length = readVarInt(in, prefix);
        if (length < 0 || length > MAX_INITIAL_FRAME) throw new IOException("Minecraft initial packet length out of range: " + length);
        byte[] payload = in.readNBytes(length);
        if (payload.length != length) throw new EOFException("Unexpected EOF reading Minecraft packet");
        byte[] pre = prefix.toByteArray();
        byte[] raw = new byte[pre.length + payload.length];
        System.arraycopy(pre, 0, raw, 0, pre.length);
        System.arraycopy(payload, 0, raw, pre.length, payload.length);
        return new Frame(raw, payload);
    }

    private static int readVarInt(InputStream in) throws IOException { return readVarInt(in, null); }
    private static int readVarInt(InputStream in, ByteArrayOutputStream capture) throws IOException {
        int value = 0, position = 0;
        while (true) {
            int current = in.read();
            if (current < 0) throw new EOFException("Unexpected EOF reading VarInt");
            if (capture != null) capture.write(current);
            value |= (current & 0x7F) << position;
            if ((current & 0x80) == 0) return value;
            position += 7;
            if (position >= 35) throw new IOException("VarInt too large");
        }
    }

    private static String readString(InputStream in, int maxChars) throws IOException {
        int len = readVarInt(in);
        if (len < 0 || len > maxChars * 4) throw new IOException("String byte length out of range: " + len);
        byte[] b = in.readNBytes(len);
        if (b.length != len) throw new EOFException("Unexpected EOF reading string");
        String s = new String(b, StandardCharsets.UTF_8);
        if (s.length() > maxChars) throw new IOException("String character length out of range");
        return s;
    }

    private static void writeString(OutputStream out, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, b.length);
        out.write(b);
    }

    private static void writeFrame(OutputStream out, byte[] payload) throws IOException {
        writeVarInt(out, payload.length);
        out.write(payload);
    }

    private static void writeVarInt(OutputStream out, int value) throws IOException {
        while ((value & 0xFFFFFF80) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value & 0x7F);
    }

    private static void closeQuietly(Closeable c) {
        if (c == null) return;
        try { c.close(); } catch (IOException ignored) {}
    }

    @Override public synchronized void close() {
        running = false;
        closeQuietly(serverSocket);
        Process vp=velocityProcess;ProcessHandle vh=velocityHandle;
        if(vp!=null&&vp.isAlive()){
            vp.destroy();try{if(!vp.waitFor(5,TimeUnit.SECONDS))vp.destroyForcibly();}catch(InterruptedException e){Thread.currentThread().interrupt();}
        }else if(vh!=null&&vh.isAlive()){
            // An adopted process still belongs to this Cluster root. Full Cluster
            // shutdown owns its lifecycle even though stdin/Process could not be reattached.
            vh.destroy();try{vh.onExit().get(5,TimeUnit.SECONDS);}catch(Exception ignored){}if(vh.isAlive())vh.destroyForcibly();
        }
        velocityProcess=null;velocityHandle=null;velocityAdopted=false;
        for (Route r : activeRoutes.values()) sessions.release(r.sessionId());
        activeRoutes.clear();
        log.info("Foundations Gateway stopped");
    }
}
