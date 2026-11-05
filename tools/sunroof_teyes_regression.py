#!/usr/bin/env python3
"""Compile the production TEYES observer/client against deterministic Android boundaries.

Requires a JDK through JAVA_HOME or PATH; no Gradle, Android device or network is used.
No vendor classes exist in the test classpath. The service fixture speaks the retained
Binder wire contract and verifies lookup, delivery, retries, death and stop guards.
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
 String getInterfaceDescriptor() throws RemoteException; boolean isBinderAlive();
 interface DeathRecipient { void binderDied(); }
 void linkToDeath(DeathRecipient recipient,int flags) throws RemoteException;
 boolean unlinkToDeath(DeathRecipient recipient,int flags);
}""",
    "android/os/RemoteException.java": "package android.os; public class RemoteException extends Exception { public RemoteException(){} public RemoteException(String s){super(s);} }",
    "android/os/DeadObjectException.java": "package android.os; public class DeadObjectException extends RemoteException {}",
    "android/os/Binder.java": """package android.os; public class Binder implements IBinder {
 private IInterface owner; private String descriptor;
 private volatile boolean alive=true;
 private final java.util.List<DeathRecipient> deaths=new java.util.concurrent.CopyOnWriteArrayList<>();
 public void attachInterface(IInterface o,String d){owner=o;descriptor=d;}
 public IInterface queryLocalInterface(String d){return d.equals(descriptor)?owner:null;}
 public String getInterfaceDescriptor(){return descriptor;} public boolean isBinderAlive(){return alive;}
 public void linkToDeath(DeathRecipient d,int f) throws RemoteException {if(!alive)throw new DeadObjectException();deaths.add(d);}
 public boolean unlinkToDeath(DeathRecipient d,int f){return deaths.remove(d);}
 public void kill(){alive=false;for(DeathRecipient d:deaths)d.binderDied();}
 public boolean transact(int code,Parcel data,Parcel reply,int flags) throws RemoteException {
  if(!alive)throw new DeadObjectException();
  data.setDataPosition(0);boolean result=onTransact(code,data,reply,flags);
  if(reply!=null)reply.setDataPosition(0);return result;
 }
 protected boolean onTransact(int c,Parcel d,Parcel r,int f) throws RemoteException{return false;}
}""",
    "android/os/Parcel.java": """package android.os; public class Parcel {
 private final java.util.List<Object> values=new java.util.ArrayList<>(); private int position;
 public static Parcel obtain(){return new Parcel();} public void recycle(){}
 public void setDataPosition(int p){position=p/4;} public int dataAvail(){return 4*(values.size()-position);}
 public void writeInterfaceToken(String s){writeString(s);}
 public void enforceInterface(String s){if(!s.equals(readString()))throw new SecurityException();}
 public void writeString(String s){values.add(s);position++;} public String readString(){return (String)values.get(position++);}
 public void writeInt(int i){values.add(i);position++;} public int readInt(){return position<values.size()?(Integer)values.get(position++):0;}
 public void writeStrongBinder(IBinder b){values.add(b);position++;} public IBinder readStrongBinder(){return (IBinder)values.get(position++);}
 public void writeNoException(){writeInt(0);} public void readException(){if(readInt()!=0)throw new RuntimeException();}
 public java.util.HashMap<?,?> readHashMap(ClassLoader loader){return (java.util.HashMap<?,?>)values.get(position++);}
}""",
    "android/os/ServiceManager.java": """package android.os; public class ServiceManager {
 public static volatile IBinder service; public static volatile int lookups;
 public static IBinder getService(String name){if(!"spd".equals(name))throw new AssertionError(name);lookups++;return service;}
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
    "kia/app/sunroof/FakeTeyesService.java": """package kia.app.sunroof;
