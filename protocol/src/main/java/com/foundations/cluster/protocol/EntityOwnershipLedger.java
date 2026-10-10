package com.foundations.cluster.protocol;

import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/**
 * Durable Manager-side ownership CAS prototype. This is NOT a Minecraft transfer
 * endpoint, entity serializer, destination-state persistence adapter or failover.
 * A caller must authenticate the destination's durable full-state receipt and
 * fence real world simulation BEFORE using this CAS in production.
 *
 * Every transition writes a checksummed, forced complete snapshot then makes an
 * atomic replacement. File-system durability after sudden power loss depends on
 * storage/OS guarantees; tests kill JVMs, not powered-off hosts.
 */
public final class EntityOwnershipLedger implements AutoCloseable {
    public static final int MAX_ENTITIES=128, MAX_IMAGE=256*1024, MAX_FILE=32*1024*1024;
    private static final int MAGIC=0x4643454f, SCHEMA=1;
    public record Key(UUID world, UUID entity) {
        public Key { Objects.requireNonNull(world); Objects.requireNonNull(entity); }
    }
    public record Owner(String worker,long epoch,long generation) {
        public Owner {
            worker=token(worker);if(epoch<1||generation<1||generation==Long.MAX_VALUE)
                throw new IllegalArgumentException("Invalid entity authority generation/epoch");
        }
    }
    public record Stage(UUID transfer,String source,String destination,long generation,long sourceEpoch,
                        String digest,byte[] image) {
        public Stage {
            Objects.requireNonNull(transfer);source=token(source);destination=token(destination);
            if(source.equals(destination)||generation<1||sourceEpoch<1||!sha256(digest))
                throw new IllegalArgumentException("Invalid staged entity authority transfer");
            image=checkedImage(image);
            if(!digest.equals(hash(image)))throw new IllegalArgumentException("Stage image checksum differs");
        }
        @Override public byte[] image(){return image.clone();}
    }
    public record View(Key key,String dimension,String kind,Owner owner,Stage staged,
                       UUID committedTransfer,String oldOwner,long oldGeneration) {}
    public record CommitResult(View view,boolean committedNow) {}
    private final Path file;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private Map<Key,View> current=new LinkedHashMap<>();
    private boolean failed,closed;
    public EntityOwnershipLedger(Path directory)throws IOException {
        if(Files.isSymbolicLink(directory))throw new IOException("Ownership directory must not be a symlink");
        Files.createDirectories(directory);
        file=directory.resolve("entity-ownership-v1.bin");
        Path lockPath=directory.resolve("entity-ownership-v1.lock");
        lockChannel=FileChannel.open(lockPath,StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
        FileLock acquired=null;
        try {
            acquired=lockChannel.tryLock();if(acquired==null)throw new IOException("Ownership ledger already open");
            lock=acquired;
            if(Files.exists(file,LinkOption.NOFOLLOW_LINKS))current=load();
        }catch(IOException|RuntimeException e){
            if(acquired!=null)try{acquired.release();}catch(IOException ex){e.addSuppressed(ex);}
            try{lockChannel.close();}catch(IOException ex){e.addSuppressed(ex);}
            throw e;
        }
    }
    public synchronized View initialize(Key key,String dimension,String kind,String owner,long epoch)throws IOException {
        available();Objects.requireNonNull(key);dimension=dimension(dimension);kind=dimension(kind);Owner first=new Owner(owner,epoch,1);
        View prior=current.get(key);
        if(prior!=null){if(prior.owner.equals(first)&&prior.dimension.equals(dimension)&&prior.kind.equals(kind))return prior;
            throw new IllegalStateException("Existing entity cannot be silently initialized under a new owner");}
        if(current.size()>=MAX_ENTITIES)throw new IllegalStateException("Ownership checkpoint/capacity gate reached");
        View view=new View(key,dimension,kind,first,null,null,"",0);
        var next=new LinkedHashMap<>(current);next.put(key,view);persist(next);return view;
    }
    public synchronized View stage(Key key,UUID transfer,long expectedGeneration,String source,long sourceEpoch,
                                   String destination,byte[] fullState)throws IOException {
        available();Objects.requireNonNull(key);Objects.requireNonNull(transfer);
        View prior=require(key);Owner owner=prior.owner();source=token(source);destination=token(destination);
        if(prior.staged()!=null) {
            Stage pending=prior.staged();
            if(pending.transfer().equals(transfer) && pending.generation()==expectedGeneration &&
                pending.source().equals(source) && pending.sourceEpoch()==sourceEpoch &&
                pending.destination().equals(destination) && Arrays.equals(pending.image(),fullState))return prior;
            throw new IllegalStateException("Another entity transfer is already staged");
        }
        if(prior.committedTransfer()!=null&&prior.committedTransfer().equals(transfer))
            throw new IllegalStateException("Previously committed transfer cannot be staged again");
        if(owner.generation()!=expectedGeneration||owner.epoch()!=sourceEpoch||!owner.worker().equals(source))
            throw new IllegalStateException("Stale entity owner/generation/epoch");
        Stage pending=new Stage(transfer,source,destination,expectedGeneration,sourceEpoch,hash(fullState),fullState);
        View view=new View(key,prior.dimension(),prior.kind(),owner,pending,prior.committedTransfer(),prior.oldOwner(),prior.oldGeneration());
        replace(view);return view;
    }
    /**
     * CAS only after a trusted destination has durably staged this exact full
     * image. The supplied digest is a comparison, NOT a cryptographic receipt.
     * After commit B wins forever at generation+1, even if A crashes.
     */
    public synchronized CommitResult commit(Key key,UUID transfer,long expectedGeneration,String source,
                                             String destination,long destinationEpoch,String stagedDigest)throws IOException {
        available();View before=require(key);Objects.requireNonNull(transfer);
        source=token(source);destination=token(destination);
        if(before.committedTransfer()!=null&&before.committedTransfer().equals(transfer)) {
            if(before.owner().generation()==expectedGeneration+1 && before.owner().worker().equals(destination))
                return new CommitResult(before,false);
            throw new IllegalStateException("Superseded transfer retry");
        }
        Stage stage=before.staged();
        if(stage==null||!stage.transfer().equals(transfer)||stage.generation()!=expectedGeneration ||
           !stage.source().equals(source)||!stage.destination().equals(destination)||
           !before.owner().worker().equals(source)||before.owner().generation()!=expectedGeneration||
           !stage.digest().equals(stagedDigest))throw new IllegalStateException("CAS rejected stale or unmatched entity transfer");
        View next=new View(key,before.dimension(),before.kind(),new Owner(destination,destinationEpoch,Math.addExact(expectedGeneration,1)),
                           null,transfer,source,expectedGeneration);
        replace(next);return new CommitResult(next,true);
    }
    /** Aborting after durable CAS is forbidden; ownership cannot roll backward. */
    public synchronized boolean abort(Key key,UUID transfer)throws IOException {
        available();View before=require(key);
        if(before.committedTransfer()!=null&&before.committedTransfer().equals(transfer))
            throw new IllegalStateException("Already committed; destination must finish promotion");
        if(before.staged()==null)return false;
        if(!before.staged().transfer().equals(transfer))throw new IllegalStateException("Wrong transfer token");
        replace(new View(key,before.dimension(),before.kind(),before.owner(),null,before.committedTransfer(),before.oldOwner(),before.oldGeneration()));
        return true;
    }
    public synchronized Optional<View> view(Key key)throws IOException {available();return Optional.ofNullable(current.get(key));}
    public synchronized int size()throws IOException {available();return current.size();}
    private View require(Key key){View view=current.get(Objects.requireNonNull(key));if(view==null)throw new IllegalStateException("Unregistered entity");return view;}
    private void replace(View v)throws IOException {var map=new LinkedHashMap<>(current);map.put(v.key(),v);persist(map);}
    private static byte[] checkedImage(byte[] bytes) {
        if(bytes==null||bytes.length==0||bytes.length>MAX_IMAGE)throw new IllegalArgumentException("Entity staging image out of bounds");return bytes.clone();
    }
    private static String token(String s){return EntityViewsWire.token(s,"worker");}
    private static String dimension(String s){if(s==null||!s.matches("[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,128}"))throw new IllegalArgumentException("Invalid entity dimension/type");return s;}
    private static boolean sha256(String s){return s!=null&&s.matches("[0-9a-f]{64}");}
    private static byte[] digest(byte[] bytes){try{return MessageDigest.getInstance("SHA-256").digest(bytes);}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}}
    public static String hash(byte[] bytes){return HexFormat.of().formatHex(digest(Objects.requireNonNull(bytes)));}
    private void available()throws IOException {if(closed||failed||!lock.isValid())throw new IOException("Entity ownership ledger closed/fenced");}
    private void persist(Map<Key,View> next)throws IOException {
        byte[] body;
        try(var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes)){
            out.writeInt(next.size());
            for(View v:next.values()){
                uuid(out,v.key().world());uuid(out,v.key().entity());out.writeUTF(v.dimension());out.writeUTF(v.kind());
                out.writeUTF(v.owner().worker());out.writeLong(v.owner().epoch());out.writeLong(v.owner().generation());
                out.writeBoolean(v.committedTransfer()!=null);if(v.committedTransfer()!=null)uuid(out,v.committedTransfer());
                out.writeUTF(v.oldOwner());out.writeLong(v.oldGeneration());out.writeBoolean(v.staged()!=null);
                if(v.staged()!=null){Stage s=v.staged();uuid(out,s.transfer());out.writeUTF(s.source());out.writeUTF(s.destination());
                    out.writeLong(s.generation());out.writeLong(s.sourceEpoch());out.writeUTF(s.digest());out.writeInt(s.image().length);out.write(s.image());}
            }
            out.flush();body=bytes.toByteArray();
        }
        if(body.length>MAX_FILE-48)throw new IOException("Ownership ledger checkpoint limit reached");
        ByteBuffer encoded=ByteBuffer.allocate(body.length+44);
        encoded.putInt(MAGIC).putInt(SCHEMA).putInt(body.length).put(body).put(digest(body));encoded.flip();
        Path tmp=file.resolveSibling("entity-ownership-"+UUID.randomUUID()+".tmp");
        try {
            if(Files.isSymbolicLink(file))throw new IOException("Ownership file became symlink");
            try(FileChannel target=FileChannel.open(tmp,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
                while(encoded.hasRemaining())target.write(encoded);target.force(true);
            }
            Files.move(tmp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            current=next;
        }catch(IOException e){failed=true;throw e;}
        finally{Files.deleteIfExists(tmp);}
    }
    private Map<Key,View> load()throws IOException {
        if(Files.isSymbolicLink(file))throw new IOException("Ownership file is symlink");
        byte[] bytes=Files.readAllBytes(file);
        if(bytes.length<48||bytes.length>MAX_FILE)throw new IOException("Invalid ownership image size");
        var b=ByteBuffer.wrap(bytes);
        if(b.getInt()!=MAGIC||b.getInt()!=SCHEMA||b.getInt()!=bytes.length-44)throw new IOException("Invalid ownership header");
        byte[] body=Arrays.copyOfRange(bytes,12,bytes.length-32);
        if(!MessageDigest.isEqual(digest(body),Arrays.copyOfRange(bytes,bytes.length-32,bytes.length)))throw new IOException("Corrupt ownership checksum");
        try(var in=new DataInputStream(new ByteArrayInputStream(body))){
            int n=in.readInt();if(n<0||n>MAX_ENTITIES)throw new IOException("Ownership count exceeds limit");
            Map<Key,View> map=new LinkedHashMap<>();
            for(int i=0;i<n;i++){
                Key key=new Key(uuid(in),uuid(in));String dim=dimension(in.readUTF()),kind=dimension(in.readUTF());
                Owner owner=new Owner(in.readUTF(),in.readLong(),in.readLong());
                UUID committed=in.readBoolean()?uuid(in):null;String oldOwner=in.readUTF();long oldGen=in.readLong();
                Stage stage=null;
                if(in.readBoolean()){
                    UUID transfer=uuid(in);String src=in.readUTF(),dest=in.readUTF();long gen=in.readLong(),epoch=in.readLong();String sha=in.readUTF();
                    int len=in.readInt();if(len<1||len>MAX_IMAGE)throw new IOException("Staged entity image exceeds limit");
                    stage=new Stage(transfer,src,dest,gen,epoch,sha,in.readNBytes(len));
                    if(stage.image().length!=len||stage.generation()!=owner.generation()||stage.sourceEpoch()!=owner.epoch()||!stage.source().equals(owner.worker()))
                        throw new IOException("Staged transfer does not match durable owner");
                }
                if(oldGen<0||(committed==null&&(oldGen!=0||!oldOwner.isEmpty()))||(committed!=null&&oldGen!=owner.generation()-1))
                    throw new IOException("Inconsistent tombstone metadata");
                if(map.putIfAbsent(key,new View(key,dim,kind,owner,stage,committed,oldOwner,oldGen))!=null)throw new IOException("Duplicate ownership identity");
            }
            if(in.available()!=0)throw new IOException("Trailing ownership bytes");return map;
        }catch(IllegalArgumentException|IllegalStateException ex){throw new IOException("Invalid ownership data",ex);}
    }
    private static void uuid(DataOutputStream out,UUID value)throws IOException {out.writeLong(value.getMostSignificantBits());out.writeLong(value.getLeastSignificantBits());}
    private static UUID uuid(DataInputStream in)throws IOException{return new UUID(in.readLong(),in.readLong());}
    @Override public synchronized void close()throws IOException{if(!closed){closed=true;lock.release();lockChannel.close();}}
}
