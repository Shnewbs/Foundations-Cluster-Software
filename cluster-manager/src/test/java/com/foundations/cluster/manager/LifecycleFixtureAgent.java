package com.foundations.cluster.manager;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.*;
/** Disposable Java process for lifecycle HTTP tests. This is not a Minecraft simulation. */
public final class LifecycleFixtureAgent {
 public static void main(String[] args)throws Exception{
  String worker=System.getenv("FOUNDATIONS_CLUSTER_WORKER_ID"),manager=System.getenv("FOUNDATIONS_CLUSTER_MANAGER_URL"),token=System.getenv("FOUNDATIONS_CLUSTER_AGENT_TOKEN");
  var client=HttpClient.newHttpClient();var heartbeat=Executors.newSingleThreadScheduledExecutor();
  String body="{\"workerId\":\""+worker+"\",\"port\":"+System.getenv("FOUNDATIONS_CLUSTER_WORKER_PORT")+",\"ready\":true,\"players\":0,\"nodeVersion\":\""+ClusterVersion.NODE_VERSION+"\",\"worldSeed\":1234}";
  java.util.function.Consumer<String> send=path->{try{var r=client.send(HttpRequest.newBuilder(URI.create(manager+path)).timeout(Duration.ofSeconds(3)).header("Content-Type","application/json").header("X-Foundations-Agent-Token",token).POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.discarding());if(r.statusCode()!=200)System.out.println("Fixture HTTP "+r.statusCode());}catch(Exception e){System.out.println("Fixture disconnected");}};
  try{send.accept("/api/agent/register");heartbeat.scheduleWithFixedDelay(()->send.accept("/api/agent/heartbeat"),0,250,TimeUnit.MILLISECONDS);
   try(var reader=new BufferedReader(new InputStreamReader(System.in))){for(String line;(line=reader.readLine())!=null;)if(line.equals("stop"))break;}
  }finally{heartbeat.shutdownNow();send.accept("/api/agent/offline");}
 }
}