import android.os.*;
public class FakeTeyesService extends Binder {
 static final String DESCRIPTOR="com.spd.system.aidl.ISpdService";
 static final String DEVICE="com.spd.system.entity.aidl.DeviceStatusInfo";
 public volatile IBinder callback; public volatile int registers,unregisters,polls;
 public volatile int baselineMode,platform=0,acc=1;
 public volatile boolean blockFailure,failRegistration;
 public java.util.concurrent.CountDownLatch failureEntered=new java.util.concurrent.CountDownLatch(1),failureRelease=new java.util.concurrent.CountDownLatch(1);
 public FakeTeyesService(){attachInterface(null,DESCRIPTOR);}
 static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 protected boolean onTransact(int code,Parcel data,Parcel reply,int flags) throws RemoteException {
  require(flags==0&&reply!=null,"service request must be synchronous");
  data.enforceInterface(DESCRIPTOR);
  String argument=data.readString();
  if(code==1){
   require("kia.app".equals(argument),"wrong registration package");IBinder cb=data.readStrongBinder();
   require(data.dataAvail()==0&&cb!=null,"wrong registration payload");
   require("com.spd.system.aidl.ISpdServiceCallback".equals(cb.getInterfaceDescriptor()),"wrong callback descriptor");
   registers++;callback=cb;if(failRegistration)throw new IllegalStateException("registration failed");
   reply.writeNoException();return true;
  }
  if(code==3){
   require("kia.app".equals(argument)&&data.dataAvail()==0,"wrong unregistration payload");
   unregisters++;callback=null;reply.writeNoException();return true;
  }
  if(code!=9)throw new AssertionError("Unexpected service transaction "+code);
  require("android".equals(argument)&&data.dataAvail()==0,"wrong baseline request");polls++;
  if(blockFailure){
   blockFailure=false;failureEntered.countDown();
   try{failureRelease.await();}catch(InterruptedException e){throw new RuntimeException(e);}
   throw new IllegalStateException("delayed native failure");
  }
  if(baselineMode==4)throw new IllegalStateException("aggregate unavailable");
  if(baselineMode==5)throw new DeadObjectException();
  if(baselineMode==6)return false;
  if(baselineMode==12)return true;
  reply.writeNoException();
  if(baselineMode==8)return true;
  if(baselineMode==1){reply.writeInt(0);return true;}
  reply.writeInt(baselineMode==7?2:1);
  if(baselineMode==9)return true;
  reply.writeString(baselineMode==2?null:baselineMode==3?"other.DeviceStatusInfo":DEVICE);
  if(baselineMode==10)return true;
  reply.writeInt(platform);
  if(baselineMode==11)return true;
  reply.writeInt(acc);
  // A real DeviceStatusInfo has a long unrelated tail. Only the verified prefix is needed.
  reply.writeInt(998);reply.writeString("unused vendor tail");return true;
 }
}
""",
    "kia/app/sunroof/TeyesObserverRegression.java": r"""package kia.app.sunroof;
