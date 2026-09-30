package com.velocitypowered.proxy.connection.backend;

import com.velocitypowered.proxy.protocol.ProtocolUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.*;

/** NeoForge 21.1.250 declaration codecs, not a generic payload normalizer.
 * Data maps: registry -> map ID -> mandatory flag. Enum declarations: class
 * name -> exact entry bytes, including ordered extension values. Feature flags:
 * complete unique resource-name set. Real client replies are never rewritten.
 */
final class FoundationsNeoForgeConfiguration {
    static final String DATA_MAPS="neoforge:known_registry_data_maps",ENUMS="neoforge:extensible_enum_data",FLAGS="neoforge:feature_flags";
    private static final int MAX_ENTRIES=131072;
    static boolean supports(String channel){return channel.equals(DATA_MAPS)||channel.equals(ENUMS)||channel.equals(FLAGS);}

    static byte[] canonical(String channel,byte[] raw){
        var out=Unpooled.buffer();
        try{
            switch(channel){
                case DATA_MAPS->{
                    var registries=dataMaps(raw);ProtocolUtils.writeVarInt(out,registries.size());
                    for(var registry:registries.entrySet()){
                        ProtocolUtils.writeString(out,registry.getKey());ProtocolUtils.writeVarInt(out,registry.getValue().size());
                        for(var entry:registry.getValue().entrySet()){ProtocolUtils.writeString(out,entry.getKey());out.writeBoolean(entry.getValue());}
                    }
                }
                case ENUMS->{
                    var entries=enums(raw);ProtocolUtils.writeVarInt(out,entries.size());
                    // Only class-entry ordering changes. Every byte within an entry stays exact.
                    for(byte[] entry:entries.values())out.writeBytes(entry);
                }
                case FLAGS->{var flags=flags(raw);ProtocolUtils.writeVarInt(out,flags.size());for(String flag:flags)ProtocolUtils.writeString(out,flag);}
                default->throw invalid("unsupported channel");
            }
            byte[] bytes=new byte[out.readableBytes()];out.getBytes(out.readerIndex(),bytes);return bytes;
        }finally{out.release();}
    }

    private static SortedMap<String,SortedMap<String,Boolean>> dataMaps(byte[] raw){
        var input=input(raw);
        try{
            int registries=count(input,FoundationsHandshakeCapture.MAX_PACKETS),total=0;
            SortedMap<String,SortedMap<String,Boolean>> result=new TreeMap<>();
            for(int r=0;r<registries;r++){
                String registry=resource(input);int size=count(input,MAX_ENTRIES-total);total+=size;
                SortedMap<String,Boolean> entries=new TreeMap<>();
                if(result.putIfAbsent(registry,entries)!=null)throw invalid("duplicate data-map registry");
                for(int i=0;i<size;i++){
                    String id=resource(input);boolean mandatory=bool(input);
                    if(entries.putIfAbsent(id,mandatory)!=null)throw invalid("duplicate data-map ID");
                }
            }
            exhausted(input);return result;
        }catch(RuntimeException ex){throw decoding(ex);}finally{input.release();}
    }

    private static SortedMap<String,byte[]> enums(byte[] raw){
        var input=input(raw);
        try{
            int size=count(input,FoundationsHandshakeCapture.MAX_PACKETS),totalMembers=0;
            SortedMap<String,byte[]> result=new TreeMap<>();
            for(int i=0;i<size;i++){
                int start=input.readerIndex();String clazz=ProtocolUtils.readString(input);
                if(clazz.isEmpty())throw invalid("empty enum class name");
                String networkCheck=ProtocolUtils.readString(input);
                if(networkCheck.isEmpty())throw invalid("empty enum network check");
                if(bool(input)){
                    int vanilla=ProtocolUtils.readVarInt(input),total=ProtocolUtils.readVarInt(input);
                    if(vanilla<0||total<vanilla)throw invalid("enum member counts");
                    int members=count(input,MAX_ENTRIES-totalMembers);totalMembers+=members;
                    if(members!=total-vanilla)throw invalid("enum extension count differs");
                    for(int m=0;m<members;m++)ProtocolUtils.readString(input);
                }
                byte[] entry=new byte[input.readerIndex()-start];input.getBytes(start,entry);
                if(result.putIfAbsent(clazz,entry)!=null)throw invalid("duplicate enum class");
            }
            exhausted(input);return result;
        }catch(RuntimeException ex){throw decoding(ex);}finally{input.release();}
    }

