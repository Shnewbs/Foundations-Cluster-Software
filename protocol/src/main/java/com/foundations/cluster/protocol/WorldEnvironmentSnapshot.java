package com.foundations.cluster.protocol;

/** Versioned vanilla/NeoForge overworld clock and weather state. Never changes gameTime. */
public record WorldEnvironmentSnapshot(long dayTime, float fraction, float dayRate,
        boolean daylightCycle, boolean weatherCycle, int clearTicks, int rainTicks, int thunderTicks,
        boolean raining, boolean thundering, float rainLevel, float thunderLevel) {
    public WorldEnvironmentSnapshot {
        if(dayTime<0 || dayTime>Long.MAX_VALUE-100_000_000L || !Float.isFinite(fraction) || fraction<0 || fraction>=1
                || !Float.isFinite(dayRate) || dayRate< -1 || dayRate>10000
                || clearTicks<0 || rainTicks<0 || thunderTicks<0
                || !level(rainLevel) || !level(thunderLevel)) throw new IllegalArgumentException("Invalid world environment snapshot");
    }
    private static boolean level(float x){return Float.isFinite(x)&&x>=0&&x<=1;}
    private static boolean bool(String x){if(x.equals("true"))return true;if(x.equals("false"))return false;throw new IllegalArgumentException("Invalid environment boolean");}
    public String encode(){return "1|"+dayTime+"|"+fraction+"|"+dayRate+"|"+daylightCycle+"|"+weatherCycle+"|"+clearTicks+"|"+rainTicks+"|"+thunderTicks+"|"+raining+"|"+thundering+"|"+rainLevel+"|"+thunderLevel;}
    public static WorldEnvironmentSnapshot decode(String token){
        if(token==null||token.length()>512)throw new IllegalArgumentException("Invalid environment token");
        String[] p=token.split("\\|",-1);if(p.length!=13||!p[0].equals("1"))throw new IllegalArgumentException("Unsupported environment schema");
        return new WorldEnvironmentSnapshot(Long.parseLong(p[1]),Float.parseFloat(p[2]),Float.parseFloat(p[3]),bool(p[4]),bool(p[5]),Integer.parseInt(p[6]),Integer.parseInt(p[7]),Integer.parseInt(p[8]),bool(p[9]),bool(p[10]),Float.parseFloat(p[11]),Float.parseFloat(p[12]));
    }
    /** Bounded prediction between reports; only the elected worker chooses weather transitions. */
    public WorldEnvironmentSnapshot advance(long millis){
        long ticks=Math.max(0,Math.min(10000,millis))/50;
        double time=fraction+(daylightCycle?ticks*(dayRate<0?1:dayRate):0);
        long whole=(long)time;float part=(float)(time-whole);
        if(part>=1){whole++;part=0;}
        int wt=weatherCycle?(int)ticks:0;
        return new WorldEnvironmentSnapshot(dayTime+whole,part,dayRate,daylightCycle,weatherCycle,
            Math.max(0,clearTicks-wt),Math.max(0,rainTicks-wt),Math.max(0,thunderTicks-wt),raining,thundering,
            Math.clamp(rainLevel+(raining?1:-1)*ticks*.01f,0,1),Math.clamp(thunderLevel+(thundering?1:-1)*ticks*.01f,0,1));
    }
}
