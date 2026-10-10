package com.foundations.cluster.node.entity;

import com.foundations.cluster.protocol.EntityViewsWire;
import com.foundations.cluster.protocol.EntityViewsWire.*;
import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/** One connected viewer's packet-only objects. No destination entity storage or
 * interaction hooks exist for these IDs: they cannot generate AI, drops or pickup. */
public final class EntityViewRenderer {
    private record Render(int id,Sample sample,long expires) {}
    private record Prepared(Sample sample,ClientboundSetEntityDataPacket metadata,long expires) {}
    private final Map<UUID,Render> visible=new LinkedHashMap<>();
    private final Consumer<Packet<?>> send;
    private String dimension="";
    private int collisions;
    public EntityViewRenderer(Consumer<Packet<?>> send){this.send=Objects.requireNonNull(send);}
    public int size(){return visible.size();}
    public int collisions(){return collisions;}
    public Set<Integer> ids(){return Set.copyOf(visible.values().stream().map(Render::id).toList());}
    public void apply(ServerLevel level,Vec3 viewer,List<Sample> samples,long now,long roundTripMillis)throws IOException {
        if(!level.getServer().isSameThread())throw new IllegalStateException("View publication requires server thread");
        if(samples.size()>EntityViewsWire.MAX_PAIRS)throw new IOException("View count exceeded");
        String dim=level.dimension().location().toString();
        if(!dim.equals(dimension)){clear();dimension=dim;}
        // Decode and validate the entire batch before any client-visible publication.
        var prepared=new LinkedHashMap<UUID,Prepared>();
        Set<UUID> identities=new HashSet<>();
        for(Sample sample:samples) {
            State s=sample.state();
            if(!identities.add(s.entity())||prepared.containsKey(s.binding()))throw new IOException("Duplicate entity in presentation batch");
            if(!dim.equals(s.dimension())||viewer.distanceToSqr(s.x(),s.y(),s.z())>EntityViewsWire.RADIUS*EntityViewsWire.RADIUS)continue;
            if(roundTripMillis<0||roundTripMillis>1000||sample.remainingMillis()<=roundTripMillis)continue;
            if(level.getEntity(s.entity())!=null){collisions++;continue;} // Never delete or move a native collision.
            var data=EntityViewPackets.metadata(s,0,level.registryAccess());
            prepared.put(s.binding(),new Prepared(sample,data,now+sample.remainingMillis()-roundTripMillis));
        }
        for(UUID id:new ArrayList<>(visible.keySet()))if(!prepared.containsKey(id))remove(id);
        for(var p:prepared.values()) {
            Sample sample=p.sample;State s=sample.state();Render old=visible.get(s.binding());
            if(old!=null&&old.sample.instance().equals(sample.instance())&&sample.sequence()<=old.sample.sequence()){
                // Identical reads never extend a view's lifetime. Lower sequence is ignored.
                if(sample.sequence()==old.sample.sequence())visible.put(s.binding(),new Render(old.id,old.sample,Math.min(old.expires,p.expires)));
                continue;
            }
            int id=old==null?EntityViewPackets.allocateId():old.id;
            if(level.getEntity(id)!=null){collisions++;if(old!=null)remove(s.binding());continue;}
            if(old==null)send.accept(EntityViewPackets.spawn(s,id));
            if(old==null||!Arrays.equals(old.sample.state().metadata(),s.metadata()))
                send.accept(new ClientboundSetEntityDataPacket(id,p.metadata.packedItems()));
            if(old!=null&&EntityViewPackets.moved(old.sample.state(),s))send.accept(EntityViewPackets.teleport(s,id));
            if(old==null||old.sample.state().headYaw()!=s.headYaw())send.accept(EntityViewPackets.head(s,id));
            if(old==null||EntityViewPackets.velocityChanged(old.sample.state(),s))send.accept(EntityViewPackets.motion(s,id));
            visible.put(s.binding(),new Render(id,sample,p.expires));
        }
        expire(now);
    }
    public void expire(long now){for(var e:new ArrayList<>(visible.entrySet()))if(now>=e.getValue().expires)remove(e.getKey());}
    private void remove(UUID binding){Render old=visible.remove(binding);if(old!=null)send.accept(new ClientboundRemoveEntitiesPacket(old.id));}
    public void clear(){for(UUID id:new ArrayList<>(visible.keySet()))remove(id);}
}
