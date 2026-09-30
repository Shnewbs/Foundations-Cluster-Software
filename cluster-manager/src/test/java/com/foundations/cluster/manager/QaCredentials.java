package com.foundations.cluster.manager;
import java.nio.file.*;
/** Test fixture only; generates isolated credentials for synthetic agent tests. */
public final class QaCredentials {
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]);var tokens=new TokenStore(root.resolve("runtime"));
        Files.writeString(Path.of(args[1]),"{\"agent\":"+Json.q(tokens.agentToken)+",\"gateway\":"+Json.q(tokens.gatewayToken)+"}");
    }
}