    private static SortedSet<String> flags(byte[] raw){
        var input=input(raw);
        try{
            int size=count(input,FoundationsHandshakeCapture.MAX_PACKETS);SortedSet<String> flags=new TreeSet<>();
            for(int i=0;i<size;i++)if(!flags.add(resource(input)))throw invalid("duplicate feature flag");
            exhausted(input);return flags;
        }catch(RuntimeException ex){throw decoding(ex);}finally{input.release();}
    }
    private static ByteBuf input(byte[] raw){
        if(raw.length>FoundationsHandshakeCapture.MAX_BYTES)throw invalid("payload exceeded memory limit");
        return Unpooled.wrappedBuffer(raw);
    }
    private static int count(ByteBuf input,int limit){
        int value=ProtocolUtils.readVarInt(input);
        // Every declared element needs at least one byte; reject before allocation/iteration.
        if(value<0||value>limit||value>input.readableBytes())throw invalid("collection count");
        return value;
    }
    private static boolean bool(ByteBuf input){int value=input.readUnsignedByte();if(value>1)throw invalid("boolean encoding");return value==1;}
    private static String resource(ByteBuf input){
        String value=ProtocolUtils.readString(input);
        if(!value.matches("[a-z0-9_.-]+:[a-z0-9/._-]+"))throw invalid("resource name");
        return value;
    }
    private static void exhausted(ByteBuf input){if(input.isReadable())throw invalid("trailing data");}
    private static IllegalStateException invalid(String reason){return new IllegalStateException("Invalid NeoForge declaration: "+reason);}
    private static IllegalStateException decoding(RuntimeException ex){
        if(ex instanceof IllegalStateException state&&state.getMessage()!=null&&state.getMessage().startsWith("Invalid NeoForge declaration:"))return state;
        return new IllegalStateException("Invalid NeoForge declaration: truncated or malformed encoding",ex);
    }

    static String difference(String channel,byte[] expected,byte[] destination){
        if(channel.equals(DATA_MAPS)){
            var before=dataMaps(expected);var after=dataMaps(destination);
            if(!before.keySet().equals(after.keySet()))return "missingRegistries="+only(before.keySet(),after.keySet())+" addedRegistries="+only(after.keySet(),before.keySet());
            for(String registry:before.keySet()){
                var a=before.get(registry);var b=after.get(registry);
                if(!a.keySet().equals(b.keySet()))return "registry="+label(registry)+" missingDataMaps="+only(a.keySet(),b.keySet())+" addedDataMaps="+only(b.keySet(),a.keySet());
                for(String id:a.keySet())if(!a.get(id).equals(b.get(id)))return "registry="+label(registry)+" dataMap="+label(id)+" mandatory="+a.get(id)+"/"+b.get(id);
            }
        }else if(channel.equals(ENUMS)){
            var before=enums(expected);var after=enums(destination);
            if(!before.keySet().equals(after.keySet()))return "missingEnums="+only(before.keySet(),after.keySet())+" addedEnums="+only(after.keySet(),before.keySet());
            for(String clazz:before.keySet())if(!Arrays.equals(before.get(clazz),after.get(clazz)))return "enumClass="+label(clazz)+" entryBytes="+before.get(clazz).length+"/"+after.get(clazz).length+" sha256="+fingerprint(before.get(clazz))+"/"+fingerprint(after.get(clazz));
        }else if(channel.equals(FLAGS)){
            var before=flags(expected);var after=flags(destination);return "missingFlags="+only(before,after)+" addedFlags="+only(after,before);
        }
        return "declaration encoding differs";
    }
    private static String only(Set<String> left,Set<String> right){
        List<String> names=new ArrayList<>();int size=0;
        for(String name:left)if(!right.contains(name)){if(size<8)names.add(label(name));size++;}
        return names+(size>8?" (+"+(size-8)+" more)":"");
    }
    private static String label(String name){String safe=name.replaceAll("\\p{Cntrl}","?");return safe.length()<=160?safe:safe.substring(0,160)+"...";}
    private static String fingerprint(byte[] bytes){
        try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)).substring(0,12);}
        catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException("SHA-256 unavailable",ex);}
    }
}
