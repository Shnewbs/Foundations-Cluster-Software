package com.velocitypowered.proxy.connection.backend;
import java.util.*;
public final class HandshakeReplaySelfTest {
 static int checks;
 static void check(boolean ok){if(!ok)throw new AssertionError();checks++;}
 static void rejects(Runnable r){try{r.run();throw new AssertionError("Unsafe replay accepted");}catch(IllegalStateException expected){checks++;}}
 static FoundationsHandshakeCapture.Transcript valid(){var t=new FoundationsHandshakeCapture.Transcript();t.started=true;t.put(true,"neoforge:register",new byte[]{0});t.put(false,"neoforge:register",new byte[]{1,2,3});t.put(true,"neoforge:network",new byte[]{0});t.put(false,"minecraft:register",new byte[]{6});t.complete=true;return t;}
 public static void main(String[] args){
  var t=valid();var c=t.cursor();c.check("neoforge:register",new byte[]{0});check(Arrays.equals(c.replies("neoforge:register").getFirst(),new byte[]{1,2,3}));c.check("neoforge:network",new byte[]{0});c.finish();checks++;
  rejects(()->valid().cursor().check("neoforge:register",new byte[]{1}));
  rejects(()->valid().cursor().check("other:task",new byte[]{0}));
  rejects(()->valid().cursor().finish());
  rejects(()->valid().cursor().replies("neoforge:unknown"));
  rejects(()->new FoundationsHandshakeCapture.Transcript().cursor());
  var limited=new FoundationsHandshakeCapture.Transcript();limited.put(true,"huge",new byte[FoundationsHandshakeCapture.MAX_BYTES+1]);limited.complete=true;rejects(limited::cursor);
  byte[] mutable={4,5};var copy=new FoundationsHandshakeCapture.Transcript();copy.put(true,"sample",mutable);mutable[0]=9;check(copy.requests.get("sample").getFirst()[0]==4);
  int before=t.packets;t.put(false,"extra",new byte[]{2});check(before==t.packets);
  for(String key:List.of("neoforge:register","neoforge:frozen_registry_sync_completed","c:version","c:register","neoforge:known_registry_data_maps","neoforge:extensible_enum_data","neoforge:feature_flags"))check(FoundationsHandshakeCapture.REPLIES.containsKey(key));
  check(Arrays.equals(FoundationsHandshakeCapture.canonicalNetwork(new byte[]{2,0,0,1,0}),FoundationsHandshakeCapture.canonicalNetwork(new byte[]{2,1,0,0,0})));
  rejects(()->FoundationsHandshakeCapture.canonicalNetwork(new byte[]{2,0,0,0,0}));
  check(!FoundationsHandshakeCapture.PASSIVE.contains("custom:challenge"));
  System.out.println("R19 guarded handshake checks: PASS ("+checks+")");
 }
}
