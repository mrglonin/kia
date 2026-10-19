#!/usr/bin/env python3
"""Verify actual TPMS controller scheduling, lifecycle and monotonic state with deterministic boundaries."""
from pathlib import Path
import argparse, hashlib, json, os, shutil, subprocess, tempfile

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--source',type=Path,default=Path(__file__).resolve().parents[2])
parser.add_argument('--output',type=Path)
parser.add_argument('--junit',type=Path)
parser.add_argument('--hamcrest',type=Path)
args=parser.parse_args()
SOURCE=args.source.expanduser().resolve()
BASE=args.output.expanduser().resolve() if args.output else Path(tempfile.mkdtemp(prefix='kia-scheduler-regression-'))
BASE.mkdir(parents=True,exist_ok=True)
FIXTURES=BASE/'fixture-src'; CLASSES=BASE/'classes'; CLASSES.mkdir(exist_ok=True)
def java_tool(name):
    candidate=Path(os.environ['JAVA_HOME'])/'bin'/name if os.environ.get('JAVA_HOME') else None
    resolved=str(candidate) if candidate and candidate.is_file() else shutil.which(name)
    if not resolved: parser.error('Missing '+name+'; set JAVA_HOME or PATH')
    return resolved

def jar(explicit,pattern):
    if explicit: return explicit.expanduser().resolve()
    matches=list((Path.home()/'.gradle/caches').glob(pattern))
    if not matches: parser.error('Missing JUnit/Hamcrest; run unit tests first or pass jar paths')
    return max(matches,key=lambda p:p.stat().st_mtime)

