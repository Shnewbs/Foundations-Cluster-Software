package com.foundations.cluster.manager;
public final class RestartAvailabilitySelfTest {
 static int checks;
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);checks++;}
 static WorkerRecord worker(boolean drain,boolean maintenance){var w=new WorkerRecord("worker-001",25566,true);w.ready=true;w.state=WorkerState.READY;w.draining=drain;w.maintenance=maintenance;return w;}
 public static void main(String[] args){
  var w=worker(false,false);w.fenceForStop();check(w.draining&&!w.routableForNewSession(),"Stop must fence routing");
  check(!w.drainAfterRestart(),"Temporary stop drain must not survive restart");
  w.fenceForStop();check(!w.drainAfterRestart(),"Repeated stop overwrote original policy");
  w.releaseStopFence();check(!w.draining&&w.routableForNewSession(),"Cancellation must restore availability");
  w=worker(true,false);w.fenceForStop();check(w.drainAfterRestart(),"Manual drain was lost");w.releaseStopFence();check(w.draining,"Cancel removed pre-existing manual drain");
  w=worker(false,true);w.fenceForStop();w.releaseStopFence();check(w.maintenance&&!w.routableForNewSession(),"Maintenance was lost");
  w=worker(false,false);w.fenceForStop();w.manualDrain(true);check(w.drainAfterRestart(),"Later manual drain was overwritten");w.releaseStopFence();check(w.draining,"Cancel lost later manual drain");
  w=worker(true,false);w.fenceForStop();w.manualDrain(false);check(w.draining&&!w.routableForNewSession(),"Undrain lifted active stop fence");check(!w.drainAfterRestart(),"Later undrain was ignored");w.releaseStopFence();check(!w.draining,"Release did not apply later undrain");
  w.manualDrain(true);check(w.draining&&w.drainBeforeStop==null,"Manual drain needs no lifecycle marker");w.manualDrain(false);check(!w.draining,"Ordinary undrain broken");
  System.out.println("Restart availability: PASS ("+checks+" checks)");
 }
}
