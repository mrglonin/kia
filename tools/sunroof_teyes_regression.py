#!/usr/bin/env python3
"""Compile the production TEYES observer against deterministic Android/vendor boundaries.

Requires a JDK through JAVA_HOME or PATH; no Gradle, Android device or network is used.
The fixtures verify Binder argument/reply contracts, delivery, retries and stop guards.
They cannot establish native API permission, physical ACC timing or panel-key routing.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


FIXTURES = {
    "android/content/Context.java": """package android.content; public class Context {
 public Context getApplicationContext(){return this;} public String getPackageName(){return "kia.app";}
}""",
    "android/os/IInterface.java": "package android.os; public interface IInterface { IBinder asBinder(); }",
    "android/os/IBinder.java": """package android.os; public interface IBinder {
 int INTERFACE_TRANSACTION=0x5f4e5446; int FLAG_ONEWAY=1;
 boolean transact(int code,Parcel data,Parcel reply,int flags) throws RemoteException;
 IInterface queryLocalInterface(String descriptor);
}""",
    "android/os/RemoteException.java": "package android.os; public class RemoteException extends Exception {}",
    "android/os/Binder.java": """package android.os; public class Binder implements IBinder {
 private IInterface owner; private String descriptor;
 public void attachInterface(IInterface o,String d){owner=o;descriptor=d;}
 public IInterface queryLocalInterface(String d){return d.equals(descriptor)?owner:null;}
 public boolean transact(int code,Parcel data,Parcel reply,int flags) throws RemoteException {
  data.setDataPosition(0);boolean result=onTransact(code,data,reply,flags);
  if(reply!=null)reply.setDataPosition(0);return result;
 }
 protected boolean onTransact(int c,Parcel d,Parcel r,int f) throws RemoteException{return false;}
}""",
    "android/os/Parcel.java": """package android.os; public class Parcel {
 private final java.util.List<Object> values=new java.util.ArrayList<>(); private int position;
 public static Parcel obtain(){return new Parcel();} public void recycle(){}
 public void setDataPosition(int p){position=p;} public int dataAvail(){return values.size()-position;}
 public void writeInterfaceToken(String s){writeString(s);}
 public void enforceInterface(String s){if(!s.equals(readString()))throw new SecurityException();}
 public void writeString(String s){values.add(s);position++;} public String readString(){return (String)values.get(position++);}
 public void writeInt(int i){values.add(i);position++;} public int readInt(){return (Integer)values.get(position++);}
 public void writeNoException(){writeInt(0);} public void readException(){if(readInt()!=0)throw new RuntimeException();}
 public java.util.HashMap<?,?> readHashMap(ClassLoader loader){return (java.util.HashMap<?,?>)values.get(position++);}
}""",
    "android/os/Looper.java": """package android.os; public class Looper {
 public static final Thread MAIN_THREAD=Thread.currentThread(); private static final Looper MAIN=new Looper();
 public static Looper getMainLooper(){return MAIN;}
 public static Looper myLooper(){return Thread.currentThread()==MAIN_THREAD?MAIN:null;}
}""",
    "android/os/Handler.java": """package android.os; public class Handler {
 private static final java.util.Queue<Runnable> ready=new java.util.concurrent.ConcurrentLinkedQueue<>();
 private static final java.util.List<Runnable> delayed=new java.util.ArrayList<>();
 public Handler(Looper l){} public boolean post(Runnable r){ready.add(r);return true;}
 public boolean postDelayed(Runnable r,long d){synchronized(delayed){delayed.add(r);}return true;}
 public void removeCallbacks(Runnable r){ready.remove(r);synchronized(delayed){delayed.remove(r);}}
 public static void drain(){Runnable r;while((r=ready.poll())!=null)r.run();}
 public static boolean hasDelayed(){synchronized(delayed){return !delayed.isEmpty();}}
 public static void runDelayed(){java.util.List<Runnable> copy;synchronized(delayed){copy=new java.util.ArrayList<>(delayed);delayed.clear();}for(Runnable r:copy)r.run();}
}""",
    "android/os/SystemClock.java": """package android.os; public class SystemClock {
 public static volatile long now=1000; public static volatile boolean blockPoll;
 public static java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);
 public static long elapsedRealtime(){
  if(blockPoll&&Thread.currentThread().getName().equals("kia-sunroof-teyes-observer")){
   blockPoll=false;entered.countDown();try{release.await();}catch(InterruptedException e){throw new RuntimeException(e);}
  }return now;
 }
}""",
    "kia/app/core/AppLog.java": "package kia.app.core; public class AppLog { public static void line(android.content.Context c,String s){} }",
    "com/spd/system/aidl/ISpdServiceCallback.java": """package com.spd.system.aidl;
 public interface ISpdServiceCallback extends android.os.IInterface {
 void onServiceConnect(boolean connected); void sendCommonToSouce(int command,int arg1,int arg2);
 int sendKeyCode(int keyCode,int repeats,int action);
}""",
    "com/spd/system/entity/aidl/DeviceStatusInfo.java": """package com.spd.system.entity.aidl;
 public class DeviceStatusInfo { public int accStatus=1; }
