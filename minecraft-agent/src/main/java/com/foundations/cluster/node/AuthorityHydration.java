package com.foundations.cluster.node;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Per-process region restoration acknowledgements, independent of ACTIVE/RECOVERING labels. */
final class AuthorityHydration {
    private Map<String,Long> assigned=Map.of();
    private final Map<String,Long> ready=new HashMap<>();
    synchronized void assign(Map<String,Long> epochs) {
        assigned=Map.copyOf(epochs);
        ready.entrySet().removeIf(e -> !Objects.equals(assigned.get(e.getKey()),e.getValue()));
    }
    synchronized Map<String,Long> pending() {
        Map<String,Long> pending=new HashMap<>(assigned);
        pending.entrySet().removeIf(e -> Objects.equals(ready.get(e.getKey()),e.getValue()));
        return Map.copyOf(pending);
    }
    synchronized boolean ready(String id,long epoch){return Objects.equals(assigned.get(id),epoch)&&Objects.equals(ready.get(id),epoch);}
    synchronized boolean current(Map<String,Long> request) {
        return !request.isEmpty()&&request.entrySet().stream().allMatch(e -> Objects.equals(assigned.get(e.getKey()),e.getValue())&&!Objects.equals(ready.get(e.getKey()),e.getValue()));
    }
    synchronized void complete(Map<String,Long> request) {
        if(!current(request))throw new IllegalStateException("Authority changed while restoring regions");
        ready.putAll(request);
    }
    synchronized String report(){return token(ready);}
    static String token(Map<String,Long> epochs) {
        return epochs.entrySet().stream().sorted(Map.Entry.comparingByKey())
            .map(e -> Base64.getUrlEncoder().withoutPadding().encodeToString(e.getKey().getBytes(StandardCharsets.UTF_8))+"|"+e.getValue())
            .collect(java.util.stream.Collectors.joining(";"));
    }
}
