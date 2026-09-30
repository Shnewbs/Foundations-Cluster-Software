package com.foundations.cluster.manager;

import com.sun.net.httpserver.HttpExchange;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

import java.io.*;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

final class AuthManager {
    private static final SecureRandom RNG = new SecureRandom();
    private static final int ARGON_MEMORY_KIB = 65536;
    private static final int ARGON_ITERATIONS = 3;
    private static final int ARGON_PARALLELISM = 1;
    private final ManagerConfig cfg;
    private final Path securityDir;
    private final Path adminFile;
    private final Path sessionsFile;
    private final ClusterLog log;
    private final Map<String,Session> sessions = new LinkedHashMap<>(); // keyed by token hash
    private final Map<String,Attempt> attempts = new ConcurrentHashMap<>();
    private volatile Credential credential;

    AuthManager(ManagerConfig cfg, ClusterLog log) throws IOException {
        this.cfg=cfg; this.log=log;
        this.securityDir=cfg.home().resolve("runtime/security");
        this.adminFile=securityDir.resolve("admin.json");
        this.sessionsFile=securityDir.resolve("sessions.jsonl");
        Files.createDirectories(securityDir);
        loadCredential();
        loadSessions();
    }

    boolean setupRequired(){return credential==null;}

    synchronized String statusJson(HttpExchange e){
        String user = authenticatedUser(e);
        return "{\"setupRequired\":"+setupRequired()+",\"authenticated\":"+(user!=null)+",\"username\":"+Json.q(user)+",\"passwordStorage\":\"Argon2id\",\"secretStorage\":"+Json.q(isWindows()?"Windows DPAPI + AES-256-GCM":"AES-256-GCM local vault")+",\"rememberDays\":"+cfg.getInt("security.rememberDays",30)+"}";
    }

    synchronized LoginResult setup(String username, String password, boolean remember, HttpExchange e) throws Exception {
        if (!setupRequired()) throw new IllegalStateException("Administrator account is already configured.");
        if (!isLoopback(e)) throw new SecurityException("First-run administrator setup is only allowed from the local machine.");
        validateUsername(username); validatePassword(password);
        credential = new Credential(username, hashPassword(password));
        saveCredential();
        log.info("Security: administrator account created for "+username);
        return issue(username,remember);
    }

    synchronized LoginResult login(String username, String password, boolean remember, String remoteKey) throws Exception {
        if (setupRequired()) throw new IllegalStateException("Administrator setup is required.");
        Attempt a=attempts.computeIfAbsent(remoteKey,k->new Attempt());
        long now=System.currentTimeMillis();
        if(a.lockedUntil>now) throw new SecurityException("Too many failed sign-in attempts. Try again in "+Math.max(1,(a.lockedUntil-now)/1000)+" seconds.");
        if (!credential.username.equals(username) || !verifyPassword(password,credential.hash)) {
            a.failures++;
            if(a.failures>=5){a.failures=0;a.lockedUntil=now+60_000;}
            throw new SecurityException("Invalid username or password.");
        }
        attempts.remove(remoteKey);
        return issue(username,remember);
    }

    synchronized void logout(HttpExchange e) throws IOException {
        String token=cookieToken(e);
        if(token==null)return;
        sessions.remove(tokenHash(token));
        saveSessions();
    }

    synchronized void changePassword(String current, String next, HttpExchange e) throws Exception {
        String user=authenticatedUser(e);
        if(user==null)throw new SecurityException("Authentication required.");
        if(!verifyPassword(current,credential.hash))throw new SecurityException("Current password is incorrect.");
        validatePassword(next);
        credential=new Credential(credential.username,hashPassword(next));
        saveCredential();
        sessions.clear(); saveSessions();
        log.info("Security: administrator password changed; all remembered sessions revoked");
    }

    synchronized void revokeAllSessions(){sessions.clear();try{saveSessions();}catch(IOException ignored){}}

    synchronized String authenticatedUser(HttpExchange e){
        String token=cookieToken(e); if(token==null)return null;
        String hash=tokenHash(token); Session s=sessions.get(hash); if(s==null)return null;
        long now=System.currentTimeMillis();
        long idleMillis=Math.max(1,cfg.getLong("security.idleHours",12))*3600_000L;
        if(s.expiresAt<now || now-s.lastSeen>idleMillis){sessions.remove(hash);try{saveSessions();}catch(IOException ignored){}return null;}
        if(now-s.lastSeen>300_000L){s.lastSeen=now;try{saveSessions();}catch(IOException ignored){}}
        return s.username;
    }

    boolean authenticated(HttpExchange e){return authenticatedUser(e)!=null;}

    private LoginResult issue(String username, boolean remember) throws IOException {
        byte[] raw=new byte[32];RNG.nextBytes(raw);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        long now=System.currentTimeMillis();
        long seconds=remember?Math.max(1,cfg.getLong("security.rememberDays",30))*86400L:Math.max(1,cfg.getLong("security.sessionHours",12))*3600L;
        long expires=now+seconds*1000L;
        sessions.put(tokenHash(token),new Session(username,now,expires,now));
        prune();saveSessions();
        return new LoginResult(username,token,seconds,expires,remember);
    }

    String cookieHeader(LoginResult r){return "foundations_session="+r.token+"; Path=/; HttpOnly; SameSite=Strict"+(r.persistent?"; Max-Age="+r.maxAgeSeconds:"");}
    String clearCookieHeader(){return "foundations_session=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0";}