""",
    "com/spd/system/entity/aidl/DataWrapper.java": """package com.spd.system.entity.aidl;
 public class DataWrapper { public final DeviceStatusInfo state=new DeviceStatusInfo();
 public Object getSourceInfo(){return state;} }
""",
    "com/spd/system/service/SpdManager.java": """package com.spd.system.service;
 import com.spd.system.aidl.ISpdServiceCallback; import com.spd.system.entity.aidl.DataWrapper;
 public class SpdManager {
 public static final SpdManager INSTANCE=new SpdManager(); public static volatile boolean available=true;
 public volatile ISpdServiceCallback callback; public volatile int registers,unregisters,polls;
 public final DataWrapper data=new DataWrapper();
 public volatile boolean blockFailure;
 public java.util.concurrent.CountDownLatch failureEntered=new java.util.concurrent.CountDownLatch(1),failureRelease=new java.util.concurrent.CountDownLatch(1);
 public static SpdManager getInstance(android.content.Context context){return available?INSTANCE:null;}
 public void registerSpdServiceCallback(String pkg,ISpdServiceCallback c){registers++;callback=c;c.onServiceConnect(true);}
 public void unRegisterSpdServiceCallback(String pkg){unregisters++;callback=null;}
 public DataWrapper getSourceInfo(String source){
  if(!"android".equals(source))throw new AssertionError(source);polls++;
  if(blockFailure){blockFailure=false;failureEntered.countDown();try{failureRelease.await();}catch(InterruptedException e){throw new RuntimeException(e);}throw new IllegalStateException("delayed native failure");}
  return available?data:null;
 }
 }
""",
    "kia/app/sunroof/TeyesObserverRegression.java": r"""package kia.app.sunroof;
