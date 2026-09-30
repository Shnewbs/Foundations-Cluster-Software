package com.foundations.cluster.manager;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
/** Read a bounded suffix instead of loading potentially multi-GB worker logs. */
final class LogTail {
    static List<String> read(Path file,int limit)throws IOException{
        try(var in=new RandomAccessFile(file.toFile(),"r")){
            long start=Math.max(0,in.length()-2*1024*1024);in.seek(start);
            byte[] bytes=new byte[(int)(in.length()-start)];in.readFully(bytes);
            String text=new String(bytes,StandardCharsets.UTF_8);
            if(start>0){int newline=text.indexOf('\n');text=newline>=0?text.substring(newline+1):"";}
            var lines=text.lines().toList();return lines.subList(Math.max(0,lines.size()-limit),lines.size());
        }
    }
}
