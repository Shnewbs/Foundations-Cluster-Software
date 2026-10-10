package com.foundations.cluster.node.entity;

import com.foundations.cluster.protocol.EntityViewsWire;
import com.foundations.cluster.protocol.EntityViewsWire.*;
import java.util.*;
import java.util.function.Predicate;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

/** Repeated, interest-scoped observation of ALREADY-LOADED entities on a source.
 * Does not load chunks, construct replicas, mutate entities or claim new ownership.
 * V0 compatibility is deliberately limited to certified vanilla species/items;
 * generic modded entity state/extra-spawn packets require a separate adapter. */
public final class AutomaticEntityDiscovery {
    private AutomaticEntityDiscovery() {}
    public record Result(List<State> states,int skipped) {
        public Result { states=List.copyOf(states);if(skipped<0)throw new IllegalArgumentException(); }
    }
    public static Result collect(MinecraftServer server,String worker,List<Player> interests,Set<UUID> excluded,
                                 Predicate<Entity> currentAuthority,int budget) {
        if(!server.isSameThread())throw new IllegalStateException("Discovery requires server thread");
        Objects.requireNonNull(worker);Objects.requireNonNull(interests);
        Objects.requireNonNull(excluded);Objects.requireNonNull(currentAuthority);
        if(interests.size()>EntityViewsWire.MAX_INTERESTS||budget<0||budget>EntityViewsWire.MAX_AUTO_STATES)
            throw new IllegalArgumentException("Automatic discovery budget exceeded");
        if(budget==0||interests.isEmpty())return new Result(List.of(),0);
        var unique=new HashMap<UUID,Entity>();
        for(Player viewer:interests) {
            var dimension=ResourceLocation.tryParse(viewer.dimension());if(dimension==null)continue;
            ServerLevel level=server.getLevel(ResourceKey.create(Registries.DIMENSION,dimension));
            if(level==null)continue;
            double r=EntityViewsWire.RADIUS;
            var box=new AABB(viewer.x()-r,viewer.y()-r,viewer.z()-r,
                             viewer.x()+r,viewer.y()+r,viewer.z()+r);
            // The loaded-entity spatial index does not create region tickets or
            // request missing chunks. No entity is synthesized from old metadata.
            for(Entity entity:level.getEntities((Entity)null,box,e->!e.isRemoved())) {
                if(excluded.contains(entity.getUUID())||unique.containsKey(entity.getUUID()))continue;
                double dx=entity.getX()-viewer.x(),dy=entity.getY()-viewer.y(),dz=entity.getZ()-viewer.z();
                if(dx*dx+dy*dy+dz*dz>r*r)continue;
                try{EntityViewPackets.supported(entity);}catch(IllegalArgumentException ignored){continue;}
                if(!currentAuthority.test(entity))continue;
                unique.put(entity.getUUID(),entity);
            }
        }
        var eligible=new ArrayList<>(unique.values());
        eligible.sort(Comparator.comparingDouble((Entity e)->distance(e,interests))
            .thenComparing(e->e.getUUID().toString()));
        int skipped=Math.max(0,eligible.size()-budget);
        var results=new ArrayList<State>();
        for(Entity e:eligible) {
            if(results.size()>=budget)break;
            try{results.add(EntityViewPackets.capture(e,EntityViewsWire.autoBinding(worker,e.getUUID())));}
            catch(Exception ex){skipped++;}
        }
        return new Result(results,skipped);
    }
    private static double distance(Entity e,List<Player> interests) {
        String dimension=e.level().dimension().location().toString();
        double best=Double.POSITIVE_INFINITY;
        for(Player p:interests)if(p.dimension().equals(dimension)) {
            double x=e.getX()-p.x(),y=e.getY()-p.y(),z=e.getZ()-p.z();best=Math.min(best,x*x+y*y+z*z);
        }
        return best;
    }
}