import android.os.*; import com.spd.system.service.SpdManager;
import java.util.*; import java.util.function.BooleanSupplier;
public class TeyesObserverRegression {
 static final String DESCRIPTOR="com.spd.system.aidl.ISpdServiceCallback";
 static final List<String> acc=new ArrayList<>(); static final List<Boolean> sleep=new ArrayList<>();
 static final List<String> keys=new ArrayList<>(); static int checks;
 static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
 static void await(BooleanSupplier done) throws Exception {
  long deadline=System.nanoTime()+2_000_000_000L;
  do{Handler.drain();if(done.getAsBoolean())return;Thread.sleep(2);}while(System.nanoTime()<deadline);
  throw new AssertionError("Timed out waiting for observer");
 }
 static Parcel parcel(int... values){Parcel p=Parcel.obtain();p.writeInterfaceToken(DESCRIPTOR);for(int v:values)p.writeInt(v);return p;}
 static void pollNext() throws Exception {await(Handler::hasDelayed);Handler.runDelayed();}
 static void event(IBinder binder,int code,int... values) throws Exception {
  check(binder.transact(code,parcel(values),null,IBinder.FLAG_ONEWAY),"known callback rejected");Handler.drain();
 }
 public static void main(String[] args) throws Exception {
  SpdManager nativeManager=SpdManager.INSTANCE;
  TeyesSunroofObserver observer=new TeyesSunroofObserver(new android.content.Context(),new TeyesSunroofObserver.Listener(){
   public void onAcc(boolean known,boolean on,long elapsed,String source){
    check(Thread.currentThread()==Looper.MAIN_THREAD,"ACC callback off main");acc.add(known+":"+on+":"+elapsed);
   }
   public void onHardwareAction(int key,int action){check(Thread.currentThread()==Looper.MAIN_THREAD,"key callback off main");keys.add(key+":"+action);}
   public void onSleep(boolean asleep){check(Thread.currentThread()==Looper.MAIN_THREAD,"sleep callback off main");sleep.add(asleep);}
  });
  observer.start();observer.start();await(()->acc.size()>=2);
  check(acc.get(0).startsWith("false:false:"),"initial state was not unknown");
  check(acc.get(1).startsWith("true:true:"),"native aggregate baseline missing");
  check(nativeManager.registers==1,"duplicate start registered twice");
  IBinder oldBinder=nativeManager.callback.asBinder();
  // Hold the poll AFTER it has read aggregate ON, then deliver the newer OFF event.
  SystemClock.blockPoll=true;pollNext();await(()->SystemClock.entered.getCount()==0);
  nativeManager.data.state.accStatus=0;event(oldBinder,13,5,1,0);final int acceptedOff=acc.size();
  SystemClock.release.countDown();await(Handler::hasDelayed);Handler.drain();
  check(acc.size()==acceptedOff&&acc.get(acc.size()-1).startsWith("true:false:"),"in-flight ON read overwrote newer ACC OFF");
  // An older failed read must not replace a newer valid callback with unknown, either.
  nativeManager.blockFailure=true;pollNext();await(()->nativeManager.failureEntered.getCount()==0);
  event(oldBinder,13,5,1,0);final int offBeforeFailure=acc.size();nativeManager.failureRelease.countDown();
  await(Handler::hasDelayed);Handler.drain();
  check(acc.size()==offBeforeFailure&&acc.get(acc.size()-1).startsWith("true:false:"),"in-flight failure overwrote newer ACC OFF");
  pollNext();await(()->nativeManager.callback!=null);oldBinder=nativeManager.callback.asBinder();
  check(oldBinder.queryLocalInterface(DESCRIPTOR)==nativeManager.callback,"proxy did not attach its IInterface");
  Parcel descriptor=Parcel.obtain();check(oldBinder.transact(IBinder.INTERFACE_TRANSACTION,Parcel.obtain(),descriptor,0),"descriptor rejected");
  check(DESCRIPTOR.equals(descriptor.readString()),"descriptor mismatch");
  int before=acc.size();event(oldBinder,13,5,1,0);
  check(acc.size()==before+1&&acc.get(acc.size()-1).startsWith("true:false:"),"early ACC-off callback lost");
  before=acc.size();event(oldBinder,13,8,6,0);event(oldBinder,13,8,0,0);event(oldBinder,3,1);
  check(acc.size()==before,"sleep/power metadata became ACC");check(sleep.equals(Arrays.asList(true,false)),"sleep polarity mismatch");
  event(oldBinder,13,5,2,0);event(oldBinder,13,5,0,1);event(oldBinder,13,8,1,0);
  check(acc.size()==before&&sleep.size()==2,"invalid enum/metadata accepted");
  Parcel keyReply=Parcel.obtain();check(oldBinder.transact(14,parcel(24,0,0),keyReply,0),"key rejected");
  keyReply.readException();check(keyReply.readInt()==-1&&keyReply.dataAvail()==0,"key reply changed native action");Handler.drain();
  check(keys.equals(Arrays.asList("24:0")),"routed key not delivered");
  event(oldBinder,14,24,0,2);check(keys.size()==1,"unknown key action delivered");
  check(!oldBinder.transact(99,Parcel.obtain(),null,0),"unknown Binder transaction handled");
  boolean malformedRejected=false;try{oldBinder.transact(13,parcel(5,1,0,9),null,1);}catch(IllegalArgumentException expected){malformedRejected=true;}
  check(malformedRejected&&acc.size()==before,"extra callback data accepted");
  nativeManager.data.state.accStatus=2;pollNext();await(()->acc.get(acc.size()-1).startsWith("false:false:"));
  nativeManager.data.state.accStatus=0;SystemClock.now=2000;pollNext();await(()->acc.get(acc.size()-1).equals("true:false:2000"));
  before=acc.size();SystemClock.now=3000;pollNext();final int duplicateBefore=before;await(()->acc.size()>duplicateBefore);
  check(acc.get(acc.size()-1).equals("true:false:3000"),"same-state poll did not refresh elapsed time");
  int stoppedCount=acc.size();observer.stop();event(oldBinder,13,5,0,0);await(()->nativeManager.callback==null);
  check(acc.size()==stoppedCount,"stopped observer delivered a callback");
  observer.start();await(()->nativeManager.callback!=null&&acc.size()>stoppedCount+1);
  before=acc.size();event(oldBinder,13,5,0,0);check(acc.size()==before,"previous run's callback accepted");
  IBinder currentBinder=nativeManager.callback.asBinder();event(currentBinder,1,0);
  check(acc.get(acc.size()-1).startsWith("false:false:"),"disconnect did not make ACC unknown");
  SpdManager.available=false;pollNext();await(()->nativeManager.callback==null);
  SpdManager.available=true;pollNext();await(()->nativeManager.callback!=null&&acc.get(acc.size()-1).startsWith("true:false:"));
  check(nativeManager.registers>=3,"service availability retry did not register");observer.stop();
  await(()->nativeManager.callback==null);System.out.println("TEYES observer regression passed: "+checks+" assertions");
 }
}
""",
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    source = args.source.expanduser().resolve()
    output = args.output.expanduser().resolve() if args.output else Path(tempfile.mkdtemp(prefix="kia-sunroof-teyes-"))
    output.mkdir(parents=True, exist_ok=True)
    fixture_root = output / "fixtures"
    classes = output / "classes"
    classes.mkdir(exist_ok=True)
    java_home = Path(os.environ["JAVA_HOME"]) if os.environ.get("JAVA_HOME") else None
    commands = {}
    for tool in ("java", "javac"):
        candidate = java_home / "bin" / tool if java_home else None
        commands[tool] = str(candidate) if candidate and candidate.is_file() else shutil.which(tool)
        if not commands[tool]:
            parser.error("Missing " + tool + "; set JAVA_HOME to a JDK")
    for relative, contents in FIXTURES.items():
        path = fixture_root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(contents)
    production = [source / "app/app/src/main/java/kia/app/sunroof" / name
                  for name in ("TeyesSunroofObserver.java", "TeyesSunroofSignals.java")]
    invocation = [commands["javac"], "-encoding", "UTF-8", "-d", str(classes)]
    invocation += [str(path) for path in sorted(fixture_root.rglob("*.java"))] + list(map(str, production))
    compile_run = subprocess.run(invocation, text=True, capture_output=True)
    (output / "compile.log").write_text(compile_run.stdout + compile_run.stderr)
    if compile_run.returncode:
        raise SystemExit(compile_run.stderr)
    test_run = subprocess.run([commands["java"], "-cp", str(classes), "kia.app.sunroof.TeyesObserverRegression"],
                              text=True, capture_output=True)
    (output / "regression.log").write_text(test_run.stdout + test_run.stderr)
    receipt = {"source": str(source), "returncode": test_run.returncode,
               "sha256": {str(path.relative_to(source)): hashlib.sha256(path.read_bytes()).hexdigest()
                          for path in production},
               "boundary": "Deterministic JVM fixtures; physical firmware access, timing and keys unverified"}
    (output / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
    print(test_run.stdout + test_run.stderr, end="")
    print("Artifacts:", output)
    raise SystemExit(test_run.returncode)


if __name__ == "__main__":
    main()
