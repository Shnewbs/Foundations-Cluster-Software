package com.foundations.cluster.node;
import java.util.*;
public final class AuthorityHydrationSelfTest {
 static int checks;
 static void check(boolean pass,String why){if(!pass)throw new AssertionError(why);checks++;}
 public static void main(String[]args){
  var h=new AuthorityHydration();String a="foundations/minecraft:overworld/r.0.0",b="foundations/minecraft:overworld/r.-1.0";
  h.assign(Map.of(a,1L));check(h.pending().equals(Map.of(a,1L)),"New authority not pending");check(!h.ready(a,1),"Ready before restore");
  var first=h.pending();h.complete(first);check(h.ready(a,1),"Restore did not become ready");check(h.pending().isEmpty(),"Completed restore repeated");
  h.assign(Map.of(a,1L));check(h.pending().isEmpty(),"RECOVERING to ACTIVE replays same epoch");check(!h.current(first),"Duplicate response would reapply");
  h.assign(Map.of(a,1L,b,3L));check(h.ready(a,1),"Other region acquisition paused an active region");check(h.pending().equals(Map.of(b,3L)),"Replay included live region");
  var old=h.pending();h.assign(Map.of(a,1L,b,4L));check(!h.current(old),"Stale response accepted");
  try{h.complete(old);throw new AssertionError("Stale completion accepted");}catch(IllegalStateException expected){checks++;}
  check(h.ready(a,1),"Stale response affected independent region");check(!h.ready(b,4),"Unrestored epoch ready");
  h.complete(h.pending());check(h.ready(b,4),"Current response rejected");
  h.assign(Map.of(a,1L));check(!h.ready(b,4),"Released region retained permission");
  h.assign(Map.of(a,1L,b,5L));check(!h.ready(b,5),"Reacquired region skipped restore");check(!h.report().contains("|5"),"Reported unconfirmed epoch");
  check(AuthorityHydration.token(Map.of(b,2L,a,1L)).equals(AuthorityHydration.token(Map.of(a,1L,b,2L))),"Unstable report order");
  h.assign(Map.of());check(h.report().isEmpty(),"Removed assignment leaked ack");check(h.pending().isEmpty(),"No assignments still request replay");
  check(!new AuthorityHydration().ready(a,1),"New process inherited readiness");
  System.out.println("Authority hydration ledger: PASS ("+checks+" checks)");
 }
}
