package com.foundations.cluster.manager;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

final class AuditLog {
    private final Path file;
    AuditLog(Path file) throws IOException {this.file=file;Files.createDirectories(file.getParent());if(!Files.exists(file))Files.createFile(file);}
    synchronized void record(String actor,String action,String target,String detail){
        String line=Instant.now()+"\t"+clean(actor)+"\t"+clean(action)+"\t"+clean(target)+"\t"+clean(detail)+System.lineSeparator();
        try{Files.writeString(file,line,StandardCharsets.UTF_8,StandardOpenOption.APPEND);}catch(IOException ignored){}
    }
    synchronized List<Entry> tail(int limit){
        try{List<String> lines=Files.readAllLines(file,StandardCharsets.UTF_8);int from=Math.max(0,lines.size()-Math.max(1,limit));List<Entry> out=new ArrayList<>();for(String line:lines.subList(from,lines.size())){String[]p=line.split("\\t",5);if(p.length>=4)out.add(new Entry(p[0],p[1],p[2],p[3],p.length>4?p[4]:""));}return out;}catch(IOException e){return List.of();}
    }
    private static String clean(String s){return (s==null?"":s).replace('\t',' ').replace('\n',' ').replace('\r',' ');}
    record Entry(String time,String actor,String action,String target,String detail){}
}
