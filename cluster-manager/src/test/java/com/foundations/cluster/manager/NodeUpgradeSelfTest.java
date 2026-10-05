package com.foundations.cluster.manager;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.regex.*;

/** Exercise real startup selection and stored-worker synchronization with stale caches. */
public final class NodeUpgradeSelfTest {
 static int checks;
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);checks++;}
 public static void main(String[] args)throws Exception{
  Path source=Path.of(args[0]);Properties properties=new Properties();
  try(var in=Files.newInputStream(source.resolve("minecraft-agent/gradle.properties"))){properties.load(in);}
  String expected=properties.getProperty("mod_version");String current="foundations_cluster_node-"+expected+".jar",old="foundations_cluster_node-0.0.6a.R8.jar";
  byte[] currentBytes;
  if(args.length>1 && args[1].equals("--fixture")) {
   Path fixture=Files.createTempFile("foundations-node-metadata-", ".jar");
   try {NodeStartupSelfTest.jar(fixture,expected);currentBytes=Files.readAllBytes(fixture);}finally{Files.delete(fixture);}
   System.out.println("NodeUpgradeSelfTest: metadata fixture mode (not a game binary validation)");
  } else currentBytes=Files.readAllBytes(source.resolve("minecraft-agent/build/libs/"+current));
  try(var jar=new JarInputStream(new ByteArrayInputStream(currentBytes))){
   String metadata="";for(JarEntry e;(e=jar.getNextJarEntry())!=null;)if(e.getName().equals("META-INF/neoforge.mods.toml"))metadata=new String(jar.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
   check(metadata.replace(" ","").contains("version=\""+expected+"\""),"Packaged Node metadata disagrees with build version");
  }
  Path home=Files.createTempDirectory("foundations-node-upgrade-");
  try{
   Files.createDirectories(home.resolve("config"));Files.writeString(home.resolve("config/cluster.properties"),"world.seed=1234\nworld.identity.enforce=false\nworld.environment.enabled=false\n");
   Path cache=home.resolve("minecraft-agent/build/libs"),template=home.resolve("server-template/mods"),worker=home.resolve("workers/worker-001");
   Files.createDirectories(cache);Files.createDirectories(template);Files.createDirectories(worker.resolve("mods"));
   Files.write(cache.resolve(current),currentBytes);Files.writeString(cache.resolve(old),"stale cached R8 fixture");Files.write(template.resolve(current),currentBytes);
   Files.writeString(worker.resolve("mods/"+old),"stale worker R8 fixture");Files.writeString(worker.resolve("mods/unrelated-mod.jar"),"preserve user mod");Files.createDirectories(worker.resolve("world"));Files.writeString(worker.resolve("world/level.dat"),"preserve world");
   ManagerConfig cfg=ManagerConfig.load(home);ClusterLog log=new ClusterLog(home.resolve("logs/test.log"),100);TemplateManager templates=new TemplateManager(cfg,log);NodeAgentManager node=new NodeAgentManager(cfg,templates,log);
   check(node.version().equals(expected),"Manager expects "+node.version()+" but packaged Node is "+expected);
   check(node.ready(),"Updater-installed Node should satisfy startup without reinstalling stale cache");
   check(node.builtJar().equals(cache.resolve(current)),"Startup selected old cached Node");
   node.buildAndInstallAsync();check(node.installedJar().equals(template.resolve(current)),"Startup changed installed version");
   Files.delete(template.resolve(current));Files.writeString(template.resolve(old),"old installed template");
   node.installBuiltJarIfPresent();check(Arrays.equals(Files.readAllBytes(template.resolve(current)),currentBytes),"Template repair did not install bundled Node");
   check(!Files.exists(template.resolve(old)),"Template repair kept duplicate old Node");
   // Deliberately reintroduce stale template entries in both directory orders.
   try(WorkerManager workers=new WorkerManager(cfg,new TokenStore(home.resolve("runtime")),log,templates,node)){
    var sync=WorkerManager.class.getDeclaredMethod("syncClusterRuntimeFiles",Path.class);sync.setAccessible(true);
    for(int order=0;order<2;order++){
     Files.delete(template.resolve(current));Files.deleteIfExists(template.resolve(old));
     if(order==0)Files.writeString(template.resolve(old),"old first");
     Files.write(template.resolve(current),currentBytes);
     if(order==1)Files.writeString(template.resolve(old),"old last");
     sync.invoke(workers,worker);
     check(Arrays.equals(Files.readAllBytes(worker.resolve("mods/"+current)),currentBytes),"Stored worker received wrong Node");
     check(!Files.exists(worker.resolve("mods/"+old)),"Stored worker retained stale Node");
    }
    check(Files.readString(worker.resolve("mods/unrelated-mod.jar")).equals("preserve user mod"),"Unrelated mod changed");
    check(Files.readString(worker.resolve("world/level.dat")).equals("preserve world"),"World changed");
    Files.delete(template.resolve(current));
    try{sync.invoke(workers,worker);throw new AssertionError("Missing required Node silently accepted");}
    catch(java.lang.reflect.InvocationTargetException e){check(e.getCause() instanceof IOException,"Missing Node should fail before starting worker");}
    check(Arrays.equals(Files.readAllBytes(worker.resolve("mods/"+current)),currentBytes),"Failed sync removed working Node");
   }
  }finally{try(var paths=Files.walk(home)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
  System.out.println("Node upgrade lifecycle: PASS ("+checks+" checks)");
 }
}
