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
        Map<String,String> out = new LinkedHashMap<>();
        if (json == null) return out;
        int i = 0, n = json.length();
        while (i < n && Character.isWhitespace(json.charAt(i))) i++;
        if (i < n && json.charAt(i) == '{') i++;
        while (i < n) {
            while (i < n && (Character.isWhitespace(json.charAt(i)) || json.charAt(i)==',')) i++;
            if (i >= n || json.charAt(i)=='}') break;
            if (json.charAt(i)!='"') break;
            int[] r = readString(json, i); String key = unescape(json.substring(i+1, r[0])); i = r[0]+1;
            while (i<n && Character.isWhitespace(json.charAt(i))) i++;
            if (i<n && json.charAt(i)==':') i++;
            while (i<n && Character.isWhitespace(json.charAt(i))) i++;
            String val;
            if (i<n && json.charAt(i)=='"') { int[] v=readString(json,i); val=unescape(json.substring(i+1,v[0])); i=v[0]+1; }
            else { int start=i; while (i<n && json.charAt(i)!=',' && json.charAt(i)!='}') i++; val=json.substring(start,i).trim(); }
            out.put(key,val);
        }
        return out;
    }

    private static int[] readString(String s, int start) {
        boolean esc=false; int i=start+1;
        for (; i<s.length(); i++) { char c=s.charAt(i); if (esc) esc=false; else if (c=='\\') esc=true; else if (c=='"') break; }
        return new int[]{i};
    }

    private static String unescape(String s) {
        return s.replace("\\n","\n").replace("\\r","\r").replace("\\t","\t").replace("\\\"","\"").replace("\\\\","\\");
    }

    static int i(Map<String,String> m, String k, int d) { try { return Integer.parseInt(m.getOrDefault(k,Integer.toString(d))); } catch(Exception e){ return d; } }
    static long l(Map<String,String> m, String k, long d) { try { return Long.parseLong(m.getOrDefault(k,Long.toString(d))); } catch(Exception e){ return d; } }
    static double d(Map<String,String> m, String k, double d) { try { double value=Double.parseDouble(m.getOrDefault(k,Double.toString(d)));return Double.isFinite(value)?value:d; } catch(Exception e){ return d; } }
    static boolean b(Map<String,String> m, String k, boolean d) { String v=m.get(k); return v==null?d:Boolean.parseBoolean(v); }
}
