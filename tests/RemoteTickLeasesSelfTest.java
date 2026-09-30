package com.foundations.cluster.node;
import java.util.*;
public final class RemoteTickLeasesSelfTest {
 static int count;static void check(boolean ok,String m){if(!ok)throw new AssertionError(m);count++;}
 public static void main(String[]args){
  var a=new RemoteTickLeases.Key("minecraft:overworld",2,2);var b=new RemoteTickLeases.Key("minecraft:overworld",3,2);var c=new RemoteTickLeases.Key("minecraft:the_nether",2,2);
  var l=new RemoteTickLeases(2,100);
  check(l.size()==0,"empty");check(l.renew(a,"r",1,0)==RemoteTickLeases.Result.ADDED,"first acquires");
  check(l.renew(a,"r",1,50)==RemoteTickLeases.Result.RENEWED&&l.size()==1,"repeated views do not duplicate tickets");
  check(l.expire(100,x->true).isEmpty(),"renew extends expiry");check(l.renew(b,"r",1,100)==RemoteTickLeases.Result.ADDED,"second acquire");
  check(l.renew(c,"n",1,100)==RemoteTickLeases.Result.FULL&&l.size()==2,"capacity bounded");
  check(l.expire(149,x->true).isEmpty(),"not expired early");check(l.expire(150,x->true).size()==1&&l.get(a)==null,"expires exactly at TTL");
  check(l.renew(c,"n",1,150)==RemoteTickLeases.Result.ADDED,"capacity recovers");
  check(l.expire(151,x->x.region().equals("n")).size()==1&&l.get(b)==null,"lost/frozen authority releases ticket");
  boolean rejected=false;try{l.renew(c,"n",2,151);}catch(IllegalStateException e){rejected=true;}check(rejected,"epoch cannot silently refresh old ticket");
  check(l.expire(152,x->x.epoch()==2).size()==1,"old epoch released");check(l.renew(c,"n",2,152)==RemoteTickLeases.Result.ADDED,"new epoch new acquisition");
  check(l.clear().size()==1&&l.size()==0,"stop releases all");check(l.clear().isEmpty(),"stop idempotent");
  boolean bad=false;try{new RemoteTickLeases(0,100);}catch(IllegalArgumentException e){bad=true;}check(bad,"bad capacity rejected");
  bad=false;try{new RemoteTickLeases(1,0);}catch(IllegalArgumentException e){bad=true;}check(bad,"bad TTL rejected");
  bad=false;try{l.renew(a,"r",0,1);}catch(IllegalArgumentException e){bad=true;}check(bad,"no zero epoch");
  var wrap=new RemoteTickLeases(1,100);long start=Long.MAX_VALUE-50;wrap.renew(a,"r",1,start);
  check(wrap.expire(start+99,x->true).isEmpty(),"monotonic clock wrap before expiry");check(wrap.expire(start+100,x->true).size()==1,"monotonic clock wrap at expiry");
  System.out.println("Remote tick leases: PASS ("+count+" checks)");
 }
}
