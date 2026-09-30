package com.foundations.cluster.node;

import java.util.*;
import java.util.function.Predicate;

/** Server-thread-only, bounded soft interests. Does not retain sessions, players or worlds. */
final class RemoteTickLeases {
    record Key(String dimension,int x,int z) {}
    record Lease(Key key,String region,long epoch,long expiresNanos) {}
    enum Result { ADDED, RENEWED, FULL }
    private final int limit;private final long ttlNanos;
    private final Map<Key,Lease> leases=new HashMap<>();
    RemoteTickLeases(int limit,long ttlNanos) {
        if(limit<1||ttlNanos<1)throw new IllegalArgumentException("positive limit/TTL required");
        this.limit=limit;this.ttlNanos=ttlNanos;
    }
    Result renew(Key key,String region,long epoch,long now) {
        Objects.requireNonNull(key);Objects.requireNonNull(region);
        if(epoch<=0)throw new IllegalArgumentException("positive authority epoch required");
        Lease old=leases.get(key);
        if(old!=null&&(!old.region().equals(region)||old.epoch()!=epoch))
            throw new IllegalStateException("Release obsolete authority ticket before reacquiring");
        if(old==null&&leases.size()>=limit)return Result.FULL;
        leases.put(key,new Lease(key,region,epoch,now+ttlNanos));
        return old==null?Result.ADDED:Result.RENEWED;
    }
    List<Lease> expire(long now,Predicate<Lease> authorized) {
        List<Lease> out=new ArrayList<>();
        var iterator=leases.values().iterator();
        while(iterator.hasNext()) {
            Lease value=iterator.next();
            if(now-value.expiresNanos()>=0||!authorized.test(value)){out.add(value);iterator.remove();}
        }
        return out;
    }
    Lease get(Key key){return leases.get(key);}
    void remove(Key key){leases.remove(key);}
    int size(){return leases.size();}
    List<Lease> clear(){var out=List.copyOf(leases.values());leases.clear();return out;}
}
