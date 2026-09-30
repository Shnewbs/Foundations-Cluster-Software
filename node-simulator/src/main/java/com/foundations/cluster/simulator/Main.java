package com.foundations.cluster.simulator;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Locale;

public final class Main {
    public static void main(String[] args) throws Exception {
        String manager=env("FOUNDATIONS_CLUSTER_MANAGER_URL","http://127.0.0.1:8765");
        String token=env("FOUNDATIONS_CLUSTER_AGENT_TOKEN","");
        String id=env("FOUNDATIONS_CLUSTER_WORKER_ID","sim-worker-001");
        int port=Integer.parseInt(env("FOUNDATIONS_CLUSTER_WORKER_PORT","25599"));
        int players=intArg(args,"--players",12);
        double mspt=doubleArg(args,"--mspt",18.5);
        if(token.isBlank()) throw new IllegalStateException("FOUNDATIONS_CLUSTER_AGENT_TOKEN is required");
        HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        Runtime.getRuntime().addShutdownHook(new Thread(()->post(client,manager+"/api/agent/offline",token,"{\"workerId\":\""+id+"\"}")));
        post(client,manager+"/api/agent/register",token,String.format(Locale.ROOT,
                "{\"workerId\":\"%s\",\"port\":%d,\"warm\":false,\"ready\":true,\"nodeVersion\":\"0.0.5a-sim\",\"minecraftVersion\":\"1.21.1\",\"players\":%d,\"maxPlayers\":100}",id,port,players));
        System.out.println("Simulating "+id+" -> "+manager+"; Ctrl+C to stop");
        long started=System.currentTimeMillis();
        while(true){
            long used=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory(),max=Runtime.getRuntime().maxMemory();
            String body=String.format(Locale.ROOT,
                    "{\"workerId\":\"%s\",\"port\":%d,\"players\":%d,\"maxPlayers\":100,\"mspt\":%.2f,\"msptP95\":%.2f,\"msptP99\":%.2f,\"heapUsed\":%d,\"heapMax\":%d,\"loadedChunks\":%d,\"dimensions\":3,\"threadCount\":%d,\"uptimeMillis\":%d,\"nodeVersion\":\"0.0.5a-sim\",\"minecraftVersion\":\"1.21.1\",\"ready\":true}",
                    id,port,players,mspt,mspt*1.2,mspt*1.4,used,max,400+players*20,Thread.activeCount(),System.currentTimeMillis()-started);
            post(client,manager+"/api/agent/heartbeat",token,body);
            Thread.sleep(2000);
        }
    }
    static void post(HttpClient c,String url,String token,String body){try{HttpRequest r=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3)).header("Content-Type","application/json").header("X-Foundations-Agent-Token",token).POST(HttpRequest.BodyPublishers.ofString(body)).build();HttpResponse<String> resp=c.send(r,HttpResponse.BodyHandlers.ofString());if(resp.statusCode()>=300)System.err.println(resp.statusCode()+" "+resp.body());}catch(Exception e){System.err.println("heartbeat failed: "+e.getMessage());}}
    static String env(String k,String d){String v=System.getenv(k);return v==null||v.isBlank()?d:v;}
    static int intArg(String[]a,String k,int d){for(int i=0;i<a.length-1;i++)if(a[i].equals(k))return Integer.parseInt(a[i+1]);return d;}
    static double doubleArg(String[]a,String k,double d){for(int i=0;i<a.length-1;i++)if(a[i].equals(k))return Double.parseDouble(a[i+1]);return d;}
}