FILES = {'android/Manifest.java': 'package android; public class Manifest { public static class permission { public '
                          'static final String POST_NOTIFICATIONS="notify"; }}',
 'android/R.java': 'package android; public class R { public static class drawable { public static final int '
                   'stat_sys_warning=1; }}',
 'android/content/pm/PackageManager.java': 'package android.content.pm; public class PackageManager { public '
                                           'static final int PERMISSION_GRANTED=0; }',
 'android/content/pm/ApplicationInfo.java': 'package android.content.pm; public class ApplicationInfo { '
                                            'public int icon=1; }',
 'android/content/Context.java': 'package android.content; public class Context { public static final String '
                                 'NOTIFICATION_SERVICE="notification"; public Context '
                                 'getApplicationContext(){return this;} public Object '
                                 'getSystemService(String name){return new '
                                 'android.app.NotificationManager();} public int checkSelfPermission(String '
                                 'p){return 0;} public android.content.pm.ApplicationInfo '
                                 'getApplicationInfo(){return new android.content.pm.ApplicationInfo();} }',
 'android/content/Intent.java': 'package android.content; public class Intent { public static final int '
                                'FLAG_ACTIVITY_NEW_TASK=1,FLAG_ACTIVITY_SINGLE_TOP=2; public Intent(Context '
                                'c,Class<?> cls){} public Intent addFlags(int flags){return this;} }',
 'android/os/Build.java': 'package android.os; public class Build { public static class VERSION { public '
                          'static final int SDK_INT=32; }}',
 'android/os/Looper.java': 'package android.os; public class Looper { public final String name; public '
                           'Looper(String n){name=n;} public static final Looper MAIN=new Looper("main"); '
                           'public static final ThreadLocal<Looper> current=new ThreadLocal<>(); public '
                           'static Looper getMainLooper(){return MAIN;} public static Looper '
                           'myLooper(){Looper l=current.get();return l==null?MAIN:l;} }',
 'android/text/TextUtils.java': 'package android.text; public class TextUtils { public static boolean '
                                'isEmpty(CharSequence s){return s==null||s.length()==0;} }',
 'android/app/PendingIntent.java': 'package android.app; public class PendingIntent { public static final '
                                   'int FLAG_UPDATE_CURRENT=1,FLAG_IMMUTABLE=2; public static PendingIntent '
                                   'getActivity(android.content.Context c,int id,android.content.Intent '
                                   'i,int f){return new PendingIntent();} }',
 'android/app/NotificationChannel.java': 'package android.app; public class NotificationChannel { public '
                                         'NotificationChannel(String id,String name,int priority){} public '
                                         'void enableVibration(boolean v){} public void setSound(Object '
                                         's,Object a){} }',
 'android/app/NotificationManager.java': 'package android.app; public class NotificationManager { public '
                                         'static final int IMPORTANCE_LOW=1; public static int '
                                         'notifications,cancellations; public void '
                                         'createNotificationChannel(NotificationChannel c){} public void '
                                         'notify(int id,Notification n){notifications++;} public void '
                                         'cancel(int id){cancellations++;} }',
 'android/app/Notification.java': 'package android.app; public class Notification { public static final '
                                  'String CATEGORY_STATUS="status"; public static final int PRIORITY_LOW=1; '
                                  'public static class BigTextStyle { public BigTextStyle bigText(String '
                                  't){return this;} } public static class Builder { public '
                                  'Builder(android.content.Context c){} public '
                                  'Builder(android.content.Context c,String channel){} public Builder '
                                  'setSmallIcon(int x){return this;} public Builder setContentTitle(String '
                                  'x){return this;} public Builder setContentText(String x){return this;} '
                                  'public Builder setStyle(BigTextStyle x){return this;} public Builder '
                                  'setCategory(String x){return this;} public Builder setPriority(int '
                                  'x){return this;} public Builder setDefaults(int x){return this;} public '
                                  'Builder setOnlyAlertOnce(boolean x){return this;} public Builder '
                                  'setAutoCancel(boolean x){return this;} public Builder setShowWhen(boolean '
                                  'x){return this;} public Builder setWhen(long x){return this;} public '
                                  'Builder setContentIntent(PendingIntent x){return this;} public '
                                  'Notification build(){return new Notification();} } }',
 'android/os/Handler.java': 'package android.os;\n'
                            'import java.util.*;\n'
                            'public class Handler {\n'
                            ' public static volatile long now; public static long serial; public final '
                            'Looper looper;\n'
                            ' private static final PriorityQueue<Task> q=new '
                            'PriorityQueue<>(Comparator.comparingLong((Task '
                            't)->t.at).thenComparingLong(t->t.serial));\n'
                            ' static class Task { Handler owner;Runnable run;long at,serial;Task(Handler '
                            'h,Runnable r,long t){owner=h;run=r;at=t;serial=Handler.serial++;} }\n'
                            ' public Handler(Looper l){looper=l;}\n'
                            ' public boolean post(Runnable r){return postDelayed(r,0);}\n'
                            ' public boolean postDelayed(Runnable r,long delay){synchronized(q){q.add(new '
                            'Task(this,r,now+Math.max(0,delay)));}return true;}\n'
                            ' public void removeCallbacks(Runnable '
                            'r){synchronized(q){q.removeIf(t->t.owner==this&&t.run==r);}}\n'
                            ' public static void '
                            'reset(){synchronized(q){q.clear();now=0;serial=0;HandlerThread.starts=0;}}\n'
                            ' public static int pending(Runnable r){synchronized(q){int n=0;for(Task '
                            't:q)if(t.run==r)n++;return n;}}\n'
                            ' public static long next(Runnable r){synchronized(q){long '
                            'next=Long.MAX_VALUE;for(Task t:q)if(t.run==r)next=Math.min(next,t.at);return '
                            'next;}}\n'
                            ' public static Runnable take(Runnable r){synchronized(q){Task '
                            'found=null;for(Task '
                            't:q)if(t.run==r&&(found==null||t.at<found.at))found=t;if(found==null)throw new '
                            'AssertionError("callback absent");q.remove(found);now=found.at;return '
                            'found.run;}}\n'
                            ' static void execute(Task t){Looper '
                            'old=Looper.current.get();Looper.current.set(t.owner.looper);try{t.run.run();}finally{Looper.current.set(old);}}\n'
                            ' public static void drainLooper(Looper l){while(true){Task '
                            'found=null;synchronized(q){for(Task '
                            't:q)if(t.owner.looper==l&&t.at<=now&&(found==null||t.at<found.at))found=t;if(found==null)return;q.remove(found);}execute(found);}}\n'
                            ' public static void advance(long to){int guard=0;while(true){Task '
                            't;synchronized(q){t=q.peek();if(t==null||t.at>to){now=to;return;}q.poll();now=t.at;}execute(t);if(++guard>20000)throw '
                            'new AssertionError("runaway queue");}}\n'
                            '}\n',
 'kia/app/core/AppLog.java': 'package kia.app.core; public class AppLog { public static void '
                             'line(android.content.Context c,String s){} }',
 'kia/app/core/StateStore.java': 'package kia.app.core; public class StateStore { private static '
                                 'kia.app.core.model.TpmsState state=kia.app.core.model.TpmsState.empty(); '
                                 'public static synchronized kia.app.core.model.TpmsState tpms(){return '
                                 'state;} public static synchronized void setTpms(android.content.Context '
                                 'c,kia.app.core.model.TpmsState s){state=s;} }',
 'kia/app/core/settings/AppSettings.java': 'package kia.app.core.settings; public class AppSettings { public '
                                           'static boolean alerts=true; public static int '
                                           'low=220,high=280,lowTemp=-20,highTemp=70; public static boolean '
                                           'tpmsAlertsEnabled(android.content.Context c){return alerts;} '
                                           'public static int tpmsLowPressureKpa(android.content.Context '
                                           'c){return low;} public static int '
                                           'tpmsHighPressureKpa(android.content.Context c){return high;} '
                                           'public static int tpmsLowTempC(android.content.Context c){return '
                                           'lowTemp;} public static int '
                                           'tpmsHighTempC(android.content.Context c){return highTemp;} }',
 'kia/app/entry/MainActivity.java': 'package kia.app.entry; public class MainActivity {}',
 'kia/app/tpms/TpmsWarningOverlayController.java': 'package kia.app.tpms; public class '
                                                   'TpmsWarningOverlayController { public static final '
                                                   'TpmsWarningOverlayController INSTANCE=new '
                                                   'TpmsWarningOverlayController(); public String '
                                                   'details=""; public int calls,clears; public static '
                                                   'TpmsWarningOverlayController get(android.content.Context '
                                                   'c){return INSTANCE;} public void apply(int[] '
                                                   'levels,String text){details=text;calls++;} public void '
                                                   'clear(){details="";clears++;} }',
 'kia/app/protocol/adapter/AdapterProtocol.java': 'package kia.app.protocol.adapter; public class '
                                                  'AdapterProtocol { public static final int '
                                                  'CMD_TPMS=0x51,CMD_TPMS_LEGACY=0x7b; }',
 'kia/app/protocol/adapter/AdapterGateway.java': 'package kia.app.protocol.adapter;\n'
                                                 'public class AdapterGateway {\n'
                                                 ' public static final AdapterGateway INSTANCE=new '
                                                 'AdapterGateway(); public boolean '
                                                 'ready=true,exclusive;public Runnable onPoll;public int '
                                                 'active,maxActive;\n'
                                                 ' public final java.util.List<Long> attempts=new '
                                                 'java.util.ArrayList<>(),written=new '
                                                 'java.util.ArrayList<>();public final '
                                                 'java.util.List<String> loopers=new '
                                                 'java.util.ArrayList<>();\n'
                                                 ' public static AdapterGateway get(android.content.Context '
                                                 'c){return INSTANCE;}\n'
                                                 ' public void '
                                                 'pollTpms(){active++;maxActive=Math.max(maxActive,active);try{attempts.add(android.os.Handler.now);loopers.add(android.os.Looper.myLooper().name);if(ready&&!exclusive)written.add(android.os.Handler.now);if(onPoll!=null)onPoll.run();}finally{active--;}}\n'
                                                 ' public void '
                                                 'reset(){ready=true;exclusive=false;onPoll=null;active=0;maxActive=0;attempts.clear();written.clear();loopers.clear();}\n'
                                                 '}\n',
 'android/os/HandlerThread.java': 'package android.os; public class HandlerThread { public static int '
                                  'starts; private final Looper looper; public HandlerThread(String '
                                  'name){looper=new Looper(name);} public void start(){starts++;} public '
                                  'Looper getLooper(){return looper;} }',
 'android/os/SystemClock.java': 'package android.os; public class SystemClock { public static long '
                                'elapsedRealtime(){return Handler.now;} }'}