    private String cookieToken(HttpExchange e){
        String cookie=e.getRequestHeaders().getFirst("Cookie"); if(cookie==null)return null;
        for(String part:cookie.split(";")){String[]kv=part.trim().split("=",2);if(kv.length==2&&kv[0].equals("foundations_session"))return kv[1];}
        return null;
    }

    private void loadCredential() throws IOException {
        if(!Files.isRegularFile(adminFile)){credential=null;return;}
        Map<String,String> m=Json.flatObject(Files.readString(adminFile,StandardCharsets.UTF_8));
        String u=m.get("username"),h=m.get("passwordHash");
        if(u==null||h==null||u.isBlank()||h.isBlank()){credential=null;return;}
        credential=new Credential(u,h);
    }
    private void saveCredential() throws IOException {
        String json="{\n  \"username\": "+Json.q(credential.username)+",\n  \"passwordHash\": "+Json.q(credential.hash)+",\n  \"updatedAt\": "+Json.q(Instant.now().toString())+"\n}\n";
        writeAtomic(adminFile,json);
    }

    private void loadSessions() throws IOException {
        sessions.clear(); if(!Files.isRegularFile(sessionsFile))return;
        for(String line:Files.readAllLines(sessionsFile,StandardCharsets.UTF_8)){
            Map<String,String>m=Json.flatObject(line);String h=m.get("hash"),u=m.get("username");
            try{long c=Long.parseLong(m.getOrDefault("createdAt","0")),x=Long.parseLong(m.getOrDefault("expiresAt","0")),ls=Long.parseLong(m.getOrDefault("lastSeen",Long.toString(c)));if(h!=null&&u!=null&&x>System.currentTimeMillis())sessions.put(h,new Session(u,c,x,ls));}catch(Exception ignored){}
        }
        prune();
    }
    private void saveSessions() throws IOException {
        prune();StringBuilder b=new StringBuilder();
        for(var e:sessions.entrySet()){Session s=e.getValue();b.append("{\"hash\":").append(Json.q(e.getKey())).append(",\"username\":").append(Json.q(s.username)).append(",\"createdAt\":").append(s.createdAt).append(",\"expiresAt\":").append(s.expiresAt).append(",\"lastSeen\":").append(s.lastSeen).append("}\n");}
        writeAtomic(sessionsFile,b.toString());
    }
    private void prune(){long now=System.currentTimeMillis();sessions.entrySet().removeIf(e->e.getValue().expiresAt<now);}

    private static String hashPassword(String password){
        byte[] salt=new byte[16];RNG.nextBytes(salt);byte[] out=argon(password,salt,ARGON_MEMORY_KIB,ARGON_ITERATIONS,ARGON_PARALLELISM);
        return "$argon2id$v=19$m="+ARGON_MEMORY_KIB+",t="+ARGON_ITERATIONS+",p="+ARGON_PARALLELISM+"$"+Base64.getEncoder().withoutPadding().encodeToString(salt)+"$"+Base64.getEncoder().withoutPadding().encodeToString(out);
    }
    private static boolean verifyPassword(String password,String encoded){
        try{
            String[] p=encoded.split("\\$");if(p.length<6||!p[1].equals("argon2id"))return false;
            String[] params=p[3].split(",");int m=Integer.parseInt(params[0].substring(2)),t=Integer.parseInt(params[1].substring(2)),par=Integer.parseInt(params[2].substring(2));
            byte[] salt=Base64.getDecoder().decode(p[4]), expected=Base64.getDecoder().decode(p[5]);
            return MessageDigest.isEqual(expected,argon(password,salt,m,t,par));
        }catch(Exception e){return false;}
    }
    private static byte[] argon(String password,byte[] salt,int memory,int iterations,int parallelism){
        Argon2Parameters params=new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id).withVersion(Argon2Parameters.ARGON2_VERSION_13).withSalt(salt).withMemoryAsKB(memory).withIterations(iterations).withParallelism(parallelism).build();
        Argon2BytesGenerator gen=new Argon2BytesGenerator();gen.init(params);byte[] out=new byte[32];gen.generateBytes(password.toCharArray(),out);return out;
    }

    private static String tokenHash(String token){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static void validateUsername(String u){if(u==null||!u.matches("[A-Za-z0-9._-]{3,32}"))throw new IllegalArgumentException("Username must be 3-32 characters using letters, numbers, dot, underscore, or dash.");}
    private static void validatePassword(String p){if(p==null||p.length()<10)throw new IllegalArgumentException("Password must be at least 10 characters.");if(p.length()>256)throw new IllegalArgumentException("Password is too long.");}
    private static void writeAtomic(Path p,String s)throws IOException{Files.createDirectories(p.getParent());Path t=p.resolveSibling(p.getFileName()+".tmp");Files.writeString(t,s,StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);try{Files.move(t,p,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(t,p,StandardCopyOption.REPLACE_EXISTING);}}
    private static boolean isLoopback(HttpExchange e){try{InetAddress a=e.getRemoteAddress().getAddress();return a!=null&&a.isLoopbackAddress();}catch(Exception x){return false;}}
    private static boolean isWindows(){return System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("win");}

    record LoginResult(String username,String token,long maxAgeSeconds,long expiresAt,boolean persistent){}
    private record Credential(String username,String hash){}
    private static final class Session{final String username;final long createdAt;final long expiresAt;volatile long lastSeen;Session(String username,long createdAt,long expiresAt,long lastSeen){this.username=username;this.createdAt=createdAt;this.expiresAt=expiresAt;this.lastSeen=lastSeen;}}
    private static final class Attempt{int failures;long lockedUntil;}
}
