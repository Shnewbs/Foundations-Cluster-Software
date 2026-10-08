package com.foundations.cluster.manager;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

/** Metadata fixtures test Manager gates/deployment, not Minecraft/NeoForge gameplay. */
public final class NodeStartupSelfTest {
 static int checks;
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);checks++;}
 static void jar(Path file,String version)throws Exception{
  Files.createDirectories(file.getParent());
  try(var out=new JarOutputStream(Files.newOutputStream(file))){
   for(var e:Map.of("META-INF/neoforge.mods.toml","modLoader=\"javafml\"\n[[mods]]\nmodId=\"foundations_cluster_node\"\nversion = \""+version+"\"\n[[dependencies.foundations_cluster_node]]\nmodId=\"minecraft\"\nversionRange=\"[1.21.1,1.21.2)\"\n", "com/foundations/cluster/node/FoundationsClusterNode.class","NOT A GAME CLASS: TEST FIXTURE ONLY", "foundations_cluster_node.mixins.json","{}").entrySet()){
    out.putNextEntry(new JarEntry(e.getKey()));out.write(e.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));out.closeEntry();
   }
  }
 }
 static void waitFor(NodeAgentManager node)throws Exception{long until=System.nanoTime()+10_000_000_000L;while(node.building()&&System.nanoTime()<until)Thread.sleep(10);check(!node.building(),"async build finished");}
 public static void main(String[]args)throws Exception{
  Properties build=new Properties();try(var in=Files.newInputStream(Path.of(args[0],"minecraft-agent/gradle.properties"))){build.load(in);}
  check(ClusterVersion.NODE_VERSION.equals(build.getProperty("mod_version")),"Manager/Gradle release parity");
  check(ClusterVersion.NODE_VERSION.equals("0.0.6a.R13"),"R13 required");
  check(ClusterVersion.PACKAGE_REVISION.equals("0.0.6a.R19-HF13"),"repair package marker");
  Path home=Files.createTempDirectory("foundations-nodefix-");
  try{
   Files.createDirectories(home.resolve("config"));Files.writeString(home.resolve("config/cluster.properties"),"worker.templateDir=custom template\nworld.seed=1234\nworld.identity.enforce=false\nworld.environment.enabled=false\n");
   var cfg=ManagerConfig.load(home);var log=new ClusterLog(home.resolve("logs/test.log"),100);var templates=new TemplateManager(cfg,log);var node=new NodeAgentManager(cfg,templates,log);
   String current="foundations_cluster_node-0.0.6a.R13.jar",old="foundations_cluster_node-0.0.6a.R11.jar";
   Path cache=home.resolve("minecraft-agent/build/libs"),mods=templates.templateDir().resolve("mods"),worker=home.resolve("workers/worker-001");
   check(!node.ready()&&node.state().equals("MISSING"),"missing Node blocks startup");
   jar(mods.resolve(old),"0.0.6a.R11");jar(cache.resolve(old),"0.0.6a.R11");
   check(node.state().equals("OUTDATED"),"R11 template is outdated");
   check(node.builtJar()==null,"R11 cache is not R13");
   jar(cache.resolve(current),"0.0.6a.R11");check(node.builtJar()==null,"R11 renamed to R13 rejected");
   try{node.installBuiltJarIfPresent();throw new AssertionError("invalid artifact accepted");}catch(IOException e){check(e.getMessage().contains("Expected Cluster Node 0.0.6a.R13")&&e.getMessage().contains("embedded Node version is 0.0.6a.R11"),"specific wrong-metadata diagnostic");}
   check(Files.exists(mods.resolve(old)),"failed preflight preserves installed old Node");
   Files.writeString(cache.resolve(current),"broken zip");check(node.builtJar()==null,"corrupt artifact rejected");
   jar(cache.resolve(current),"0.0.6a.R13");check(node.mayAutoRetry(),"valid build usable without Gradle/script");
   node.buildAndInstallAsync();waitFor(node);
   check(node.ready()&&node.state().equals("READY"),"R13 satisfies startup");
   check(node.installedJar().equals(mods.resolve(current)),"configured template used");
   check(!Files.exists(home.resolve("server-template")),"default template not created with custom config");
   check(!Files.exists(mods.resolve(old)),"old template duplicate removed");
   check(Files.isDirectory(home.resolve(".foundations_update_backups")),"old Node backed up");
   check(node.statusJson().contains("\"version\":\"0.0.6a.R13\"")&&node.statusJson().contains("\"outdated\":false"),"dashboard reports current R13");
   long before=Files.getLastModifiedTime(mods.resolve(current)).toMillis();node.buildAndInstallAsync();check(!node.building()&&Files.getLastModifiedTime(mods.resolve(current)).toMillis()==before,"already installed does not rebuild");
   Files.createDirectories(worker.resolve("mods"));jar(worker.resolve("mods/"+old),"0.0.6a.R11");Files.writeString(worker.resolve("mods/unrelated.jar"),"user mod");Files.createDirectories(worker.resolve("world"));Files.writeString(worker.resolve("world/level.dat"),"world fixture");
   try(WorkerManager workers=new WorkerManager(cfg,new TokenStore(home.resolve("runtime")),log,templates,node)){
    var sync=WorkerManager.class.getDeclaredMethod("syncClusterRuntimeFiles",Path.class);sync.setAccessible(true);sync.invoke(workers,worker);
    check(NodeArtifact.isVersion(worker.resolve("mods/"+current),"0.0.6a.R13"),"stored worker receives R13");
    check(!Files.exists(worker.resolve("mods/"+old)),"stored worker old duplicate removed");
    jar(mods.resolve(current),"0.0.6a.R11");
    try { sync.invoke(workers,worker); throw new AssertionError("Renamed R11 accepted for stored worker"); }
    catch(java.lang.reflect.InvocationTargetException e) { check(e.getCause() instanceof IOException,"stored worker rejects template metadata mismatch"); }
    check(NodeArtifact.isVersion(worker.resolve("mods/"+current),"0.0.6a.R13"),"failed worker sync preserves installed R13");
    Files.copy(cache.resolve(current),mods.resolve(current),StandardCopyOption.REPLACE_EXISTING);
    check(Files.readString(worker.resolve("mods/unrelated.jar")).equals("user mod")&&Files.readString(worker.resolve("world/level.dat")).equals("world fixture"),"world/unrelated mod unchanged");
   }
   Files.delete(mods.resolve(current));Files.delete(cache.resolve(current));
   Files.writeString(home.resolve("minecraft-agent/build_mod.sh"),"#!/bin/sh\nprintf '%s' \"$*\" > build-args.txt\nexit 0\n");
   Files.writeString(home.resolve("minecraft-agent/build_mod.bat"),"@echo off\r\ntype nul > build-args.txt\r\nif not \"%*\"==\"\" echo %* > build-args.txt\r\nexit /b 0\r\n");
   Files.createDirectories(home.resolve("tools"));Files.writeString(home.resolve("tools/ensure_gradle.ps1"),"# prerequisite fixture; build script does not invoke Gradle\n");
   for(int n=0;n<3;n++){node.buildAndInstallAsync();waitFor(node);}
   check(!node.mayAutoRetry()&&node.statusJson().contains("\"automaticBuildPaused\":true"),"three failed outputs pause automatic rebuilds");
   check(node.statusJson().contains("Expected Cluster Node 0.0.6a.R13")&&node.statusJson().contains(old),"diagnostic includes expected/found files");
   check(Files.readString(home.resolve("build-args.txt")).isEmpty(),"Manager build script is not passed --install");
   jar(cache.resolve(current),"0.0.6a.R13");check(node.mayAutoRetry(),"valid artifact unblocks paused auto builds");node.buildAndInstallAsync();waitFor(node);
   check(node.ready()&&node.statusJson().contains("\"buildFailures\":0"),"successful install resets failure count");
  }finally{try(var paths=Files.walk(home)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
  System.out.println("Node startup/deployment: PASS ("+checks+" checks; metadata/process fixtures, no Minecraft gameplay)");
 }
}