FILES['kia/app/tpms/TpmsSchedulerRegression.java'] = r'''package kia.app.tpms;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import kia.app.core.StateStore;
import kia.app.core.model.TpmsState;
import kia.app.core.settings.AppSettings;
import kia.app.protocol.adapter.AdapterGateway;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class TpmsSchedulerRegression {
    static int passed,failed;
    interface Check {void run() throws Exception;}
    static void check(boolean v,String message){if(!v)throw new AssertionError(message);}
    static void test(String name,Check body){try{body.run();passed++;System.out.println("PASS "+name);}catch(Throwable t){failed++;System.out.println("FAIL "+name+": "+t);}}
    static void clearSingleton(Class<?> cls)throws Exception{Field f=cls.getDeclaredField("instance");f.setAccessible(true);f.set(null,null);}
    static final class F {
        final Context app=new Context();final TpmsController controller;final AdapterGateway gateway=AdapterGateway.INSTANCE;
        F()throws Exception{Handler.reset();gateway.reset();AppSettings.alerts=true;AppSettings.low=220;AppSettings.high=280;AppSettings.lowTemp=-20;AppSettings.highTemp=70;StateStore.setTpms(app,TpmsState.empty());clearSingleton(TpmsController.class);clearSingleton(TpmsAlertController.class);controller=TpmsController.get(app);TpmsWarningOverlayController.INSTANCE.details="";TpmsWarningOverlayController.INSTANCE.calls=0;}
        Runnable tick(){try{Field f=TpmsController.class.getDeclaredField("pollTick");f.setAccessible(true);return (Runnable)f.get(controller);}catch(Exception e){throw new RuntimeException(e);}}
        void start(){controller.startPolling();Handler.advance(Handler.now);}
        long next(){return Handler.next(tick());}
        int pending(){return Handler.pending(tick());}
        void frame(int pressure){controller.handleAdapterFrame(legacy(1,pressure,20,0));}
    }
    static byte[] legacy(int mask,int pressure,int temp,int flags){byte[] f=new byte[23];f[0]=(byte)0xbb;f[1]=0x41;f[2]=(byte)0xa1;f[3]=23;f[4]=0x7b;f[5]=(byte)mask;for(int i=0;i<4;i++){int at=6+i*4;f[at]=(byte)pressure;f[at+1]=(byte)(pressure>>>8);f[at+2]=(byte)temp;f[at+3]=(byte)flags;}return f;}
    static TpmsState state(int pressure,long observedAt){TpmsState s=TpmsState.empty();for(int i=0;i<4;i++)s=s.withWheelAt(i,pressure,20,0,"fixture",observedAt);return s;}
    static void await(CountDownLatch latch){try{if(!latch.await(2,TimeUnit.SECONDS))throw new AssertionError("latch timeout");}catch(InterruptedException e){throw new RuntimeException(e);}}
    public static void main(String[] args)throws Exception{
        test("background immediately polls on dedicated worker then every 120 seconds",()->{F f=new F();f.start();check(f.next()==120000,"background deadline");Handler.advance(240000);check(f.gateway.attempts.equals(Arrays.asList(0L,120000L,240000L)),"background cadence");check(f.gateway.loopers.stream().allMatch("KiaTpmsPolling"::equals),"USB on wrong looper");});
        test("full dashboard keeps five-second cadence",()->{F f=new F();f.controller.setPollingMode(TpmsPollingMode.FULL_DASHBOARD);f.start();Handler.advance(15000);check(f.gateway.attempts.equals(Arrays.asList(0L,5000L,10000L,15000L)),"full cadence");});
        test("embedded widget keeps 30-second cadence",()->{F f=new F();f.controller.setPollingMode(TpmsPollingMode.EMBEDDED_WIDGET);f.start();Handler.advance(60000);check(f.gateway.attempts.equals(Arrays.asList(0L,30000L,60000L)),"widget cadence");});
        test("mode changes request immediately and replace old timer",()->{F f=new F();f.start();Handler.advance(1000);f.controller.setPollingMode(TpmsPollingMode.FULL_DASHBOARD);Handler.advance(1000);check(f.next()==6000&&f.pending()==1,"foreground transition");Handler.advance(2000);f.controller.setPollingMode(TpmsPollingMode.BACKGROUND);Handler.advance(2000);check(f.next()==122000&&f.pending()==1,"background transition");});
        test("repeated starts and unchanged modes do not duplicate timers",()->{F f=new F();f.start();Handler.advance(1000);for(int i=0;i<20;i++){f.controller.startPolling();f.controller.setPollingMode(TpmsPollingMode.BACKGROUND);}check(f.next()==120000&&f.pending()==1&&f.gateway.attempts.size()==1,"duplicate timer");check(HandlerThread.starts==1,"extra worker");});
        test("warning and critical arrivals expedite fixed poll deadline",()->{F f=new F();f.start();Handler.advance(1000);f.frame(210);check(f.next()==5000,"warning postponed from arrival");Handler.advance(5000);Handler.advance(5100);f.frame(180);check(f.next()==6000,"critical postponed from arrival");Handler.advance(6000);check(f.gateway.attempts.equals(Arrays.asList(0L,5000L,6000L)),"severity cadence");});
        test("normal recovery restores current base deadline",()->{F f=new F();f.start();f.frame(180);check(f.next()==1000,"critical");Handler.advance(100);f.frame(240);check(f.next()==120000,"normal recovery deadline");});
        test("normal measurement changes never move existing deadline",()->{F f=new F();f.start();Handler.advance(1000);f.frame(240);Handler.advance(2000);f.frame(250);check(f.next()==120000&&f.gateway.attempts.size()==1,"normal delta shifted deadline");});
        test("response latency does not accumulate in polling interval",()->{F f=new F();f.controller.setPollingMode(TpmsPollingMode.FULL_DASHBOARD);Handler response=new Handler(Looper.getMainLooper());f.gateway.onPoll=()->response.postDelayed(()->f.frame(240),100);f.start();Handler.advance(10300);check(f.gateway.attempts.equals(Arrays.asList(0L,5000L,10000L)),"response drift "+f.gateway.attempts);});
        test("400 partial frames cannot postpone whole-TPMS polling",()->{F f=new F();f.controller.setPollingMode(TpmsPollingMode.FULL_DASHBOARD);long wall=System.currentTimeMillis();StateStore.setTpms(f.app,state(240,wall));f.start();for(int t=500;t<=200000;t+=500){Handler.advance(t);f.frame(240);}check(f.gateway.attempts.size()==41&&f.next()==205000,"partial stream starved poll");check(StateStore.tpms().wheelUpdatedAt[1]==wall,"other wheel timestamp changed");check(!StateStore.tpms().isWheelFresh(1)&&StateStore.tpms().isWheelFresh(0),"per-wheel expiry lost");});
        test("complete push stream also preserves configured polling guarantee",()->{F f=new F();f.controller.setPollingMode(TpmsPollingMode.FULL_DASHBOARD);f.start();for(int t=500;t<=10000;t+=500){Handler.advance(t);f.controller.handleAdapterFrame(legacy(15,240,20,0));}check(f.gateway.attempts.equals(Arrays.asList(0L,5000L,10000L)),"complete stream shifted deadline");});
        test("usable reconnect promptly replaces dropped background startup request",()->{F f=new F();f.gateway.ready=false;f.start();Handler.advance(1000);f.gateway.ready=true;f.controller.onAdapterConnected(1);Handler.advance(1000);check(f.gateway.written.equals(Arrays.asList(1000L)),"reconnect delay");check(f.next()==121000,"reconnect base");});
        test("same and stale connection epochs do not trigger duplicate requests",()->{F f=new F();f.start();Handler.advance(1000);f.controller.onAdapterConnected(5);Handler.advance(1000);for(int i=0;i<10;i++){f.controller.onAdapterConnected(5);f.controller.onAdapterConnected(4);f.controller.onAdapterConnected(0);}check(f.pending()==1&&f.next()==121000&&f.gateway.attempts.size()==2,"duplicate epoch polling");f.controller.onAdapterConnected(6);Handler.advance(1000);check(f.gateway.attempts.size()==3,"new epoch missing");});
        test("settings escalation updates deadline immediately",()->{F f=new F();StateStore.setTpms(f.app,state(240,System.currentTimeMillis()));f.start();Handler.advance(1000);AppSettings.low=275;f.controller.onSettingsChanged();check(f.next()==1000,"settings did not expedite");Handler.advance(1000);check(f.next()==2000,"critical settings cadence");});
        test("disabling alerts immediately restores background deadline",()->{F f=new F();f.start();f.frame(180);Handler.advance(100);AppSettings.alerts=false;f.controller.onSettingsChanged();check(f.next()==120000,"disabled fast cadence persisted");check(TpmsWarningOverlayController.INSTANCE.details.isEmpty(),"disabled alert remained");});
        test("expired critical sample clears alert and restores base cadence",()->{F f=new F();StateStore.setTpms(f.app,state(180,System.currentTimeMillis()));f.start();StateStore.setTpms(f.app,state(180,System.currentTimeMillis()-TpmsState.WHEEL_STALE_AFTER_MS-1));Handler.advance(1000);check(f.next()==121000,"stale cadence");check(TpmsWarningOverlayController.INSTANCE.details.isEmpty(),"stale alert");});
        test("manual request uses worker and survives an intervening normal frame",()->{F f=new F();f.start();Handler.advance(1000);f.controller.requestNow();f.frame(240);check(f.gateway.attempts.size()==1,"manual request did synchronous IO");check(f.next()==1000,"frame postponed forced request");Handler.advance(1000);check(f.gateway.attempts.size()==2&&f.gateway.loopers.get(1).equals("KiaTpmsPolling"),"manual worker missing");});
        test("RX state and alert are updated synchronously for downstream forwarding",()->{F f=new F();f.start();boolean handled=f.controller.handleAdapterFrame(legacy(1,180,20,0));check(handled&&StateStore.tpms().pressureKpa[0]==180,"state not synchronous");check(!TpmsWarningOverlayController.INSTANCE.details.isEmpty(),"alert not synchronous");});
        test("stop fences queued callbacks and subsequent incoming alerts",()->{F f=new F();f.start();Runnable old=f.tick();f.controller.stopPolling();TpmsAlertController.get(f.app).clear();int calls=TpmsWarningOverlayController.INSTANCE.calls;f.frame(180);old.run();Handler.advance(300000);check(f.gateway.attempts.size()==1&&Handler.pending(old)==0,"stopped poll");check(TpmsWarningOverlayController.INSTANCE.calls==calls&&TpmsWarningOverlayController.INSTANCE.details.isEmpty(),"alert resurrected");check(StateStore.tpms().pressureKpa[0]==180,"RX state dropped");});
        test("restart rejects already-dequeued old generation callback",()->{F f=new F();f.start();Runnable old=f.tick();f.controller.stopPolling();f.controller.startPolling();old.run();Handler.advance(0);check(f.gateway.attempts.size()==2&&f.pending()==1,"old generation consumed new session");check(HandlerThread.starts==1,"worker replaced");});
        test("already-dequeued callback cannot consume newer later deadline",()->{F f=new F();f.start();f.frame(180);Runnable dequeued=Handler.take(f.tick());f.frame(240);dequeued.run();check(f.gateway.attempts.size()==1&&f.pending()==1&&f.next()==120000,"dequeued callback consumed replacement");});
        test("concurrent frame and manual requests coalesce to one worker callback",()->{F f=new F();f.start();Handler.advance(1000);List<Thread> threads=new ArrayList<>();for(int i=0;i<20;i++){Thread t=new Thread(()->{f.frame(240);f.controller.requestNow();});threads.add(t);t.start();}for(Thread t:threads)t.join();check(f.pending()==1&&f.next()==1000,"duplicate callback");Handler.advance(1000);check(f.gateway.attempts.size()==2&&f.pending()==1,"duplicate request");});
        test("blocked USB write leaves main loop responsive and stop never joins USB",()->{F f=new F();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);f.gateway.onPoll=()->{entered.countDown();await(release);};f.controller.startPolling();Runnable old=f.tick();Thread worker=new Thread(()->Handler.advance(0));worker.start();await(entered);AtomicBoolean ui=new AtomicBoolean();new Handler(Looper.getMainLooper()).post(()->ui.set(true));Handler.drainLooper(Looper.getMainLooper());long before=System.nanoTime();f.controller.stopPolling();long stopMs=(System.nanoTime()-before)/1000000;TpmsAlertController.get(f.app).clear();int calls=TpmsWarningOverlayController.INSTANCE.calls;f.frame(180);release.countDown();worker.join(2000);check(ui.get()&&stopMs<100&&!worker.isAlive(),"main/stop blocked by USB");check(Handler.pending(old)==0&&TpmsWarningOverlayController.INSTANCE.calls==calls,"inflight tick resurrected session");});
        test("restart during blocked old write remains serialized",()->{F f=new F();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger n=new AtomicInteger();f.gateway.onPoll=()->{if(n.getAndIncrement()==0){entered.countDown();await(release);}};f.controller.startPolling();Thread worker=new Thread(()->Handler.advance(0));worker.start();await(entered);f.controller.stopPolling();f.controller.startPolling();check(f.gateway.attempts.size()==1,"new session wrote before old completion");release.countDown();worker.join(2000);check(!worker.isAlive()&&f.gateway.attempts.size()==2&&f.gateway.maxActive==1&&f.pending()==1,"restart parallelized writes");check(HandlerThread.starts==1,"restart created worker");});
        test("frame received inside write cannot postpone fixed deadline",()->{F f=new F();f.controller.setPollingMode(TpmsPollingMode.FULL_DASHBOARD);f.gateway.onPoll=()->f.frame(240);f.start();Handler.advance(10000);check(f.gateway.attempts.equals(Arrays.asList(0L,5000L,10000L))&&f.pending()==1,"in-write frame shifted timer");});
        System.out.println("RESULT passed="+passed+" failed="+failed+" total="+(passed+failed));
        if(failed>0)System.exit(1);
    }
}
'''

