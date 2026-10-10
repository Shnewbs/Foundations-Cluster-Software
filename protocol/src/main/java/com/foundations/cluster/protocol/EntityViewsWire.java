package com.foundations.cluster.protocol;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Version-pinned, bounded presentation protocol. No inventory effects, world writes,
 * entity spawning or ownership transfer are authorized by these messages. */
public final class EntityViewsWire {
    private EntityViewsWire() {}
    public static final int MAX_PAIRS = 32, MAX_SOURCE_PAIRS = 8, MAX_AUTO_STATES = 24,
            MAX_SOURCE_STATES = MAX_SOURCE_PAIRS + MAX_AUTO_STATES, MAX_INTERESTS = 8, MAX_PLAYERS = 64, MAX_VIEWERS = 4;
    public static final int MAX_METADATA = 8192, MAX_BYTES = 1200000;
    public static final double RADIUS = 64;
    public static final long FRESH_MILLIS = 1500, OPT_IN_MILLIS = 15 * 60 * 1000;
    private static final int MAGIC = 0x46434556, SCHEMA = 2;
    public enum Kind { COW, ITEM }
    /** Stable within a worker process and its world; not a gameplay-ownership ID.
     * Only exact UUIDs, never position/type/name heuristics, participate in discovery. */
    public static UUID autoBinding(String worker, UUID entity) {
        token(worker,"worker");Objects.requireNonNull(entity);
        return UUID.nameUUIDFromBytes(("foundations:entity-preview:auto-v1:"+worker+":"+entity)
            .getBytes(StandardCharsets.UTF_8));
    }
    public record Pair(UUID binding, UUID entity, String worker, String identity, long seed,
                       String dimension, Kind kind, boolean interested) {
        public Pair {
            Objects.requireNonNull(binding); Objects.requireNonNull(entity); Objects.requireNonNull(kind);
            worker = token(worker, "worker"); identity = token(identity, "identity"); dimension = EntityViewsWire.dimension(dimension);
        }
        public Pair interest(boolean interested) { return new Pair(binding, entity, worker, identity, seed, dimension, kind, interested); }
    }
    public record State(UUID binding, UUID entity, Kind kind, String dimension,
                        double x, double y, double z, float yaw, float pitch, float headYaw,
                        double vx, double vy, double vz, boolean onGround, byte[] metadata) {
        public State {
            Objects.requireNonNull(binding); Objects.requireNonNull(entity); Objects.requireNonNull(kind);
            dimension = EntityViewsWire.dimension(dimension); position(x, y, z);
            if (!Float.isFinite(yaw) || !Float.isFinite(pitch) || !Float.isFinite(headYaw) ||
                !Double.isFinite(vx) || !Double.isFinite(vy) || !Double.isFinite(vz) ||
                Math.abs(vx) > 100 || Math.abs(vy) > 100 || Math.abs(vz) > 100)
                throw new IllegalArgumentException("Invalid entity rotation/velocity");
            Objects.requireNonNull(metadata);
            if (metadata.length == 0 || metadata.length > MAX_METADATA) throw new IllegalArgumentException("Invalid entity metadata size");
            metadata = metadata.clone();
        }
        @Override public byte[] metadata() { return metadata.clone(); }
    }
    public record Player(UUID uuid, String name, String dimension, double x, double y, double z) {
        public Player {
            Objects.requireNonNull(uuid); name = token(name, "player"); dimension = EntityViewsWire.dimension(dimension); position(x, y, z);
        }
        public boolean near(State state) {
            return dimension.equals(state.dimension) && squared(x-state.x, y-state.y, z-state.z) <= RADIUS*RADIUS;
        }
    }
    public record Request(long sequence, List<State> states, List<Player> players, boolean automatic) {
        public Request(long sequence,List<State> states,List<Player> players){this(sequence,states,players,false);}
        public Request {
            if (sequence <= 0) throw new IllegalArgumentException("Invalid exchange sequence");
            states = limited(states, MAX_SOURCE_STATES); players = limited(players, MAX_PLAYERS);
            unique(states.stream().map(State::binding).toList());
            unique(states.stream().map(State::entity).toList());
            unique(players.stream().map(Player::uuid).toList());
        }
    }
    public record Sample(Pair pair, State state, UUID instance, long sequence, long remainingMillis) {
        public Sample {
            Objects.requireNonNull(pair); Objects.requireNonNull(state); Objects.requireNonNull(instance);
            if (!matches(pair, state) || sequence <= 0 || remainingMillis < 0 || remainingMillis > FRESH_MILLIS)
                throw new IllegalArgumentException("Invalid paired sample");
        }
    }
    public record Viewer(UUID uuid, List<Sample> samples) {
        public Viewer {
            Objects.requireNonNull(uuid); samples = limited(samples, MAX_PAIRS);
            unique(samples.stream().map(s -> s.pair.binding).toList());
            unique(samples.stream().map(s -> s.pair.entity).toList());
        }
    }
    public record Response(long sequence, UUID boot, List<Pair> owned, List<Viewer> viewers, List<Player> interests) {
        public Response(long sequence,UUID boot,List<Pair> owned,List<Viewer> viewers){this(sequence,boot,owned,viewers,List.of());}
        public Response {
            if (sequence <= 0) throw new IllegalArgumentException("Invalid response sequence");
            Objects.requireNonNull(boot); owned = limited(owned, MAX_SOURCE_STATES);
            viewers = limited(viewers, MAX_VIEWERS); interests = limited(interests, MAX_INTERESTS);
            unique(owned.stream().map(Pair::binding).toList());
            unique(viewers.stream().map(Viewer::uuid).toList());
            unique(interests.stream().map(Player::uuid).toList());
        }
    }
    public static boolean matches(Pair p, State s) {
        return p.binding.equals(s.binding) && p.entity.equals(s.entity) && p.kind == s.kind && p.dimension.equals(s.dimension);
    }
    private static double squared(double x, double y, double z) { return x*x+y*y+z*z; }
    private static void position(double x, double y, double z) {
        if (!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)||Math.abs(x)>30000000||Math.abs(z)>30000000||Math.abs(y)>20000000)
            throw new IllegalArgumentException("Invalid entity position");
    }
    private static String dimension(String s) {
        if (s == null || !s.matches("[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,128}")) throw new IllegalArgumentException("Invalid dimension");
        return s;
    }
    public static String token(String s, String name) {
        if (s == null || !s.matches("[A-Za-z0-9_.-]{1,128}")) throw new IllegalArgumentException("Invalid " + name);
        return s;
    }
    private static <T> List<T> limited(List<T> v, int max) {
        Objects.requireNonNull(v); if (v.size() > max) throw new IllegalArgumentException("Entity view count exceeded"); return List.copyOf(v);
    }
    private static void unique(List<?> values) { if (new HashSet<>(values).size()!=values.size()) throw new IllegalArgumentException("Duplicate view identity"); }
    private interface Writer { void write(DataOutputStream out) throws IOException; }
    private interface Reader<T> { T read(DataInputStream in) throws IOException; }
    private static byte[] encode(int type, Writer writer) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try(var out = new DataOutputStream(bytes)) { out.writeInt(MAGIC); out.writeByte(SCHEMA); out.writeByte(type); writer.write(out); }
        if (bytes.size() > MAX_BYTES) throw new IOException("Entity view bundle too large"); return bytes.toByteArray();
    }
    private static <T> T decode(byte[] bytes, int type, Reader<T> reader) throws IOException {
        if (bytes == null || bytes.length > MAX_BYTES) throw new IOException("Entity view bundle too large");
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if(in.readInt()!=MAGIC || in.readUnsignedByte()!=SCHEMA || in.readUnsignedByte()!=type) throw new IOException("Unknown entity view schema/type");
            T result=reader.read(in); if(in.available()!=0) throw new IOException("Trailing entity view bytes"); return result;
        } catch(IllegalArgumentException | NullPointerException ex) { throw new IOException("Invalid entity view bundle", ex); }
    }
    private static void uuid(DataOutputStream out, UUID id)throws IOException { out.writeLong(id.getMostSignificantBits());out.writeLong(id.getLeastSignificantBits()); }
    private static UUID uuid(DataInputStream in)throws IOException { return new UUID(in.readLong(), in.readLong()); }
    private static Kind kind(DataInputStream in)throws IOException { int k=in.readUnsignedByte();if(k>=Kind.values().length)throw new IOException("Unsupported entity kind");return Kind.values()[k]; }
    private static void pair(DataOutputStream out, Pair p)throws IOException {
        uuid(out,p.binding);uuid(out,p.entity);out.writeUTF(p.worker);out.writeUTF(p.identity);out.writeLong(p.seed);out.writeUTF(p.dimension);out.writeByte(p.kind.ordinal());out.writeBoolean(p.interested);
    }
    private static Pair pair(DataInputStream in)throws IOException { return new Pair(uuid(in),uuid(in),in.readUTF(),in.readUTF(),in.readLong(),in.readUTF(),kind(in),in.readBoolean()); }
    private static void state(DataOutputStream out, State s)throws IOException {
        uuid(out,s.binding);uuid(out,s.entity);out.writeByte(s.kind.ordinal());out.writeUTF(s.dimension);
        out.writeDouble(s.x);out.writeDouble(s.y);out.writeDouble(s.z);out.writeFloat(s.yaw);out.writeFloat(s.pitch);out.writeFloat(s.headYaw);
        out.writeDouble(s.vx);out.writeDouble(s.vy);out.writeDouble(s.vz);out.writeBoolean(s.onGround);out.writeInt(s.metadata.length);out.write(s.metadata);
    }
    private static State state(DataInputStream in)throws IOException {
        UUID binding=uuid(in),entity=uuid(in);Kind kind=kind(in);String dim=in.readUTF();
        double x=in.readDouble(),y=in.readDouble(),z=in.readDouble();float yaw=in.readFloat(),pitch=in.readFloat(),head=in.readFloat();
        double vx=in.readDouble(),vy=in.readDouble(),vz=in.readDouble();boolean ground=in.readBoolean();int n=count(in,MAX_METADATA);
        byte[] metadata=new byte[n];in.readFully(metadata);return new State(binding,entity,kind,dim,x,y,z,yaw,pitch,head,vx,vy,vz,ground,metadata);
    }
    private static int count(DataInputStream in,int max)throws IOException { int n=in.readInt();if(n<0||n>max)throw new IOException("Entity view count/size exceeded");return n; }
    private static void player(DataOutputStream out,Player p)throws IOException {
        uuid(out,p.uuid);out.writeUTF(p.name);out.writeUTF(p.dimension);
        out.writeDouble(p.x);out.writeDouble(p.y);out.writeDouble(p.z);
    }
    private static Player player(DataInputStream in)throws IOException {
        return new Player(uuid(in),in.readUTF(),in.readUTF(),in.readDouble(),in.readDouble(),in.readDouble());
    }
    public static byte[] encodeRequest(Request r)throws IOException { return encode(1,out->{
        out.writeLong(r.sequence);out.writeInt(r.states.size());for(State s:r.states)state(out,s);
        out.writeInt(r.players.size());for(Player p:r.players)player(out,p);
        out.writeBoolean(r.automatic);
    }); }
    public static Request decodeRequest(byte[] bytes)throws IOException { return decode(bytes,1,in->{
        long seq=in.readLong();int n=count(in,MAX_SOURCE_STATES);var states=new ArrayList<State>();for(int i=0;i<n;i++)states.add(state(in));
        n=count(in,MAX_PLAYERS);var players=new ArrayList<Player>();for(int i=0;i<n;i++)players.add(player(in));
        return new Request(seq,states,players,in.readBoolean());
    }); }
    public static byte[] encodeResponse(Response r)throws IOException { return encode(2,out->{
        out.writeLong(r.sequence);uuid(out,r.boot);out.writeInt(r.owned.size());for(Pair p:r.owned)pair(out,p);
        out.writeInt(r.viewers.size());for(Viewer v:r.viewers){uuid(out,v.uuid);out.writeInt(v.samples.size());for(Sample s:v.samples){pair(out,s.pair);state(out,s.state);uuid(out,s.instance);out.writeLong(s.sequence);out.writeLong(s.remainingMillis);}}
        out.writeInt(r.interests.size());for(Player p:r.interests)player(out,p);
    }); }
    public static Response decodeResponse(byte[] bytes)throws IOException { return decode(bytes,2,in->{
        long seq=in.readLong();UUID boot=uuid(in);int n=count(in,MAX_SOURCE_STATES);var owned=new ArrayList<Pair>();for(int i=0;i<n;i++)owned.add(pair(in));
        n=count(in,MAX_VIEWERS);var viewers=new ArrayList<Viewer>();for(int i=0;i<n;i++){UUID id=uuid(in);int ns=count(in,MAX_PAIRS);var samples=new ArrayList<Sample>();for(int j=0;j<ns;j++)samples.add(new Sample(pair(in),state(in),uuid(in),in.readLong(),in.readLong()));viewers.add(new Viewer(id,samples));}
        n=count(in,MAX_INTERESTS);var interests=new ArrayList<Player>();for(int i=0;i<n;i++)interests.add(player(in));
        return new Response(seq,boot,owned,viewers,interests);
    }); }
    public static byte[] encodePairs(List<Pair> pairs)throws IOException { return encode(3,out->{
        limited(pairs,MAX_PAIRS);out.writeInt(pairs.size());for(Pair p:pairs)pair(out,p.interest(false));
    }); }
    public static List<Pair> decodePairs(byte[] bytes)throws IOException { return decode(bytes,3,in->{
        int n=count(in,MAX_PAIRS);var pairs=new ArrayList<Pair>();for(int i=0;i<n;i++)pairs.add(pair(in));unique(pairs.stream().map(Pair::binding).toList());return List.copyOf(pairs);
    }); }
}
