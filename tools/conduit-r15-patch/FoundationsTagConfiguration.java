package com.velocitypowered.proxy.connection.backend;

import com.velocitypowered.proxy.protocol.ProtocolUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.*;

/** Conduit 1.8.0 TagsUpdatePacket is registry -> tag -> ordered numeric IDs.
 * Canonicalize the two map levels only. Do not sort/deduplicate member arrays:
 * their IDs, multiplicity and sequence remain part of the compatibility guard.
 * This representation is private to comparison; never rewrite forwarded packets.
 */
final class FoundationsTagConfiguration {
    static final String KEY="vanilla:TagsUpdatePacket";
    private static final int MAX_TAGS=131072,MAX_MEMBERS=1048576;

    static byte[] canonical(byte[] raw){
        var registries=read(raw);var out=Unpooled.buffer();
        try{
            ProtocolUtils.writeVarInt(out,registries.size());
            for(var registry:registries.entrySet()){
                ProtocolUtils.writeString(out,registry.getKey());ProtocolUtils.writeVarInt(out,registry.getValue().size());
                for(var tag:registry.getValue().entrySet()){
                    ProtocolUtils.writeString(out,tag.getKey());ProtocolUtils.writeVarIntArray(out,tag.getValue());
                }
            }
            byte[] result=new byte[out.readableBytes()];out.getBytes(out.readerIndex(),result);return result;
        }finally{out.release();}
    }

    private static SortedMap<String,SortedMap<String,int[]>> read(byte[] raw){
        if(raw.length>FoundationsHandshakeCapture.MAX_BYTES)throw new IllegalStateException("Oversized tag configuration");
        var input=Unpooled.wrappedBuffer(raw);
        try{
            int registryCount=count(input,FoundationsHandshakeCapture.MAX_PACKETS,"registry");
            int totalTags=0,totalMembers=0;
            SortedMap<String,SortedMap<String,int[]>> registries=new TreeMap<>();
            for(int r=0;r<registryCount;r++){
                String registry=name(input);int tagCount=count(input,MAX_TAGS-totalTags,"tag");totalTags+=tagCount;
                SortedMap<String,int[]> tags=new TreeMap<>();
                if(registries.putIfAbsent(registry,tags)!=null)throw new IllegalStateException("Duplicate tag configuration registry");
                for(int t=0;t<tagCount;t++){
                    String tag=name(input);int memberCount=count(input,MAX_MEMBERS-totalMembers,"member");totalMembers+=memberCount;
                    int[] ids=new int[memberCount];
                    for(int m=0;m<memberCount;m++){
                        ids[m]=ProtocolUtils.readVarInt(input);
                        if(ids[m]<0)throw new IllegalStateException("Negative tag configuration member ID");
                    }
                    if(tags.putIfAbsent(tag,ids)!=null)throw new IllegalStateException("Duplicate tag configuration tag");
                }
            }
            if(input.isReadable())throw new IllegalStateException("Trailing tag configuration data");
            return registries;
        }catch(IllegalStateException ex){throw ex;}
        catch(RuntimeException ex){throw new IllegalStateException("Invalid tag configuration encoding",ex);}
        finally{input.release();}
    }

    private static int count(ByteBuf input,int limit,String kind){
        int n=ProtocolUtils.readVarInt(input);
        // Each remaining element needs at least one byte. Check before allocation.
        if(n<0||n>limit||n>input.readableBytes())throw new IllegalStateException("Invalid tag configuration "+kind+" count");
        return n;
    }
    private static String name(ByteBuf input){
        String value=ProtocolUtils.readString(input);
        if(!value.matches("[a-z0-9_.-]+:[a-z0-9/._-]+"))throw new IllegalStateException("Invalid tag configuration resource name");
        return value;
    }

    /** Bounded, deterministic first difference; never dump full tag payloads. */
    static String difference(byte[] expected,byte[] destination){
        var before=read(expected);var after=read(destination);
        if(!before.keySet().equals(after.keySet()))return "missingRegistries="+only(before.keySet(),after.keySet())+" addedRegistries="+only(after.keySet(),before.keySet());
        for(String registry:before.keySet()){
            var a=before.get(registry);var b=after.get(registry);
            if(!a.keySet().equals(b.keySet()))return "registry="+label(registry)+" missingTags="+only(a.keySet(),b.keySet())+" addedTags="+only(b.keySet(),a.keySet());
            for(String tag:a.keySet()){
                int[] x=a.get(tag),y=b.get(tag);
                if(Arrays.equals(x,y))continue;
                int index=0;while(index<Math.min(x.length,y.length)&&x[index]==y[index])index++;
                boolean reordered=false;
                if(x.length==y.length){int[] sx=x.clone(),sy=y.clone();Arrays.sort(sx);Arrays.sort(sy);reordered=Arrays.equals(sx,sy);}
                return "registry="+label(registry)+" tag="+label(tag)+" memberCounts="+x.length+"/"+y.length
                    +" memberIndex="+index+" expectedId="+(index<x.length?Integer.toString(x[index]):"<end>")
                    +" destinationId="+(index<y.length?Integer.toString(y[index]):"<end>")+" sameMembersDifferentOrder="+reordered;
            }
        }
        return "tag encoding differs";
    }
    private static String only(Set<String> left,Set<String> right){
        List<String> names=new ArrayList<>();int size=0;
        for(String name:left)if(!right.contains(name)){if(size<8)names.add(label(name));size++;}
        return names+(size>8?" (+"+(size-8)+" more)":"");
    }
    private static String label(String name){return name.length()<=160?name:name.substring(0,160)+"...";}
}