for name,body in FILES.items():
    p=FIXTURES/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body)
prod=SOURCE/'app/app/src/main/java'
test=SOURCE/'app/app/src/test/java'
production=[prod/n for n in ('kia/app/tpms/TpmsController.java','kia/app/tpms/TpmsPollingMode.java','kia/app/tpms/TpmsAlertController.java','kia/app/tpms/TpmsAlertLifecycle.java','kia/app/tpms/TpmsWarningPolicy.java','kia/app/core/model/TpmsState.java')]
test_names=['kia.app.tpms.TpmsPollingModeTest','kia.app.tpms.TpmsControllerParsingTest','kia.app.tpms.TpmsAlertFreshnessTest','kia.app.core.model.TpmsStateFreshnessTest','kia.app.core.model.TpmsStateMonotonicTest']
test_files=[test/(n.replace('.','/')+'.java') for n in test_names]
junit=jar(args.junit,'modules-2/files-2.1/junit/junit/4.13.2/*/junit-4.13.2.jar')
hamcrest=jar(args.hamcrest,'modules-2/files-2.1/org.hamcrest/hamcrest-core/1.3/*/hamcrest-core-1.3.jar')
classpath=os.pathsep.join(map(str,[CLASSES,junit,hamcrest]))
hashes={str(p.relative_to(SOURCE)):hashlib.sha256(p.read_bytes()).hexdigest() for p in production}
compiled=subprocess.run([java_tool('javac'),'-encoding','UTF-8','-cp',classpath,'-d',str(CLASSES)]+[str(p) for p in FIXTURES.rglob('*.java')]+[str(p) for p in production+test_files],text=True,capture_output=True)
(BASE/'compile.txt').write_text(compiled.stdout+compiled.stderr)
if compiled.returncode:print(compiled.stdout+compiled.stderr);raise SystemExit(compiled.returncode)
results=[];runs=[]
for entry in [['org.junit.runner.JUnitCore']+test_names,['kia.app.tpms.TpmsSchedulerRegression']]:
    run=subprocess.run([java_tool('java'),'-cp',classpath]+entry,text=True,capture_output=True)
    output=run.stdout+run.stderr;results.append(output);runs.append({'entry':entry,'exit_code':run.returncode});print(output,end='')
(BASE/'results.txt').write_text('\n'.join(results))
receipt={'source':str(SOURCE),'source_hashes':hashes,'runs':runs,'clock_model':'Handler and Android elapsedRealtime share virtual elapsed time; model unit tests inject independent wall and elapsed clocks.','limitations':['Android loopers, UI/notification, StateStore storage and Gateway are deterministic boundary fixtures.','Actual controller, state and alert policy/lifecycle source are compiled and executed.','Blocked-write test exercises real concurrent Java threads at the Gateway boundary; actual USB driver and hardware are not executed.']}
(BASE/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n')
print('Artifacts:',BASE)
raise SystemExit(1 if any(r['exit_code'] for r in runs) else 0)
