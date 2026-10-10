package com.foundations.cluster.protocol;

import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/**
 * Persistent reference state machine for authority-initiated item pickup:
 * PROPOSE -> RESERVE -> WORLD_COMMITTED -> PLAYER_COMMITTED -> CLEARED.
 * NOT wired to a Minecraft inventory, item entity, player-save or worker API.
 * World commit is a one-way durability barrier: no timeout abort afterward.
 * Production still needs independent world/player dedup and reconciliation.
 */
public final class PickupTransferLedger implements AutoCloseable {
    private static final int MAGIC=0x46435055, SCHEMA=1, LIMIT=4096, MAX_FILE=4*1024*1024;
    public enum Phase { PROPOSED, RESERVED, WORLD_COMMITTED, PLAYER_COMMITTED, CLEARED, ABORTED }
    public record Id(UUID world,UUID itemEntity,long ownerGeneration,UUID player,long effectCounter) {
        public Id {
            Objects.requireNonNull(world);Objects.requireNonNull(itemEntity);Objects.requireNonNull(player);
            if(ownerGeneration<1||effectCounter<0||effectCounter==Long.MAX_VALUE)
                throw new IllegalArgumentException("Invalid pickup identity");
        }
    }
    public record Entry(Id id,Phase phase,long entityRevision,long inventoryRevision,int offered,int accepted,int committed,
                        String itemDigest,String worldReceipt,String playerReceipt) {
        public Entry {
            Objects.requireNonNull(id);Objects.requireNonNull(phase);
            if(entityRevision<0||inventoryRevision<0||offered<1||accepted<0||accepted>offered||committed<0||committed>accepted||
                !digest(itemDigest)||!optionalDigest(worldReceipt)||!optionalDigest(playerReceipt))
                throw new IllegalArgumentException("Invalid pickup record");
        }
    }
    private final Path file;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private Map<Id,Entry> entries=new LinkedHashMap<>();
    private boolean closed,failed;
    public PickupTransferLedger(Path dir)throws IOException {
        if(Files.isSymbolicLink(dir))throw new IOException("Pickup directory symlink");
        Files.createDirectories(dir);file=dir.resolve("pickup-transfers-v1.bin");
        lockChannel=FileChannel.open(dir.resolve("pickup-transfers-v1.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
        FileLock acquired=null;
        try {
            acquired=lockChannel.tryLock();if(acquired==null)throw new IOException("Pickup ledger already open");lock=acquired;
            if(Files.exists(file,LinkOption.NOFOLLOW_LINKS))entries=load();
        }catch(IOException|RuntimeException e){
            if(acquired!=null)try{acquired.release();}catch(IOException ex){e.addSuppressed(ex);}
            try{lockChannel.close();}catch(IOException ex){e.addSuppressed(ex);}throw e;
        }
    }
    public synchronized Entry propose(Id id,long entityRevision,int offered,String itemDigest)throws IOException {
        ready();Objects.requireNonNull(id);
        Entry prior=entries.get(id);
        if(prior!=null){if(prior.entityRevision()==entityRevision&&prior.offered()==offered&&prior.itemDigest().equals(itemDigest))return prior;
            throw new IllegalStateException("Conflicting pickup proposal");}
        // Never offer the same item revision to two different players/effects.
        // A partial pickup can be proposed again only after the previous grant
        // is durably cleared, using its incremented world entity revision.
        for(Entry previous:entries.values())if(previous.id().world().equals(id.world())&&
                previous.id().itemEntity().equals(id.itemEntity()) &&
                previous.id().ownerGeneration()==id.ownerGeneration()) {
            if(previous.phase()!=Phase.CLEARED&&previous.phase()!=Phase.ABORTED)
                throw new IllegalStateException("Pickup entity already has an unfinished transfer");
            if(previous.phase()==Phase.CLEARED&&entityRevision<previous.entityRevision())
                throw new IllegalStateException("Pickup proposal uses a consumed entity revision");
        }
        if(entries.size()>=LIMIT)throw new IllegalStateException("Pickup checkpoint/retention gate reached");
        Entry result=new Entry(id,Phase.PROPOSED,entityRevision,0,offered,0,0,itemDigest,"","");put(result);return result;
    }
    public synchronized Entry reserve(Id id,long expectedEntityRevision,long inventoryRevision,int accepted)throws IOException {
        ready();Entry e=require(id);
        if(e.phase()==Phase.RESERVED&&e.inventoryRevision()==inventoryRevision&&e.accepted()==accepted&&e.entityRevision()==expectedEntityRevision)return e;
        if(e.phase()!=Phase.PROPOSED||e.entityRevision()!=expectedEntityRevision||accepted<1||accepted>e.offered())
            throw new IllegalStateException("Pickup reservation stale/unsupported");
        Entry next=new Entry(id,Phase.RESERVED,e.entityRevision(),inventoryRevision,e.offered(),accepted,0,e.itemDigest(),"","");put(next);return next;
    }
    public synchronized Entry commitWorld(Id id,int committed,long expectedEntityRevision,String worldReceipt)throws IOException {
        ready();Entry e=require(id);
        if(e.phase()==Phase.WORLD_COMMITTED&&e.committed()==committed&&e.entityRevision()==expectedEntityRevision+1&&e.worldReceipt().equals(worldReceipt))return e;
        if(e.phase()!=Phase.RESERVED||e.entityRevision()!=expectedEntityRevision||committed<1||committed>e.accepted()||!digest(worldReceipt))
            throw new IllegalStateException("Pickup world commit must match reservation and durable world receipt");
        Entry next=new Entry(id,Phase.WORLD_COMMITTED,Math.addExact(e.entityRevision(),1),e.inventoryRevision(),e.offered(),e.accepted(),committed,e.itemDigest(),worldReceipt,"");put(next);return next;
    }
    /** Caller must atomically persist actual grant and effectId with its player image. */
    public synchronized Entry commitPlayer(Id id,long newInventoryRevision,String worldReceipt,String playerReceipt)throws IOException {
        ready();Entry e=require(id);
        if((e.phase()==Phase.PLAYER_COMMITTED||e.phase()==Phase.CLEARED)&&e.inventoryRevision()==newInventoryRevision&&
                e.worldReceipt().equals(worldReceipt)&&e.playerReceipt().equals(playerReceipt))return e;
        if(e.phase()!=Phase.WORLD_COMMITTED||newInventoryRevision<=e.inventoryRevision()||
                !e.worldReceipt().equals(worldReceipt)||!digest(playerReceipt))
            throw new IllegalStateException("Pickup player effect must follow exactly one world commit");
        Entry next=new Entry(id,Phase.PLAYER_COMMITTED,e.entityRevision(),newInventoryRevision,e.offered(),e.accepted(),e.committed(),
                e.itemDigest(),e.worldReceipt(),playerReceipt);put(next);return next;
    }
    public synchronized Entry clear(Id id)throws IOException {
        ready();Entry e=require(id);if(e.phase()==Phase.CLEARED)return e;
        if(e.phase()!=Phase.PLAYER_COMMITTED)throw new IllegalStateException("Pickup cannot clear uncommitted player grant");
        Entry next=new Entry(id,Phase.CLEARED,e.entityRevision(),e.inventoryRevision(),e.offered(),e.accepted(),e.committed(),
                e.itemDigest(),e.worldReceipt(),e.playerReceipt());put(next);return next;
    }
    /** Only a coordinator with proof no world commit happened may abort. */
    public synchronized Entry abort(Id id)throws IOException {
        ready();Entry e=require(id);if(e.phase()==Phase.ABORTED)return e;
        if(e.phase()!=Phase.PROPOSED&&e.phase()!=Phase.RESERVED)
            throw new IllegalStateException("Committed item pickup cannot abort");
        Entry next=new Entry(id,Phase.ABORTED,e.entityRevision(),e.inventoryRevision(),e.offered(),e.accepted(),0,e.itemDigest(),"","");put(next);return next;
    }
    public synchronized Optional<Entry> view(Id id)throws IOException {ready();return Optional.ofNullable(entries.get(id));}
    private Entry require(Id id){Entry e=entries.get(Objects.requireNonNull(id));if(e==null)throw new IllegalStateException("Unknown pickup effect");return e;}
    private void ready()throws IOException {if(closed||failed||!lock.isValid())throw new IOException("Pickup ledger unavailable");}
    private static boolean digest(String s){return s!=null&&s.matches("[0-9a-f]{64}");}
    private static boolean optionalDigest(String s){return s!=null&&(s.isEmpty()||digest(s));}
    private void put(Entry entry)throws IOException {var copy=new LinkedHashMap<>(entries);copy.put(entry.id(),entry);persist(copy);}
    private void persist(Map<Id,Entry> data)throws IOException {
        byte[] body;
        try(var output=new ByteArrayOutputStream();var out=new DataOutputStream(output)){
            out.writeInt(data.size());
            for(Entry e:data.values()){
                Id id=e.id();write(out,id.world());write(out,id.itemEntity());out.writeLong(id.ownerGeneration());write(out,id.player());out.writeLong(id.effectCounter());
                out.writeByte(e.phase().ordinal());out.writeLong(e.entityRevision());out.writeLong(e.inventoryRevision());
                out.writeInt(e.offered());out.writeInt(e.accepted());out.writeInt(e.committed());
                out.writeUTF(e.itemDigest());out.writeUTF(e.worldReceipt());out.writeUTF(e.playerReceipt());
            }out.flush();body=output.toByteArray();
        }
        if(body.length>MAX_FILE-44)throw new IOException("Pickup checkpoint required");
        byte[] sha=hash(body);ByteBuffer payload=ByteBuffer.allocate(body.length+44);
        payload.putInt(MAGIC).putInt(SCHEMA).putInt(body.length).put(body).put(sha).flip();
        Path temp=file.resolveSibling("pickup-transfers-"+UUID.randomUUID()+".tmp");
        try {
            if(Files.isSymbolicLink(file))throw new IOException("Pickup file is symlink");
            try(FileChannel channel=FileChannel.open(temp,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
                while(payload.hasRemaining())channel.write(payload);channel.force(true);
            }
            Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);entries=data;
        }catch(IOException e){failed=true;throw e;}
        finally{Files.deleteIfExists(temp);}
    }
    private Map<Id,Entry> load()throws IOException {
        if(Files.isSymbolicLink(file))throw new IOException("Pickup file is symlink");
        byte[] all=Files.readAllBytes(file);if(all.length<48||all.length>MAX_FILE)throw new IOException("Invalid pickup image size");
        ByteBuffer buf=ByteBuffer.wrap(all);
        if(buf.getInt()!=MAGIC||buf.getInt()!=SCHEMA||buf.getInt()!=all.length-44)throw new IOException("Invalid pickup header");
        byte[] body=Arrays.copyOfRange(all,12,all.length-32);
        if(!MessageDigest.isEqual(hash(body),Arrays.copyOfRange(all,all.length-32,all.length)))throw new IOException("Pickup checksum mismatch");
        try(var in=new DataInputStream(new ByteArrayInputStream(body))){
            int n=in.readInt();if(n<0||n>LIMIT)throw new IOException("Pickup count invalid");
            var result=new LinkedHashMap<Id,Entry>();
            for(int i=0;i<n;i++){
                Id id=new Id(read(in),read(in),in.readLong(),read(in),in.readLong());int ph=in.readUnsignedByte();
                if(ph>=Phase.values().length)throw new IOException("Invalid pickup phase");
                Entry e=new Entry(id,Phase.values()[ph],in.readLong(),in.readLong(),in.readInt(),in.readInt(),in.readInt(),in.readUTF(),in.readUTF(),in.readUTF());
                if((e.phase()==Phase.PROPOSED&&(e.accepted()!=0||e.committed()!=0))||
                    ((e.phase()==Phase.WORLD_COMMITTED||e.phase()==Phase.PLAYER_COMMITTED||e.phase()==Phase.CLEARED)&&
                        (e.committed()<1||e.worldReceipt().isEmpty()))||
                    ((e.phase()==Phase.PLAYER_COMMITTED||e.phase()==Phase.CLEARED)&&e.playerReceipt().isEmpty()))
                    throw new IOException("Contradictory persisted pickup phase");
                if(result.putIfAbsent(id,e)!=null)throw new IOException("Duplicate pickup identity");
            }
            if(in.available()!=0)throw new IOException("Trailing pickup bytes");return result;
        }catch(IllegalArgumentException|IllegalStateException ex){throw new IOException("Invalid pickup entry",ex);}
    }
    private static byte[] hash(byte[] data){try{return MessageDigest.getInstance("SHA-256").digest(data);}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}}
    private static void write(DataOutputStream out,UUID uuid)throws IOException {out.writeLong(uuid.getMostSignificantBits());out.writeLong(uuid.getLeastSignificantBits());}
    private static UUID read(DataInputStream in)throws IOException {return new UUID(in.readLong(),in.readLong());}
    @Override public synchronized void close()throws IOException{if(!closed){closed=true;lock.release();lockChannel.close();}}
}
