package com.foundations.cluster.manager;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
public final class SupportArchivesSelfTest {
 static int checks;
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);checks++;}
 static void rejects(Runnable r){try{r.run();throw new AssertionError("Expected validation failure");}catch(IllegalArgumentException e){checks++;}}
 public static void main(String[] args)throws Exception{
  Path home=Files.createTempDirectory("foundations-support-test-");
  try{
   Files.createDirectories(home.resolve("config"));Files.writeString(home.resolve("config/cluster.properties"),"support.keepCount=2\nsupport.maxLogMiB=1\n");
   ManagerConfig cfg=ManagerConfig.load(home);ClusterLog log=new ClusterLog(home.resolve("logs/test.log"),100);
   Files.createDirectories(home.resolve("runtime/security"));Files.writeString(home.resolve("runtime/security/admin.json"),"DO_NOT_EXPORT_VAULT");
   Files.writeString(home.resolve("config/private.properties"),"password=DO_NOT_EXPORT_CONFIG");
   Files.writeString(home.resolve("runtime/world-seed.txt"),"12345\n");
   Files.writeString(home.resolve("logs/worker-001.log"),"old\n"+"A".repeat(1100000)+"\nlast-marker known-secret-123 token=abc authorization: Bearer bearer-fixture\npartial-password=do-not-export");
   Files.writeString(home.resolve("logs/not-a-log.dat"),"DO_NOT_EXPORT_BINARY");
   Files.createSymbolicLink(home.resolve("logs/symlink.log"),home.resolve("runtime/security/admin.json"));
   Files.createDirectories(home.resolve("outside"));Files.writeString(home.resolve("outside/leak.log"),"DO_NOT_EXPORT_SYMLINK");Files.createSymbolicLink(home.resolve("logs/linked-dir"),home.resolve("outside"));
   try(var journal=new PersistentBlockJournal(cfg,log);var be=new PersistentBlockEntityJournal(cfg,log)){
    journal.record("foundations","minecraft:overworld","foundations/minecraft:overworld/r.0.0",1,65,15,43,"minecraft:fire;age=10");
    check(journal.diagnosticSnapshot(10)==null,"Oversized journal was not omitted");
    try(var support=new SupportArchives(cfg,log,limit->{Map<String,byte[]> m=new LinkedHashMap<>();m.put("runtime/persistent-blocks.tsv",journal.diagnosticSnapshot(limit));m.put("runtime/persistent-block-entities.tsv",be.diagnosticSnapshot(limit));return m;},List.of("known-secret-123"))){
     Path first=support.create("manual","FAIL chest known-secret-123");
     try(var z=new ZipFile(first.toFile())){
      check(z.getEntry("runtime/persistent-blocks.tsv")!=null,"Missing journal");
      check(z.getEntry("runtime/world-seed.txt")!=null,"Missing identity");
      check(z.getEntry("runtime/security/admin.json")==null,"Vault exported");
      check(z.stream().noneMatch(e->e.getName().contains("symlink")||e.getName().contains("linked-dir")),"Symlink exported");
      String logs=new String(z.getInputStream(z.getEntry("logs/worker-001.log")).readAllBytes(),StandardCharsets.UTF_8);
      check(logs.contains("last-marker")&&!logs.contains("known-secret-123")&&!logs.contains("bearer-fixture")&&!logs.contains("token=abc"),"Credential redaction or tail failed");
      check(!logs.contains("partial-password"),"Incomplete trailing credential exported");
      String manifest=new String(z.getInputStream(z.getEntry("manifest.json")).readAllBytes(),StandardCharsets.UTF_8);check(manifest.contains("truncated")&&manifest.contains("uncompressedBytes"),"Missing truncation manifest");
      for(var e:Collections.list(z.entries()))if(!e.getName().equals("manifest.json")){byte[] data=z.getInputStream(e).readAllBytes();String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));check(manifest.contains(sha),"Checksum missing: "+e.getName());}
     }
     Files.writeString(support.directory().resolve("user-backup.zip"),"keep");support.create("manual","");support.create("manual","");
     check(support.archives().size()==2,"Retention failed");check(Files.exists(support.directory().resolve("user-backup.zip")),"Retention deleted unrelated file");
     try{support.download("../config/private.properties");throw new AssertionError("Traversal accepted");}catch(java.io.IOException expected){checks++;}
     check(!support.request("test-failure","ignored"),"Auto capture was enabled by default");
     cfg.updateEditable(Map.of("support.onTestFailure","true","support.intervalMinutes","60","support.keepCount","1"));
     check(support.request("test-failure","FAIL fixture"),"Opt-in auto capture not accepted");
     long end=System.currentTimeMillis()+10000;while(support.statusJson().contains("\"busy\":true")&&System.currentTimeMillis()<end)Thread.sleep(20);
     check(!support.statusJson().contains("\"busy\":true")&&support.archives().size()==1,"Asynchronous auto capture or retention failed");
     cfg.updateEditable(Map.of("support.intervalMinutes","1"));
     var last=SupportArchives.class.getDeclaredField("lastAttempt");last.setAccessible(true);last.setLong(support,0);
     var scheduled=SupportArchives.class.getDeclaredMethod("scheduled");scheduled.setAccessible(true);scheduled.invoke(support);
     check(last.getLong(support)>0,"Due scheduled capture was not requested");
     end=System.currentTimeMillis()+10000;while(support.statusJson().contains("\"busy\":true")&&System.currentTimeMillis()<end)Thread.sleep(20);
     try(var z=new ZipFile(support.archives().getFirst().toFile())){check(new String(z.getInputStream(z.getEntry("manifest.json")).readAllBytes(),StandardCharsets.UTF_8).contains("\"reason\":\"scheduled\""),"Scheduled ZIP missing reason");}

    }
    rejects(()->{try{cfg.updateEditable(Map.of("support.keepCount","0"));}catch(java.io.IOException e){throw new RuntimeException(e);}});
    rejects(()->{try{cfg.updateEditable(Map.of("support.onTestFailure","maybe"));}catch(java.io.IOException e){throw new RuntimeException(e);}});
   }
   Files.delete(home.resolve("logs/symlink.log"));Files.delete(home.resolve("logs/linked-dir"));
  }finally{try(var paths=Files.walk(home)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
  System.out.println("Support archives: PASS ("+checks+" checks)");
 }
}
