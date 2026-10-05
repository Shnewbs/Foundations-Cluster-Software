package com.foundations.cluster.manager;

import java.util.LinkedHashMap;
import java.util.Map;

final class Json {
    private Json() {}

    static String q(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '\\' -> b.append("\\\\");
                case '"' -> b.append("\\\"");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> { if (c < 32) b.append(String.format("\\u%04x", (int)c)); else b.append(c); }
            }
        }
        return b.append('"').toString();
    }

    static Map<String,String> flatObject(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        Parser parser = new Parser(json);
        Map<String,String> result = parser.object(0);
        parser.space();
        if (parser.at != json.length()) throw parser.bad();
        return result;
    }

    private static final class Parser {
        final String text;
        int at;
        Parser(String text) { this.text=text; }
        IllegalArgumentException bad() { return new IllegalArgumentException("Invalid JSON at offset " + at); }
        void space() { while (at<text.length() && " \t\r\n".indexOf(text.charAt(at))>=0) at++; }
        boolean take(char c) { space(); if(at<text.length() && text.charAt(at)==c){at++;return true;}return false; }
        void need(char c) { if(!take(c))throw bad(); }
        Map<String,String> object(int depth) {
            if(depth>64)throw bad();
            need('{');Map<String,String> out=new LinkedHashMap<>();
            if(take('}'))return out;
            do {
                space();String key=string();need(':');String val=value(depth+1);
                if(out.putIfAbsent(key,val)!=null)throw bad();
                if(take('}'))return out;
                need(',');
            } while(true);
        }
        String value(int depth) {
            if(depth>64)throw bad();
            space();if(at>=text.length())throw bad();
            char c=text.charAt(at);
            if(c=='"')return string();
            int start=at;
            if(c=='{') object(depth);
            else if(c=='[') {
                at++;if(!take(']')) { do {value(depth+1);if(take(']'))break;need(',');}while(true); }
            } else {
                while(at<text.length() && ",}] \t\r\n".indexOf(text.charAt(at))<0)at++;
                String token=text.substring(start,at);
                if(!token.equals("true") && !token.equals("false") && !token.equals("null")
                        && !token.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))throw bad();
            }
            return text.substring(start,at);
        }
        String string() {
            if(at>=text.length() || text.charAt(at++)!='"')throw bad();
            StringBuilder result=new StringBuilder();
            while(at<text.length()) {
                char c=text.charAt(at++);
                if(c=='"')return result.toString();
                if(c<32)throw bad();
                if(c!='\\'){result.append(c);continue;}
                if(at>=text.length())throw bad();
                char escaped=text.charAt(at++);
                switch(escaped) {
                    case '"','\\','/' -> result.append(escaped);
                    case 'b' -> result.append('\b');
                    case 'f' -> result.append('\f');
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case 'u' -> {
                        if(at+4>text.length())throw bad();
                        int code=0;
                        for(int i=0;i<4;i++){int digit=Character.digit(text.charAt(at++),16);if(digit<0)throw bad();code=(code<<4)|digit;}
                        result.append((char)code);
                    }
                    default -> throw bad();
                }
            }
            throw bad();
        }
    }

    static int i(Map<String,String> m, String k, int d) { try { return Integer.parseInt(m.getOrDefault(k,Integer.toString(d))); } catch(Exception e){ return d; } }
    static long l(Map<String,String> m, String k, long d) { try { return Long.parseLong(m.getOrDefault(k,Long.toString(d))); } catch(Exception e){ return d; } }
    static double d(Map<String,String> m, String k, double d) { try { double value=Double.parseDouble(m.getOrDefault(k,Double.toString(d)));return Double.isFinite(value)?value:d; } catch(Exception e){ return d; } }
    static boolean b(Map<String,String> m, String k, boolean d) { String v=m.get(k); return v==null?d:Boolean.parseBoolean(v); }
}