import android.os.*;
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
 static String lastAcc(){return acc.get(acc.size()-1);}
 static Parcel parcel(int... values){Parcel p=Parcel.obtain();p.writeInterfaceToken(DESCRIPTOR);for(int v:values)p.writeInt(v);return p;}
 static void pollNext() throws Exception {await(Handler::hasDelayed);Handler.runDelayed();}
 static void completePoll() throws Exception {await(Handler::hasDelayed);Handler.drain();}
 static void rawEvent(IBinder binder,int code,int... values) throws Exception {
  check(binder.transact(code,parcel(values),null,IBinder.FLAG_ONEWAY),"known callback rejected");
 }
 static void event(IBinder binder,int code,int... values) throws Exception {rawEvent(binder,code,values);Handler.drain();}
 static TeyesSunroofObserver.Listener listener(){
  return new TeyesSunroofObserver.Listener(){
   public void onAcc(boolean known,boolean on,long elapsed,String source){
    check(Thread.currentThread()==Looper.MAIN_THREAD,"ACC callback off main");acc.add(known+":"+on+":"+elapsed);
   }
   public void onHardwareAction(int key,int action){check(Thread.currentThread()==Looper.MAIN_THREAD,"key callback off main");keys.add(key+":"+action);}
   public void onSleep(boolean asleep){check(Thread.currentThread()==Looper.MAIN_THREAD,"sleep callback off main");sleep.add(asleep);}
  };
 }
 public static void main(String[] args) throws Exception {
  boolean vendorAbsent=false;try{Class.forName("com.spd.system.service.SpdManager");}catch(ClassNotFoundException expected){vendorAbsent=true;}
  check(vendorAbsent,"test accidentally packages vendor manager classes");
  FakeTeyesService nativeService=new FakeTeyesService();ServiceManager.service=nativeService;
  TeyesSunroofObserver observer=new TeyesSunroofObserver(new android.content.Context(),listener());
  observer.start();observer.start();completePoll();
  check(nativeService.registers==1&&nativeService.callback!=null,"native Binder registration missing without vendor classes");
  check(acc.size()>=2&&acc.get(0).startsWith("false:false:"),"initial state was not unknown");
  check(acc.get(1).startsWith("true:true:"),"accStatus was not read after platfromID=0");
  check(ServiceManager.lookups==1,"wrong or duplicate service lookup");
  IBinder oldBinder=nativeService.callback;
  // Hold a poll after reading aggregate ON, then deliver a newer callback OFF.
  SystemClock.blockPoll=true;pollNext();await(()->SystemClock.entered.getCount()==0);
  nativeService.acc=0;event(oldBinder,13,5,1,0);final int acceptedOff=acc.size();
  SystemClock.release.countDown();completePoll();
  check(acc.size()==acceptedOff&&lastAcc().startsWith("true:false:"),"in-flight ON read overwrote newer ACC OFF");
  nativeService.blockFailure=true;pollNext();await(()->nativeService.failureEntered.getCount()==0);
  event(oldBinder,13,5,1,0);final int beforeFailure=acc.size();nativeService.failureRelease.countDown();completePoll();
  check(acc.size()==beforeFailure&&lastAcc().startsWith("true:false:"),"in-flight failure overwrote newer ACC OFF");
  check(nativeService.callback==oldBinder&&nativeService.registers==1&&nativeService.unregisters==0,
    "aggregate failure removed a registered callback");
  pollNext();completePoll();
  check(oldBinder.queryLocalInterface(DESCRIPTOR)==null,"raw callback unexpectedly requires a vendor IInterface");
  Parcel descriptor=Parcel.obtain();check(oldBinder.transact(IBinder.INTERFACE_TRANSACTION,Parcel.obtain(),descriptor,0),"descriptor rejected");
  check(DESCRIPTOR.equals(descriptor.readString()),"callback descriptor mismatch");
  int before=acc.size();event(oldBinder,13,5,1,0);
  check(acc.size()==before+1&&lastAcc().startsWith("true:false:"),"early ACC-off callback lost");
  before=acc.size();event(oldBinder,13,8,6,0);event(oldBinder,13,8,0,0);event(oldBinder,3,1);
  check(acc.size()==before,"sleep/power metadata became ACC");check(sleep.equals(Arrays.asList(true,false)),"sleep polarity mismatch");
  event(oldBinder,13,5,2,0);event(oldBinder,13,5,0,1);event(oldBinder,13,8,1,0);
  check(acc.size()==before&&sleep.size()==2,"invalid enum/metadata accepted");
  Parcel keyReply=Parcel.obtain();check(oldBinder.transact(14,parcel(24,0,0),keyReply,0),"key rejected");
  keyReply.readException();check(keyReply.readInt()==-1&&keyReply.dataAvail()==0,"key reply consumed native action");Handler.drain();
  check(keys.equals(Arrays.asList("24:0")),"routed key not delivered");
  event(oldBinder,14,24,0,2);check(keys.size()==1,"unknown key action delivered");
  check(!oldBinder.transact(99,Parcel.obtain(),null,0),"unknown Binder transaction handled");
  boolean rejected=false;try{oldBinder.transact(13,parcel(5,1,0,9),null,1);}catch(IllegalArgumentException expected){rejected=true;}
  check(rejected&&acc.size()==before,"extra callback data accepted");
  rejected=false;try{oldBinder.transact(13,parcel(5),null,1);}catch(IllegalArgumentException expected){rejected=true;}
  check(rejected&&acc.size()==before,"truncated callback became ACC ON");
  rejected=false;try{oldBinder.transact(1,parcel(0,99),null,1);}catch(IllegalArgumentException expected){rejected=true;}
  check(rejected&&nativeService.callback==oldBinder,"malformed disconnect changed binding before validation");
  Parcel wrongToken=Parcel.obtain();wrongToken.writeInterfaceToken("wrong.callback");wrongToken.writeInt(5);wrongToken.writeInt(1);wrongToken.writeInt(0);
  rejected=false;try{oldBinder.transact(13,wrongToken,null,1);}catch(SecurityException expected){rejected=true;}
  check(rejected,"wrong callback descriptor accepted");
  // Null, wrong class, transient failures, unsupported transactions and truncated replies
  // must make ACC unknown while retaining the exact event subscription.
  for(int mode:new int[]{1,2,3,4,6,7,8,9,10,11,12}){
   nativeService.baselineMode=mode;pollNext();completePoll();
   check(lastAcc().startsWith("false:false:"),"unavailable aggregate remained known in mode "+mode);
   check(nativeService.callback==oldBinder&&nativeService.registers==1&&nativeService.unregisters==0,
     "baseline mode "+mode+" replaced the callback");
   final int priorAcc=acc.size();event(oldBinder,13,5,1,0);
   check(acc.size()==priorAcc+1&&lastAcc().startsWith("true:false:"),"ACC event lost in mode "+mode);
  }
  nativeService.baselineMode=0;nativeService.acc=2;pollNext();completePoll();
  check(lastAcc().startsWith("false:false:")&&nativeService.callback==oldBinder,"invalid acc enum accepted or unsubscribed");
  nativeService.platform=1;nativeService.acc=0;SystemClock.now=2000;pollNext();completePoll();
  check(lastAcc().equals("true:false:2000"),"platfromID=1 was misread as ACC ON");
  before=acc.size();SystemClock.now=3000;pollNext();completePoll();
  check(acc.size()==before+1&&lastAcc().equals("true:false:3000"),"same-state poll did not refresh elapsed time");
  int stoppedCount=acc.size();observer.stop();event(oldBinder,13,5,0,0);await(()->nativeService.callback==null);
  check(acc.size()==stoppedCount,"stopped observer delivered a callback");
  observer.start();completePoll();
  before=acc.size();event(oldBinder,13,5,0,0);check(acc.size()==before,"previous run's callback accepted");
  IBinder currentBinder=nativeService.callback;
  // A valid callback queued just before disconnect must also be fenced on delivery.
  before=acc.size();rawEvent(currentBinder,13,5,0,0);event(currentBinder,1,0);
  check(acc.size()==before+1&&lastAcc().startsWith("false:false:"),"queued callback survived disconnect");
  before=acc.size();event(currentBinder,13,5,0,0);check(acc.size()==before,"disconnected token accepted a late callback");
  await(()->nativeService.callback==null);ServiceManager.service=null;pollNext();completePoll();
  check(nativeService.callback==null,"unavailable service kept a callback");
  ServiceManager.service=nativeService;pollNext();completePoll();
  check(nativeService.callback!=null&&lastAcc().startsWith("true:false:"),"service lookup retry failed");
  observer.stop();await(()->nativeService.callback==null);
  nativeService.baselineMode=1;final int registrationsBeforeEmpty=nativeService.registers;
  observer.start();completePoll();
  check(nativeService.callback!=null,"initial empty aggregate removed the callback");
  IBinder emptyBinder=nativeService.callback;event(emptyBinder,13,5,1,0);
  check(lastAcc().startsWith("true:false:"),"initial event-only ACC lost");
  pollNext();completePoll();
  check(nativeService.registers==registrationsBeforeEmpty+1&&nativeService.callback==emptyBinder,"empty aggregate caused registration churn");
  nativeService.baselineMode=5;pollNext();completePoll();
  check(nativeService.callback==null,"dead Binder read retained subscription");
  before=acc.size();event(emptyBinder,13,5,0,0);check(acc.size()==before,"dead binding accepted late event");
  nativeService.baselineMode=0;pollNext();completePoll();
  check(nativeService.callback!=null&&nativeService.callback!=emptyBinder,"dead Binder read did not reconnect");
  IBinder deathBinder=nativeService.callback;
  nativeService.kill();Handler.drain();
  check(lastAcc().startsWith("false:false:"),"DeathRecipient did not clear ACC");
  before=acc.size();event(deathBinder,13,5,0,0);check(acc.size()==before,"dead service token accepted event");
  FakeTeyesService replacement=new FakeTeyesService();replacement.acc=0;ServiceManager.service=replacement;
  pollNext();completePoll();
  check(replacement.callback!=null&&lastAcc().startsWith("true:false:"),"death recipient did not rebind replacement service");
  before=acc.size();event(deathBinder,13,5,0,0);check(acc.size()==before,"old death token reached replacement");
  observer.stop();await(()->replacement.callback==null);
  replacement.failRegistration=true;observer.start();completePoll();
  check(replacement.callback==null,"failed registration retained partial binding");
  replacement.failRegistration=false;pollNext();completePoll();
  check(replacement.callback!=null,"failed registration did not retry");
  observer.stop();await(()->replacement.callback==null);
  FakeTeyesService wrongService=new FakeTeyesService();wrongService.attachInterface(null,"wrong.service");ServiceManager.service=wrongService;
  observer.start();completePoll();
  check(wrongService.registers==0&&lastAcc().startsWith("false:false:"),"wrong service descriptor was used");
  observer.stop();Handler.drain();
  // The package-private locator seam must call the same production Binder client.
  Class<?> locatorType=Class.forName("kia.app.sunroof.TeyesSunroofObserver$ServiceLocator");
  int lookupsBeforeSeam=ServiceManager.lookups;final int[] seamCalls={0};
  Object locator=java.lang.reflect.Proxy.newProxyInstance(locatorType.getClassLoader(),new Class<?>[]{locatorType},
    (proxy,method,arguments)->{if("findService".equals(method.getName())){seamCalls[0]++;return replacement;}throw new AssertionError(method);});
  java.lang.reflect.Constructor<?> ctor=TeyesSunroofObserver.class.getDeclaredConstructor(android.content.Context.class,TeyesSunroofObserver.Listener.class,locatorType);
  ctor.setAccessible(true);TeyesSunroofObserver injected=(TeyesSunroofObserver)ctor.newInstance(new android.content.Context(),listener(),locator);
  injected.start();completePoll();
  check(seamCalls[0]==1&&ServiceManager.lookups==lookupsBeforeSeam&&replacement.callback!=null,"locator seam bypassed actual client or used default lookup");
  // Deterministically pause invalidation after its order reservation, let the old
  // callback deliver, then resume the real CAS/cleanup half of invalidation.
  java.lang.reflect.Field orderField=TeyesSunroofObserver.class.getDeclaredField("signalOrder");orderField.setAccessible(true);
  java.util.concurrent.atomic.AtomicLong orders=(java.util.concurrent.atomic.AtomicLong)orderField.get(injected);
  java.lang.reflect.Field tokenField=TeyesSunroofObserver.class.getDeclaredField("callbackToken");tokenField.setAccessible(true);
  long invalidatedToken=((java.util.concurrent.atomic.AtomicLong)tokenField.get(injected)).get();
  java.lang.reflect.Field generationField=TeyesSunroofObserver.class.getDeclaredField("generation");generationField.setAccessible(true);
  long activeGeneration=generationField.getLong(injected),reservedDisconnectOrder=orders.incrementAndGet();
  event(replacement.callback,13,5,0,0);check(lastAcc().startsWith("true:true:"),"race setup callback was not delivered");
  java.lang.reflect.Method invalidate=TeyesSunroofObserver.class.getDeclaredMethod("invalidateBinding",long.class,long.class,String.class,String.class,long.class);
  invalidate.setAccessible(true);invalidate.invoke(injected,activeGeneration,invalidatedToken,"disconnected","test:disconnect",reservedDisconnectOrder);
  Handler.drain();check(lastAcc().startsWith("false:false:"),"newer old-binding callback suppressed true disconnect");
  await(()->replacement.callback==null);pollNext();completePoll();
  check(lastAcc().startsWith("true:false:"),"replacement baseline missing after ordered disconnect");
  java.lang.reflect.Method lateDisconnect=TeyesSunroofObserver.class.getDeclaredMethod("publishDisconnected",long.class,long.class,String.class,String.class,long.class);
  lateDisconnect.setAccessible(true);before=acc.size();
  lateDisconnect.invoke(injected,activeGeneration,invalidatedToken,"old disconnect","test:old-disconnect",orders.incrementAndGet());
  Handler.drain();check(acc.size()==before&&lastAcc().startsWith("true:false:"),"late disconnect replaced a new binding's baseline");
  injected.stop();await(()->replacement.callback==null);
  System.out.println("TEYES raw Binder observer regression passed: "+checks+" assertions");
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
    # Never retain an older run's vendor stubs or compiled classes in this classpath.
    for generated in (fixture_root, classes):
        if generated.exists():
            shutil.rmtree(generated)
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
    binder_source = source / "app/app/src/main/java/kia/app/sunroof/TeyesSunroofBinder.java"
    if binder_source.is_file():
        production.append(binder_source)
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
               "fixture_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
               "vendor_classes_on_classpath": False,
               "boundary": "Deterministic JVM raw Binder fixture; physical firmware access, timing and keys unverified"}
    (output / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
    print(test_run.stdout + test_run.stderr, end="")
    print("Artifacts:", output)
    raise SystemExit(test_run.returncode)


if __name__ == "__main__":
    main()
