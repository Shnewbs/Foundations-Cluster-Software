package com.foundations.cluster.node;

import com.foundations.cluster.protocol.WorldEnvironmentSnapshot;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import org.slf4j.Logger;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** All level access runs on the Minecraft server thread; HTTP threads only exchange immutable records. */
final class WorldEnvironmentSync {
    private record Control(String owner,long epoch,long revision,WorldEnvironmentSnapshot snapshot,long received,long leaseNanos){}
    private record Sample(WorldEnvironmentSnapshot snapshot,long sequence,long captured,long epoch,long revision){}
    private final String workerId,instance=UUID.randomUUID().toString(); private final Logger log;
    private volatile Control control; private volatile Sample sample;
    private volatile long appliedEpoch,appliedRevision;
    private long sequence,ticks; private boolean expired;
    WorldEnvironmentSync(String workerId,Logger log){this.workerId=workerId;this.log=log;}

    synchronized void accept(boolean enabled,String owner,long epoch,long revision,long leaseMillis,String token){
        if(!enabled){control=null;appliedEpoch=0;appliedRevision=0;return;}
        if(token.isBlank())return;
        Control previous=control;
        if(previous!=null&&(epoch<previous.epoch||(epoch==previous.epoch&&revision<previous.revision)))return;
        control=new Control(owner,epoch,revision,WorldEnvironmentSnapshot.decode(token),System.nanoTime(),TimeUnit.MILLISECONDS.toNanos(Math.clamp(leaseMillis,0,10000)));
    }
    void tick(MinecraftServer server){
        Control c=control;if(c==null)return;
        long elapsed=Math.max(0,System.nanoTime()-c.received);boolean live=elapsed<c.leaseNanos;
        boolean leadership=c.owner.equals(workerId)&&live;
        if(leadership&&appliedEpoch==c.epoch&&!expired){appliedRevision=Math.max(appliedRevision,c.revision);return;}
        boolean changed=appliedEpoch!=c.epoch||appliedRevision!=c.revision;
        if(!live&&!expired)log.warn("World environment lease expired; holding the last shared clock/weather until Manager recovers");
        expired=!live;
        WorldEnvironmentSnapshot s=c.snapshot.advance(TimeUnit.NANOSECONDS.toMillis(Math.min(elapsed,c.leaseNanos)));
        ServerLevel level=server.overworld();ServerLevelData data=(ServerLevelData)level.getLevelData();
        level.setDayTime(s.dayTime());level.setDayTimeFraction(s.fraction());level.setDayTimePerTick(s.dayRate());
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(s.daylightCycle(),server);
        level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(s.weatherCycle(),server);
        // Followers never roll their own weather transition while waiting for the next owner report.
        data.setClearWeatherTime(leadership?s.clearTicks():(s.clearTicks()>0?Math.max(100,s.clearTicks()):0));
        data.setRainTime(leadership?s.rainTicks():Math.max(100,s.rainTicks()));
        data.setThunderTime(leadership?s.thunderTicks():Math.max(100,s.thunderTicks()));
        data.setRaining(s.raining());data.setThundering(s.thundering());
        level.setRainLevel(s.rainLevel());level.setThunderLevel(s.thunderLevel());
        if(changed||++ticks%20==0){
            server.forceTimeSynchronization();
            server.getPlayerList().broadcastAll(new ClientboundGameEventPacket(s.raining()?ClientboundGameEventPacket.START_RAINING:ClientboundGameEventPacket.STOP_RAINING,0),level.dimension());
            server.getPlayerList().broadcastAll(new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE,s.rainLevel()),level.dimension());
            server.getPlayerList().broadcastAll(new ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE,s.thunderLevel()),level.dimension());
        }
        if(appliedEpoch!=c.epoch)log.info("World environment synchronized: owner={} epoch={} role={}",c.owner,c.epoch,leadership?"owner":"follower");
        appliedEpoch=c.epoch;appliedRevision=c.revision;
        // Report exactly what was installed before the new owner is allowed to publish.
        capture(server);
    }
    void capture(MinecraftServer server){
        ServerLevel level=server.overworld();ServerLevelData d=(ServerLevelData)level.getLevelData();float rain=level.getRainLevel(1);
        float thunder=rain>0?Math.clamp(level.getThunderLevel(1)/rain,0,1):(d.isThundering()?1:0);
        sample=new Sample(new WorldEnvironmentSnapshot(level.getDayTime(),level.getDayTimeFraction(),level.getDayTimePerTick(),
            level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT),level.getGameRules().getBoolean(GameRules.RULE_WEATHER_CYCLE),
            d.getClearWeatherTime(),d.getRainTime(),d.getThunderTime(),d.isRaining(),d.isThundering(),rain,thunder),++sequence,System.nanoTime(),appliedEpoch,appliedRevision);
    }
    String reportJson(){
        Sample s=sample;if(s==null)return "";
        return ",\"worldEnvironment\":\""+s.snapshot.encode()+"\",\"worldEnvironmentInstance\":\""+instance+"\",\"worldEnvironmentEpoch\":"+s.epoch
            +",\"worldEnvironmentRevision\":"+s.revision+",\"worldEnvironmentSequence\":"+s.sequence
            +",\"worldEnvironmentAgeMillis\":"+TimeUnit.NANOSECONDS.toMillis(Math.max(0,System.nanoTime()-s.captured));
    }
}
