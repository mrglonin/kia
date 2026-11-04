#!/usr/bin/env python3
"""Production roof router/cache ordering, including deterministic old/new USB reader overlap.

Only fixtures and compiled classes are written to the chosen output directory. No device,
APK, application source or persisted production preferences are modified. Negative controls
alter temporary FrameRouter copies, never the source tree.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import runpy
import shutil
import subprocess
import tempfile


GETTER_HOOK = '''public static volatile boolean pauseOlder;
public static java.util.concurrent.CountDownLatch captured, release;
public static void beginRace(){pauseOlder=true;
captured=new java.util.concurrent.CountDownLatch(1);release=new java.util.concurrent.CountDownLatch(1);}
public static VehicleState vehicle(){VehicleState snapshot;
synchronized(StateStore.class){snapshot=vehicle;}
// Emulate descheduling immediately after the atomic getter and before the router guard.
if(pauseOlder&&Thread.currentThread().getName().equals("older-reader")){
pauseOlder=false;captured.countDown();try{
if(!release.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("getter hook timed out");
}catch(InterruptedException error){Thread.currentThread().interrupt();throw new AssertionError(error);}}
return snapshot;}'''

PROOF = '''package kia.app.sunroof;
import android.content.Context;
import kia.app.core.StateStore;
import kia.app.core.model.VehicleState;
import kia.app.protocol.adapter.FrameRouter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class RouterOrderProof {
 static int checks;
 static Context context;
 static FrameRouter router;
 static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
 static byte[] frame(boolean open){return new byte[]{(byte)0xbb,(byte)0xa1,0x41,7,0x76,(byte)(open?1:0),0};}
 static void reset(){Context.clearPreferences();context=new Context();
 StateStore.setVehicle(context,VehicleState.empty());router=new FrameRouter(context);}
 static boolean cachedOpen(){return SunroofStateMemory.snapshot(context).open;}
 static SunroofAlertPolicy.Kind parked(){SunroofStateMemory.Snapshot saved=SunroofStateMemory.snapshot(context);
 return new SunroofAlertPolicy().update(new SunroofAlertPolicy.Input(true,true,false,100,
 false,false,0,0,true,false,1000000,saved.known,saved.open,saved.acknowledged)).kind;}

 static void concurrentReaders()throws Exception{
  reset();StateStore.beginRace();AtomicReference<Throwable> error=new AtomicReference<>();
  CountDownLatch newerDone=new CountDownLatch(1);
  Thread older=new Thread(()->{try{router.onFrame(frame(true),1,100);}catch(Throwable e){error.compareAndSet(null,e);}},"older-reader");
  older.start();check(StateStore.captured.await(5,TimeUnit.SECONDS),"older reader did not reach snapshot hook");
  Thread newer=new Thread(()->{try{router.onFrame(frame(false),2,200);}catch(Throwable e){error.compareAndSet(null,e);}finally{newerDone.countDown();}},"newer-reader");
  newer.start();boolean observedOverlap=false;long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
  try{
   // Fixed code blocks on the router monitor. Unfixed code completes the newer close
   // before the paused older callback is released, reproducing the actual race.
   while(System.nanoTime()<until){
    if(newerDone.await(1,TimeUnit.MILLISECONDS)||newer.getState()==Thread.State.BLOCKED){observedOverlap=true;break;}
   }
  }finally{StateStore.release.countDown();}
  older.join(5000);newer.join(5000);
  check(observedOverlap,"newer callback did not overlap the older callback");
  check(!older.isAlive()&&!newer.isAlive(),"router callbacks did not terminate");
  if(error.get()!=null)throw new AssertionError("callback failed",error.get());
  check(!cachedOpen(),"CONCURRENT_OLD_READER_OVERWROTE_NEWER_CLOSE_CACHE");
  check(StateStore.vehicle().sunroofKnown&&!StateStore.vehicle().sunroofOpen,"newer live close was overwritten");
  check(StateStore.vehicle().sunroofConnectionEpoch==2,"live state regressed to the older epoch");
  check(parked()==SunroofAlertPolicy.Kind.NONE,"persisted ordering race created a false parking warning");
 }

 static void latePriorEpochAfterClear(){
  reset();router.onFrame(frame(false),2,200);
  StateStore.setVehicle(context,StateStore.vehicle().withoutSunroof());
  router.onFrame(frame(true),1,300);
  check(!cachedOpen(),"OLD_EPOCH_AFTER_CLEAR_OVERWROTE_CLOSED_CACHE");
  check(!StateStore.vehicle().sunroofKnown,"rejected prior-epoch callback restored live roof state");
  check(parked()==SunroofAlertPolicy.Kind.NONE,"prior epoch caused a false parking warning");
 }

 static void lateEarlierTimeAfterClear(){
  reset();router.onFrame(frame(false),5,500);
  StateStore.setVehicle(context,StateStore.vehicle().withoutSunroof());
  router.onFrame(frame(true),5,499);
  check(!cachedOpen(),"OLDER_TIME_AFTER_CLEAR_OVERWROTE_CLOSED_CACHE");
  check(!StateStore.vehicle().sunroofKnown,"rejected older timestamp restored live roof state");
  check(parked()==SunroofAlertPolicy.Kind.NONE,"older timestamp caused a false parking warning");
 }

 static void sameMillisecondPacketsRemainOrdered(){
  reset();router.onFrame(frame(false),1,100);router.onFrame(frame(true),1,200);
  router.onFrame(frame(false),1,200);
  check(!cachedOpen(),"equal-millisecond legitimate close was rejected");
  check(!StateStore.vehicle().sunroofOpen,"equal-millisecond live close was rejected");
  check(parked()==SunroofAlertPolicy.Kind.NONE,"equal-millisecond close caused a false warning");
 }

 static void duplicateDoesNotEraseAcknowledgement(){
  reset();router.onFrame(frame(true),5,500);SunroofStateMemory.acknowledge(context);
  StateStore.setVehicle(context,StateStore.vehicle().withoutSunroof());
  router.onFrame(frame(true),5,501);
  check(cachedOpen(),"real open position was lost on duplicate");
  check(SunroofStateMemory.snapshot(context).acknowledged,"duplicate erased persisted acknowledgement");
  check(parked()==SunroofAlertPolicy.Kind.NONE,"duplicate rearmed acknowledged parking episode");
 }

 static void malformedDoesNotAdvanceFence(){
  reset();router.onFrame(new byte[]{(byte)0xbb,(byte)0xa1,0x41,8,0x76,1,0,0},999,999999);
  check(!SunroofStateMemory.snapshot(context).known,"malformed packet was persisted");
  router.onFrame(frame(false),1,100);
  check(SunroofStateMemory.snapshot(context).known&&!cachedOpen(),"malformed packet poisoned ordering fence");
 }

 static void realNewOpeningStillWorks(){
  reset();router.onFrame(frame(false),1,100);router.onFrame(frame(true),1,101);
  check(cachedOpen(),"valid newer opening was rejected");
  check(!SunroofStateMemory.snapshot(context).acknowledged,"valid opening retained acknowledgement");
  check(parked()==SunroofAlertPolicy.Kind.PARKING_WARNING,"valid saved open failed to warn");
 }

 public static void main(String[]args)throws Exception{
  concurrentReaders();latePriorEpochAfterClear();lateEarlierTimeAfterClear();
  sameMillisecondPacketsRemainOrdered();duplicateDoesNotEraseAcknowledgement();
  malformedDoesNotAdvanceFence();realNewOpeningStillWorks();
  System.out.println("Production sunroof router ordering passed: "+checks+" assertions");
 }
}
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--output', type=Path)
    parser.add_argument('--skip-negative-controls', action='store_true')
    args = parser.parse_args()
    root = args.source.expanduser().resolve()
    output = args.output.expanduser().resolve() if args.output else Path(tempfile.mkdtemp(prefix='kia-sunroof-router-order-'))
    output.mkdir(parents=True, exist_ok=True)
    fixtures_source = root / 'tools/sunroof_controller_regression.py'
    fixtures = dict(runpy.run_path(str(fixtures_source))['FIXTURES'])
    fixtures.pop('kia/app/sunroof/ControllerBackgroundProof.java')
    getter = 'public static VehicleState vehicle(){return vehicle;}'
    state = fixtures['kia/app/core/StateStore.java']
    if getter not in state:
        parser.error('Controller StateStore fixture changed; update the getter scheduling hook explicitly.')
    fixtures['kia/app/core/StateStore.java'] = state.replace('static VehicleState vehicle=', 'static volatile VehicleState vehicle=').replace(getter, GETTER_HOOK)
    fixtures['kia/app/sunroof/RouterOrderProof.java'] = PROOF
    java_home = Path(os.environ['JAVA_HOME']) if os.environ.get('JAVA_HOME') else None
    commands = {name: str(java_home / 'bin' / name) if java_home else shutil.which(name) for name in ('java', 'javac')}
    if not all(commands.values()):
        parser.error('JDK required: set JAVA_HOME or put java/javac on PATH')
    production = [root / 'app/app/src/main/java/kia/app' / path for path in [
        'protocol/adapter/FrameRouter.java', 'sunroof/SunroofStateMemory.java',
        'sunroof/SunroofAlertPolicy.java', 'sunroof/SunroofOverlayGeometry.java',
        'core/model/VehicleState.java', 'core/model/AdapterState.java', 'core/model/RctaState.java']]
    router = production[0]
    router_source = router.read_text()
    fence = '''        if (connectionEpoch < lastSunroofEpoch
                || (connectionEpoch == lastSunroofEpoch
                && receivedElapsed < lastSunroofReceivedElapsed)) return true;
'''

    def run_variant(name, replacement=None):
        directory = output / name
        classes = directory / 'classes'
        classes.mkdir(parents=True, exist_ok=True)
        paths = []
        for rel, text in fixtures.items():
            path = directory / 'fixtures' / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text)
            paths.append(path)
        sources = list(production)
        if replacement is not None:
            temporary_router = directory / 'production-control/kia/app/protocol/adapter/FrameRouter.java'
            temporary_router.parent.mkdir(parents=True, exist_ok=True)
            temporary_router.write_text(replacement)
            sources[0] = temporary_router
        result = subprocess.run([commands['javac'], '-encoding', 'UTF-8', '-d', str(classes),
                                 *map(str, paths + sources)], capture_output=True, text=True)
        (directory / 'compile.log').write_text(result.stdout + result.stderr)
        if result.returncode:
            raise RuntimeError(name + ' failed to compile: ' + result.stderr)
        result = subprocess.run([commands['java'], '-cp', str(classes), 'kia.app.sunroof.RouterOrderProof'],
                                capture_output=True, text=True)
        (directory / 'regression.log').write_text(result.stdout + result.stderr)
        return result

    positive = run_variant('current')
    print(positive.stdout + positive.stderr, end='')
    if positive.returncode:
        raise SystemExit(positive.returncode)
    controls = []
    if not args.skip_negative_controls:
        signature = 'private synchronized boolean handleSunroof('
        if signature not in router_source or fence not in router_source:
            parser.error('Current production synchronization/watermark shape changed; negative controls need explicit review.')
        variants = [
            ('without-serialization', router_source.replace(signature, 'private boolean handleSunroof(', 1),
             'CONCURRENT_OLD_READER_OVERWROTE_NEWER_CLOSE_CACHE'),
            ('without-watermark', router_source.replace(fence, '', 1),
             'OLD_EPOCH_AFTER_CLEAR_OVERWROTE_CLOSED_CACHE'),
        ]
        for name, source, expected in variants:
            result = run_variant(name, source)
            detected = result.returncode != 0 and expected in result.stdout + result.stderr
            controls.append({'variant': name, 'failure_detected': detected, 'expected_failure': expected})
            if not detected:
                raise RuntimeError(name + ' negative control did not fail for the intended ordering bug')
            print('Negative control detected:', name, expected)
    receipt = {'returncode': 0, 'source': str(root), 'production_sha256': {
        str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest() for path in production},
        'fixtures_sha256': hashlib.sha256(fixtures_source.read_bytes()).hexdigest(),
        'negative_controls': controls,
        'boundary': 'Actual production FrameRouter, roof persistence, VehicleState and policy. Android/USB boundary fixtures; deterministic pause after atomic snapshot getter. No Android device or source mutation.'}
    (output / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print('Artifacts:', output)


if __name__ == '__main__':
    main()
