package com.foundations.cluster.manager;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class R17SelfTest {
    private static int checks;
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);checks++;}
    private static void write(Path root,String path,String content)throws IOException{
        Path file=root.resolve(path);Files.createDirectories(file.getParent());Files.writeString(file,content);
    }
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("foundations-r17-");
        write(root,"config/cluster.properties","world.seed=1234\nstartup.autoStartWorkers=false\ntemplate.autoBootstrap=false\nnodeAgent.required=false\ngateway.enabled=true\nworld.identity.enforce=false\nscaling.memory.totalBudgetGb=64\n");
        write(root,"server-template/libraries/net/neoforged/neoforge/21.1.250/unix_args.txt","");
        write(root,"server-template/libraries/net/neoforged/neoforge/21.1.250/win_args.txt","");
        write(root,"server-template/eula.txt","eula=true\n");
        Files.createDirectories(root.resolve("server-template/mods"));
        var cfg=ManagerConfig.load(root);var log=new ClusterLog(root.resolve("logs/manager.log"),100);
        var tokens=new TokenStore(root.resolve("runtime"));var templates=new TemplateManager(cfg,log);var node=new NodeAgentManager(cfg,templates,log);
        try(var workers=new WorkerManager(cfg,tokens,log,templates,node);var startup=new StartupController(cfg,templates,node,workers,log)){
            try{workers.spawn(false);throw new AssertionError("Spawn accepted missing forwarding dependency");}
            catch(IOException ex){check(ex.getMessage().contains("logs/velocity-bootstrap.log"),"Missing dependency error does not direct to bootstrap log");}
            try(var paths=Files.list(root.resolve("workers"))){check(paths.findAny().isEmpty(),"Blocked spawn created an orphan worker directory");}
            check(startup.statusJson().contains("\"gatewayPrerequisitesReady\":false"),"Startup allowed missing forwarding dependency");
            write(root,"server-template/mods/proxy-compatible-forge-1.2.2.jar.download","incomplete download");
            try{workers.ensureVelocityForwardingReady(null);throw new AssertionError("Download staging file treated as a mod");}
            catch(IOException ex){check(ex.getMessage().contains("Proxy Compatible Forge JAR"),"Wrong missing dependency error");}
            write(root,"server-template/mods/proxy-compatible-forge-1.2.2.jar","fixture only");
            write(root,"server-template/config/proxy-compatible-forge.toml","enabled = true\nmode = \"MODERN\"\nsecret = \"fixture-only\"\n");
            write(root,"velocity/velocity.jar","fixture only");
            write(root,"velocity/plugins/foundations_velocity_gateway-0.0.6a.R16.jar","fixture only");
            check(startup.statusJson().contains("\"gatewayPrerequisitesReady\":false"),"Missing forwarding secret ignored");
            write(root,"velocity/forwarding.secret","fixture-only");
            check(startup.statusJson().contains("\"gatewayPrerequisitesReady\":true"),"Complete forwarding prerequisites rejected");
            workers.ensureVelocityForwardingReady(root.resolve("workers/fixture"));
            check(Files.readString(root.resolve("workers/fixture/forwarding.secret")).equals("fixture-only"),"Forwarding secret was not synchronized");
            check(Files.isRegularFile(root.resolve("workers/fixture/mods/proxy-compatible-forge-1.2.2.jar")),"Forwarding mod was not synchronized");
            write(root,"velocity/forwarding.secret","mismatch");
            try{workers.ensureVelocityForwardingReady(null);throw new AssertionError("Mismatched secret accepted");}
            catch(IOException ex){check(ex.getMessage().contains("does not match"),"Wrong forwarding-secret error");}
        }
        try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}
        System.out.println("R17 forwarding checks: PASS ("+checks+" assertions)");
    }
}
