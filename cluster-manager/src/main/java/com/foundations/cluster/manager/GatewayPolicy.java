package com.foundations.cluster.manager;
import java.io.IOException;
import java.nio.file.*;

final class GatewayPolicy {
    private GatewayPolicy(){}
    /** Read only the advanced section; absent/ambiguous configuration is unknown. */
    static Boolean readLaunchPolicy(Path file){
        try{
            boolean advanced=false;Boolean value=null;
            for(String raw:Files.readAllLines(file)){
                String line=raw.split("#",2)[0].trim();
                if(line.startsWith("[")){advanced=line.equals("[advanced]");continue;}
                if(advanced&&line.matches("seamless-server-switches\\s*=.*")){
                    if(value!=null)return null;
                    String text=line.substring(line.indexOf('=')+1).trim();
                    if(!text.equals("true")&&!text.equals("false"))return null;value=Boolean.valueOf(text);
                }
            }return value;
        }catch(IOException e){return null;}
    }
}
