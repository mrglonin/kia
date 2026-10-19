#!/usr/bin/env python3
"""Compile production Gateway/HealthMonitor against deterministic boundary fixtures."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import shutil
import subprocess
import tempfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--source', type=Path, default=Path(__file__).resolve().parents[2],
                    help='Repository containing app/app/src (default: this checkout)')
parser.add_argument('--output', type=Path,
                    help='Retained fixture, class, log and receipt directory')
parser.add_argument('--junit', type=Path, help='JUnit 4 JAR (default: Gradle cache)')
parser.add_argument('--hamcrest', type=Path, help='Hamcrest core JAR (default: Gradle cache)')
args = parser.parse_args()
ROOT = args.source.expanduser().resolve()
OUT = (args.output.expanduser().resolve() if args.output
       else Path(tempfile.mkdtemp(prefix='kia-health-regression-')))
OUT.mkdir(parents=True, exist_ok=True)

def java_tool(name):
    java_home = os.environ.get('JAVA_HOME')
    candidate = Path(java_home)/'bin'/name if java_home else None
    if candidate is not None and candidate.is_file():
        return str(candidate)
    resolved = shutil.which(name)
    if resolved:
        return resolved
    parser.error('Missing ' + name + ': set JAVA_HOME to a JDK or add its bin directory to PATH')


def test_jar(explicit, pattern, option):
    if explicit is not None:
        candidate = explicit.expanduser().resolve()
        if candidate.is_file():
            return candidate
        parser.error(option + ' file does not exist: ' + str(candidate))
    matches = list((Path.home()/'.gradle/caches').glob(pattern))
    if matches:
        return max(matches, key=lambda item: item.stat().st_mtime)
    parser.error('Missing test dependency; pass ' + option + ' or run the project unit tests first')


JAVAC = java_tool('javac')
JAVA = java_tool('java')
fixtures = {
 'android/content/Context.java': 'package android.content; public class Context { public Context getApplicationContext(){return this;} }',
 'android/text/TextUtils.java': 'package android.text; public class TextUtils { public static boolean isEmpty(CharSequence s){return s==null||s.length()==0;} }',
 'android/os/SystemClock.java': 'package android.os; public class SystemClock { public static long now=1000; public static long elapsedRealtime(){return now;} }',
 'android/os/Looper.java': 'package android.os; public class Looper {}',
 'android/os/HandlerThread.java': 'package android.os; public class HandlerThread { public HandlerThread(String name){} public void start(){} public Looper getLooper(){return new Looper();} public boolean quitSafely(){return true;} }',
 'android/os/Handler.java': '''package android.os; public class Handler {
 public static Handler last; public Runnable pending; public long delay;
 public Handler(Looper l){last=this;} public boolean post(Runnable r){pending=r;delay=0;return true;}
 public boolean postDelayed(Runnable r,long d){pending=r;delay=d;return true;}
 public void removeCallbacksAndMessages(Object x){pending=null;}
}''',
 'kia/app/core/AppLog.java': 'package kia.app.core; public class AppLog { public static void line(android.content.Context c,String s){} public static void navigation(android.content.Context c,String s){} }',
 'kia/app/core/StateStore.java': '''package kia.app.core; public class StateStore {
 public static class Update {public Update withExclusiveUsbMode(boolean value){return this;}}
 public static void updateUpdates(android.content.Context c,java.util.function.UnaryOperator<Update> f){f.apply(new Update());}
}''',
 'kia/app/protocol/adapter/FrameRouter.java': '''package kia.app.protocol.adapter; public class FrameRouter {
 public static int frames; public FrameRouter(android.content.Context c){}
 public void onFrame(byte[] data,long epoch,long elapsed){frames++;}
}''',
 'kia/app/tpms/TpmsController.java': '''package kia.app.tpms; public class TpmsController {
 public static final TpmsController INSTANCE=new TpmsController();
 public final java.util.List<Long> connected=new java.util.ArrayList<>();
 public static TpmsController get(android.content.Context c){return INSTANCE;}
 public void onAdapterConnected(long epoch){connected.add(epoch);}
}''',
 'kia/app/transport/usb/UsbTransport.java': '''package kia.app.transport.usb;
import java.util.*; import kia.app.protocol.adapter.AdapterTxOutcome;
public class UsbTransport {
 public interface Listener {void onFrame(byte[] frame,long epoch,long received);}
 public static UsbTransport last; public final Listener listener; public boolean ready=true;
 public long epoch=1,connectedAt=1000,lastRx=1000; public int connects,reconnects;
 public final List<byte[]> writes=new ArrayList<>();
 public UsbTransport(android.content.Context c,Listener l){last=this;listener=l;}
 public void connect(){connects++;} public void close(){ready=false;}
 public boolean ready(){return ready;} public long connectionEpoch(){return epoch;}
 public long connectedAtElapsedRealtime(){return connectedAt;} public long lastRxAtElapsedRealtime(){return lastRx;}
 public void reconnect(String reason){reconnects++;ready=false;}
 public AdapterTxOutcome write(byte[] data,boolean quiet){if(!ready)return AdapterTxOutcome.BLOCKED;writes.add(data.clone());return AdapterTxOutcome.WRITTEN;}
 public void reply(int size,long fromEpoch){byte[] data=new byte[size];data[3]=(byte)size;data[4]=0x56;listener.onFrame(data,fromEpoch,android.os.SystemClock.now);}
}''',
 'kia/app/diagnostics/HealthDiscoveryProbe.java': r'''package kia.app.diagnostics;
import android.content.Context; import android.os.*;
import kia.app.protocol.adapter.*; import kia.app.transport.usb.UsbTransport;
import kia.app.tpms.TpmsController; import java.lang.reflect.*; import java.util.*;
public final class HealthDiscoveryProbe {
 static int passed; interface Check {void run()throws Exception;}
 static void test(String name,Check body)throws Exception{body.run();passed++;System.out.println("PASS "+name);}
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
 static class F {
  Context context=new Context(); AdapterGateway gateway; HealthMonitor monitor; UsbTransport usb; Object session; Method run;
  F()throws Exception{
   Field singleton=AdapterGateway.class.getDeclaredField("instance");singleton.setAccessible(true);singleton.set(null,null);
   TpmsController.INSTANCE.connected.clear();SystemClock.now=1000;
   gateway=AdapterGateway.get(context);usb=UsbTransport.last;monitor=new HealthMonitor(context);
   Class<?> c=Class.forName("kia.app.diagnostics.HealthMonitor$SessionState");Constructor<?> ctor=c.getDeclaredConstructor();ctor.setAccessible(true);session=ctor.newInstance();
   run=HealthMonitor.class.getDeclaredMethod("runTick",c);run.setAccessible(true);
  }
  long tick(long time)throws Exception{SystemClock.now=time;usb.writes.clear();return (Long)run.invoke(monitor,session);}
  long count(byte[] wanted){return usb.writes.stream().filter(x->Arrays.equals(x,wanted)).count();}
  void expect(int snapshot,int uid,int version,int health){
   check(count(AdapterProtocol.requestVehicleSnapshot())==snapshot,"snapshot count "+usb.writes.size());
   check(count(AdapterProtocol.requestAdapterUid())==uid,"uid count");
   check(count(AdapterProtocol.requestAdapterVersion())==version,"version count");
   check(count(AdapterProtocol.requestHealth())==health,"health count");
   check(usb.writes.size()==snapshot+uid+version+health,"unexpected frame");
  }
  int bursts()throws Exception{Field b=session.getClass().getDeclaredField("silentProbeBursts");b.setAccessible(true);return b.getInt(session);}
 }
 public static void main(String[] args)throws Exception{
  test("new session probes identity plus health with exactly one snapshot and one TPMS callback",()->{
   F f=new F();check(f.tick(1000)==750,"cadence changed");f.expect(1,1,1,1);
   check(TpmsController.INSTANCE.connected.equals(Arrays.asList(1L)),"connect callback missing");
   check(f.tick(1750)==750,"cadence changed");f.expect(1,0,0,0);
   check(TpmsController.INSTANCE.connected.size()==1,"callback repeated");
  });
  test("periodic identity requests stop independently as replies arrive",()->{
   F f=new F();f.tick(1000);f.usb.reply(18,1);f.usb.lastRx=6000;f.tick(6250);f.expect(1,0,1,1);
   f.usb.reply(10,1);f.usb.lastRx=11000;f.tick(11500);f.expect(1,0,0,1);
   f.usb.lastRx=16000;f.tick(16750);f.expect(1,0,0,1);
  });
  test("manual and startup full info requests preserve UID version health snapshot",()->{
   F f=new F();f.usb.reply(18,1);f.usb.reply(10,1);
   f.gateway.requestAdapterInfoQuiet();f.expect(1,1,1,1);
   f.usb.writes.clear();f.gateway.requestAdapterInfo();f.expect(1,1,1,1);
  });
  test("reconnect rediscovers identity and triggers TPMS once for the new epoch",()->{
   F f=new F();f.tick(1000);f.usb.reply(18,1);f.usb.reply(10,1);
   f.usb.epoch=2;f.usb.connectedAt=7000;f.usb.lastRx=7000;f.tick(7000);f.expect(1,1,1,1);
   check(TpmsController.INSTANCE.connected.equals(Arrays.asList(1L,2L)),"new epoch notification");
   f.usb.reply(18,1);f.usb.reply(10,1);f.usb.lastRx=12000;f.tick(12250);f.expect(1,1,1,1);
   f.usb.reply(18,2);f.usb.lastRx=17000;f.tick(17500);f.expect(1,0,1,1);
   f.usb.reply(10,2);f.usb.lastRx=22000;f.tick(22750);f.expect(1,0,0,1);
   check(TpmsController.INSTANCE.connected.size()==2,"duplicate epoch callback");
  });
  test("replies before the first health tick are retained for that exact epoch",()->{
   F f=new F();f.usb.reply(18,1);f.usb.reply(10,1);f.tick(1000);f.expect(1,0,0,1);
  });
  test("a silent fresh connection still gets startup grace and then reconnects",()->{
   F f=new F();f.usb.lastRx=0;f.tick(1000);f.tick(6250);f.tick(11500);f.tick(16750);f.tick(20999);
   check(f.usb.reconnects==0,"startup grace lost");
   check(f.tick(21000)==2000,"reconnect delay changed");check(f.usb.reconnects==1,"silent startup did not reconnect");f.expect(0,0,0,0);
  });
  test("fresh RX resets silence and reconnect still needs three completed health bursts",()->{
   F f=new F();f.tick(1000);f.tick(6250);f.tick(11500);f.usb.lastRx=16000;f.tick(16750);
   check(f.bursts()==1,"new RX did not reset probes");
   f.tick(31750);check(f.usb.reconnects==0&&f.bursts()==2,"reconnected without sufficient probes");
   f.tick(37000);check(f.usb.reconnects==0&&f.bursts()==3,"third burst must complete before reconnect");
   f.tick(37750);check(f.usb.reconnects==1,"stale RX did not reconnect after probes");
  });
  test("exclusive firmware mode pauses all health writes and resumes normally",()->{
   F f=new F();f.gateway.beginExclusiveUpdate();f.tick(1000);f.expect(0,0,0,0);
   check(TpmsController.INSTANCE.connected.isEmpty(),"TPMS callback during exclusive update");
   f.gateway.endExclusiveUpdate();f.tick(1750);f.expect(1,1,1,1);check(TpmsController.INSTANCE.connected.size()==1,"resume callback missing");
  });
  test("firmware update rediscovers identity even when the USB bridge keeps its epoch",()->{
   F f=new F();f.tick(1000);f.usb.reply(18,1);f.usb.reply(10,1);
   f.gateway.beginExclusiveUpdate();f.tick(6250);f.expect(0,0,0,0);
   f.gateway.endExclusiveUpdate();f.usb.lastRx=7000;f.tick(7000);f.expect(1,1,1,1);
   check(TpmsController.INSTANCE.connected.size()==1,"same epoch unexpectedly repeated TPMS callback");
   f.usb.reply(18,1);f.usb.reply(10,1);f.gateway.endExclusiveUpdate();
   f.usb.lastRx=12000;f.tick(12250);f.expect(1,0,0,1);
  });
  test("disconnected retry sends no packets and TPMS starts at the first ready tick",()->{
   F f=new F();f.usb.ready=false;check(f.tick(1000)==2000,"disconnected interval");f.expect(0,0,0,0);
   check(f.usb.connects==1&&TpmsController.INSTANCE.connected.isEmpty(),"connect state");
   f.usb.ready=true;f.usb.connectedAt=3000;f.usb.lastRx=3000;f.tick(3000);f.expect(1,1,1,1);
   check(TpmsController.INSTANCE.connected.equals(Arrays.asList(1L)),"ready did not signal TPMS");
  });
  test("stopped or old monitor ticks cannot write or reschedule after restart",()->{
   F f=new F();f.monitor.start();Handler oldHandler=Handler.last;Runnable old=oldHandler.pending;
   f.monitor.stop();check(oldHandler.pending==null,"stop retained callback");old.run();f.expect(0,0,0,0);
   f.monitor.start();Handler current=Handler.last;Runnable next=current.pending;
   old.run();f.expect(0,0,0,0);check(current.pending==next,"old tick changed scheduler");
   next.run();f.expect(1,1,1,1);check(current.delay==750,"new cadence changed");f.monitor.stop();
  });
  System.out.println("RESULT "+passed+" integration checks passed");
 }
}'''
}
fixture_root = OUT/'fixtures'
classes = OUT/'classes'
classes.mkdir(exist_ok=True)
for rel, content in fixtures.items():
    p = fixture_root/rel
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(content+'\n')
main = ROOT/'app/app/src/main/java/kia/app'
sources = [main/p for p in ['protocol/adapter/AdapterProtocol.java', 'protocol/adapter/MediaSourceKind.java', 'protocol/adapter/AdapterCommand.java', 'protocol/adapter/AdapterTxOutcome.java', 'protocol/adapter/AdapterInfoDiscoveryPolicy.java', 'protocol/adapter/AdapterGateway.java', 'diagnostics/HealthMonitor.java', 'diagnostics/HealthMonitorSessionPolicy.java', 'diagnostics/UsbConnectionHealthPolicy.java']]
testroot = ROOT/'app/app/src/test/java/kia/app'
tests = [testroot/p for p in ['protocol/adapter/AdapterInfoDiscoveryPolicyTest.java', 'diagnostics/HealthMonitorSessionPolicyTest.java', 'diagnostics/UsbConnectionHealthPolicyTest.java']]
junit = test_jar(args.junit, 'modules-2/files-2.1/junit/junit/*/*/junit-4*.jar', '--junit')
hamcrest = test_jar(args.hamcrest, 'modules-2/files-2.1/org.hamcrest/hamcrest-core/*/*/hamcrest-core-*.jar', '--hamcrest')
cp = os.pathsep.join(map(str, [classes, junit, hamcrest]))
result = subprocess.run([JAVAC, '-cp', cp, '-d', str(classes), *map(str, sources+tests), *map(str, fixture_root.rglob('*.java'))], capture_output=True, text=True)
(OUT/'compile.log').write_text(result.stdout+result.stderr)
if result.returncode:
    raise SystemExit(result.stderr)
commands = [['kia.app.diagnostics.HealthDiscoveryProbe'], ['org.junit.runner.JUnitCore', 'kia.app.protocol.adapter.AdapterInfoDiscoveryPolicyTest', 'kia.app.diagnostics.HealthMonitorSessionPolicyTest', 'kia.app.diagnostics.UsbConnectionHealthPolicyTest']]
logs = []
for index, args in enumerate(commands):
    result = subprocess.run([JAVA, '-cp', cp, *args], capture_output=True, text=True)
    text = result.stdout+result.stderr
    (OUT/('integration.log' if index == 0 else 'junit.log')).write_text(text)
    print(text, end='')
    logs.append({'command': args, 'exit_code': result.returncode})
    if result.returncode:
        raise SystemExit(result.returncode)
receipt = {'status': 'PASS', 'scope': 'Actual production HealthMonitor, AdapterGateway, AdapterInfoDiscoveryPolicy and protocol compiled with deterministic Android/USB/router/TPMS fixtures', 'integration_checks': 11, 'runs': logs, 'limits': 'No Android device, physical USB, firmware update or power/ACC validation. Fixture boundaries are saved alongside this proof.', 'sha256': {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources+tests}}
(OUT/'receipt.json').write_text(json.dumps(receipt, ensure_ascii=False, indent=2)+'\n')
print('Evidence: ' + str(OUT))
