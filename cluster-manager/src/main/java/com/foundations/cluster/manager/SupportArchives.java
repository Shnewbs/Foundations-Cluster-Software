package com.foundations.cluster.manager;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

/** Bounded diagnostic captures. Never a substitute for a quiesced world backup. */
final class SupportArchives implements AutoCloseable {
    interface Snapshots { Map<String,byte[]> capture(int limit) throws IOException; }
    private static final int MIB=1024*1024;
    private static final String NAME="Foundations-support-[0-9]{8}-[0-9]{6}-[a-f0-9]{8}\\.zip";
    private final ManagerConfig cfg;
    private final ClusterLog log;
    private final Snapshots snapshots;
    private final List<String> secrets;
    private volatile List<String> captureSecrets;
    private final ScheduledExecutorService executor=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"foundations-support");t.setDaemon(true);return t;});
    private volatile boolean busy;
    private volatile String lastError="",lastCreated="";
    private volatile long lastAttempt=System.currentTimeMillis();
    SupportArchives(ManagerConfig cfg,ClusterLog log,Snapshots snapshots,List<String> secrets){
        this.cfg=cfg;this.log=log;this.snapshots=snapshots;this.secrets=List.copyOf(secrets);this.captureSecrets=this.secrets;
    }
    void start(){executor.scheduleWithFixedDelay(this::scheduled,10,30,TimeUnit.SECONDS);}
    private void scheduled(){
        int minutes=bounded("support.intervalMinutes",0,0,10080);
        if(minutes>0&&System.currentTimeMillis()-lastAttempt>=minutes*60000L&&!cfg.lifecycle.shuttingDown()){
            try{request("scheduled","");}catch(IllegalStateException ignored){}
        }
    }
    synchronized boolean request(String reason,String report){
        if(!Set.of("manual","test-failure","scheduled").contains(reason))throw new IllegalArgumentException("Unknown capture reason");
        if(reason.equals("test-failure")&&!cfg.getBoolean("support.onTestFailure",false))return false;
        if(report.length()>262144)throw new IllegalArgumentException("Test report exceeds 256 KiB");
        if(busy)throw new IllegalStateException("A support capture is already running");
        busy=true;lastError="";lastAttempt=System.currentTimeMillis();
        executor.execute(()->{try{lastCreated=create(reason,report).getFileName().toString();log.info("Support ZIP ready: support-bundles/"+lastCreated);}catch(Exception ex){lastError=clean(ex.getMessage());log.warn("Support ZIP failed: "+lastError);}finally{busy=false;}});
        return true;
    }
    private int bounded(String key,int def,int min,int max){return Math.max(min,Math.min(max,cfg.getInt(key,def)));}
    Path directory()throws IOException{
        Path dir=cfg.home().resolve("support-bundles");safePath(dir);Files.createDirectories(dir);return dir;
    }
    private void safePath(Path path)throws IOException{
        Path home=cfg.home().toAbsolutePath().normalize(),p=path.toAbsolutePath().normalize();
        if(!p.startsWith(home))throw new IOException("Path outside Cluster");
        for(Path q=p;q!=null&&q.startsWith(home);q=q.getParent())if(Files.isSymbolicLink(q))throw new IOException("Symbolic links are excluded");
    }
    List<Path> archives()throws IOException{
        try(var paths=Files.list(directory())){return paths.filter(p->p.getFileName().toString().matches(NAME)&&Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).sorted(Comparator.comparingLong(SupportArchives::modified).thenComparing(p->p.getFileName().toString()).reversed()).toList();}
    }
    Path download(String name)throws IOException{
        if(!name.matches(NAME))throw new IOException("Invalid support ZIP name");
        Path p=directory().resolve(name);safePath(p);
        if(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))throw new FileNotFoundException("Support ZIP is no longer available");
        return p;
    }
    String statusJson()throws IOException{
        StringBuilder b=new StringBuilder("{\"busy\":").append(busy).append(",\"lastError\":").append(Json.q(lastError)).append(",\"lastCreated\":").append(Json.q(lastCreated)).append(",\"onTestFailure\":").append(cfg.getBoolean("support.onTestFailure",false)).append(",\"archives\":[");boolean first=true;
        for(Path p:archives().stream().limit(50).toList()){if(!first)b.append(',');first=false;b.append("{\"name\":").append(Json.q(p.getFileName().toString())).append(",\"bytes\":").append(Files.size(p)).append(",\"createdAt\":").append(Files.getLastModifiedTime(p).toMillis()).append('}');}
        return b.append("]}").toString();
    }
    private String clean(String text){
        String result=Objects.toString(text,"");
        for(String secret:captureSecrets)if(secret!=null&&!secret.isBlank())result=result.replace(secret,"[REDACTED]");
        result=result.replaceAll("(?i)(authorization[\\s\"':=]+)(?:bearer|basic)\\s+[^\\s\";,]+","$1[REDACTED]");
        return DiagnosticBundle.redact(result);
    }
    Path create(String reason,String report)throws Exception{
        String name="Foundations-support-"+DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now())+"-"+UUID.randomUUID().toString().substring(0,8)+".zip";
        Path dir=directory(),partial=dir.resolve(name+".partial"),complete=dir.resolve(name);
        ArrayList<String> activeSecrets=new ArrayList<>(secrets);
        Path forwarding=cfg.home().resolve("velocity/forwarding.secret");safePath(forwarding);
        if(Files.isRegularFile(forwarding,LinkOption.NOFOLLOW_LINKS))try(var in=Files.newInputStream(forwarding)){String value=new String(in.readNBytes(4096),StandardCharsets.UTF_8).trim();if(!value.isBlank())activeSecrets.add(value);}
        captureSecrets=List.copyOf(activeSecrets);
        List<String> notes=new ArrayList<>();
        try{
            try(var zip=new ZipOutputStream(Files.newOutputStream(partial,StandardOpenOption.CREATE_NEW),StandardCharsets.UTF_8)){
                zip.setLevel(3);Writer writer=new Writer(zip,bounded("support.maxArchiveMiB",128,16,512)*(long)MIB,notes);
                writer.add("README.txt",("Foundations "+ClusterVersion.PACKAGE_REVISION+" support capture\nReason: "+reason+"\n"
                    +"Diagnostic evidence, NOT a complete or transactionally consistent world backup.\n"
                    +"Journal exports merge snapshot + WAL under each journal lock; journals may have different capture times.\n"
                    +"Logs are bounded live tails. See manifest.json for omissions and truncation.\n"
                    +"Security vault, sessions, raw configuration, player profiles and world files are excluded. Known Manager tokens and credential labels are redacted in text.\n"
                    +"Logs and block-entity data may contain player names, chat, signs, coordinates and local paths. Review before sharing. No files are uploaded automatically.\n"
                    +"For a restorable private backup, stop the cluster and run BACKUP_CLUSTER.bat.\n").getBytes(StandardCharsets.UTF_8));
                writer.add("test-report.txt",clean(report).getBytes(StandardCharsets.UTF_8));
                for(var e:snapshots.capture(bounded("support.maxRuntimeMiB",32,1,128)*MIB).entrySet())writer.add(e.getKey(),e.getValue());
                for(String file:List.of("region-authority.tsv","world-environment.properties","world-identity.txt","world-seed.txt","worker-pins.properties"))captureFile(writer,cfg.home().resolve("runtime").resolve(file),"runtime/"+file,2*MIB,false);
                Path logs=cfg.home().resolve("logs");safePath(logs);
                if(Files.isDirectory(logs,LinkOption.NOFOLLOW_LINKS))try(var paths=Files.walk(logs,2)){
                    List<Path> candidates=paths.filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)&&p.getFileName().toString().matches("[a-zA-Z0-9._-]+\\.log(?:\\.[0-9]+)?")).sorted(Comparator.comparingLong(SupportArchives::modified).reversed()).toList();
                    if(candidates.size()>80)notes.add("Log limit: only the 80 most recently modified log files were considered");
                    for(Path p:candidates.stream().limit(80).toList())captureFile(writer,p,"logs/"+logs.relativize(p).toString().replace('\\','/'),bounded("support.maxLogMiB",4,1,32)*MIB,true);
                }
                writer.manifest(reason);
            }
            try{Files.move(partial,complete,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException ex){Files.move(partial,complete);}
            List<Path> saved=archives().stream().filter(p->!p.equals(complete)).toList();int keep=bounded("support.keepCount",5,1,50);
            for(Path p:saved.subList(Math.min(keep-1,saved.size()),saved.size()))Files.deleteIfExists(p);
            return complete;
        }finally{Files.deleteIfExists(partial);}
    }
    private static long modified(Path p){try{return Files.getLastModifiedTime(p,LinkOption.NOFOLLOW_LINKS).toMillis();}catch(IOException ex){return 0;}}
    private void captureFile(Writer writer,Path p,String entry,int limit,boolean tail)throws IOException{
        try{
            safePath(p);if(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))return;
            try(SeekableByteChannel channel=Files.newByteChannel(p,Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS))){
                long size=channel.size();if(size>limit&&!tail){writer.notes.add(entry+": omitted; exceeds per-file limit");return;}
                long offset=tail?Math.max(0,size-limit):0;channel.position(offset);
                ByteBuffer b=ByteBuffer.allocate((int)Math.min(limit,size-offset));while(b.hasRemaining()&&channel.read(b)>0){}
                String content=new String(b.array(),0,b.position(),StandardCharsets.UTF_8);
                if(offset>0){int nl=content.indexOf('\n');content=nl<0?"":content.substring(nl+1);writer.notes.add(entry+": truncated to newest complete lines within "+limit+" bytes; original="+size);}
                // A live last line may still be in flight (including a credential value).
                if(tail&&!content.endsWith("\n")){int nl=content.lastIndexOf('\n');content=nl<0?"":content.substring(0,nl+1);writer.notes.add(entry+": incomplete final line omitted");}
                writer.add(entry,clean(content).getBytes(StandardCharsets.UTF_8));
            }
        }catch(IOException ex){writer.notes.add(entry+": unavailable ("+clean(ex.getMessage())+")");}
    }
    private static final class Writer {
        final ZipOutputStream zip;final long budget;final List<String> notes;final List<String> records=new ArrayList<>();long total;
        Writer(ZipOutputStream zip,long budget,List<String> notes){this.zip=zip;this.budget=budget;this.notes=notes;}
        void add(String name,byte[] data)throws IOException{
            if(data==null){notes.add(name+": omitted; snapshot exceeds per-file limit");return;}
            if(total+data.length>budget){notes.add(name+": omitted; archive uncompressed size limit reached");return;}
            zip.putNextEntry(new ZipEntry(name));zip.write(data);zip.closeEntry();total+=data.length;
            try{records.add("{\"path\":"+Json.q(name)+",\"bytes\":"+data.length+",\"sha256\":"+Json.q(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)))+"}");}catch(Exception ex){throw new IOException(ex);}
        }
        void manifest(String reason)throws IOException{
            String text="{\"package\":"+Json.q(ClusterVersion.PACKAGE_REVISION)+",\"kind\":\"support-diagnostics\",\"createdAt\":"+Json.q(Instant.now().toString())+",\"reason\":"+Json.q(reason)+",\"uncompressedBytes\":"+total+",\"files\":["+String.join(",",records)+"],\"notes\":["+String.join(",",notes.stream().map(Json::q).toList())+"]}";
            zip.putNextEntry(new ZipEntry("manifest.json"));zip.write(text.getBytes(StandardCharsets.UTF_8));zip.closeEntry();
        }
    }
    @Override public void close(){executor.shutdown();try{if(!executor.awaitTermination(5,TimeUnit.SECONDS))executor.shutdownNow();}catch(InterruptedException ex){executor.shutdownNow();Thread.currentThread().interrupt();}}
}
