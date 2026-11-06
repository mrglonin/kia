#!/usr/bin/env python3
"""Exercise production roof routing and overlay decisions without an Activity or QA policy.

Compiles the unchanged FrameRouter, vehicle model, roof policy, geometry and controller.
Android display/window/input/clock, native ACC and USB readiness are explicit fixtures.
No ADB, physical device, Gradle, network or changes to app settings are used.
"""
from pathlib import Path
import argparse
import hashlib
import json
import os
import shutil
import subprocess
import tempfile

FIXTURES = {'android/annotation/SuppressLint.java': 'package android.annotation; public @interface SuppressLint '
                                         '{String[] value();}',
 'android/os/Looper.java': 'package android.os; public class Looper {static final Thread '
                           'THREAD=Thread.currentThread();static final Looper MAIN=new Looper();public '
                           'static Looper getMainLooper(){return MAIN;}public static Looper '
                           'myLooper(){return Thread.currentThread()==THREAD?MAIN:null;}}',
 'android/os/Handler.java': 'package android.os; public class Handler {\n'
                            ' static java.util.Queue<Runnable> ready=new '
                            'java.util.concurrent.ConcurrentLinkedQueue<>();\n'
                            ' static java.util.List<Task> delayed=new java.util.ArrayList<>();\n'
                            ' static class Task {Runnable r;long at;Task(Runnable r,long '
                            'at){this.r=r;this.at=at;}}\n'
                            ' public Handler(Looper l){} public boolean post(Runnable '
                            'r){ready.add(r);return true;}\n'
                            ' public boolean postDelayed(Runnable r,long d){delayed.add(new '
                            'Task(r,SystemClock.now+d));return true;}\n'
                            ' public void removeCallbacks(Runnable '
                            'r){ready.remove(r);delayed.removeIf(t->t.r==r);}\n'
                            ' public void removeCallbacksAndMessages(Object '
                            'o){ready.clear();delayed.clear();}\n'
                            ' public static void drain(){Runnable '
                            'r;while((r=ready.poll())!=null)r.run();}\n'
                            ' public static void advance(long '
                            'now){SystemClock.now=now;java.util.List<Task> due=new '
                            'java.util.ArrayList<>();for(Task '
                            't:delayed)if(t.at<=now)due.add(t);delayed.removeAll(due);for(Task '
                            't:due)t.r.run();drain();}\n'
                            '}',
 'android/os/Build.java': 'package android.os; public class Build {public static class VERSION {public '
                          'static int SDK_INT=33;}}',
 'android/os/SystemClock.java': 'package android.os; public class SystemClock {public static long '
                                'now=100;public static long elapsedRealtime(){return now;}}',
 'android/content/BroadcastReceiver.java': 'package android.content;public abstract class '
                                           'BroadcastReceiver {public abstract void onReceive(Context '
                                           'c,Intent i);}',
 'android/content/Intent.java': 'package android.content;public class Intent {public static final '
                                'String '
                                'ACTION_SCREEN_OFF="screenOff",ACTION_SCREEN_ON="screenOn",ACTION_CLOSE_SYSTEM_DIALOGS="closeDialogs";final '
                                'java.util.Map<String,String> extras=new java.util.HashMap<>();final '
                                'String action;public Intent(String a){action=a;}public String '
                                'getAction(){return action;}public Intent putExtra(String key,String '
                                'value){extras.put(key,value);return this;}public String '
                                'getStringExtra(String key){return extras.get(key);}}',
 'android/content/IntentFilter.java': 'package android.content;public class IntentFilter {final '
                                      'java.util.Set<String> actions=new java.util.HashSet<>();public '
                                      'IntentFilter(String a){actions.add(a);}public void '
                                      'addAction(String a){actions.add(a);}}',
 'android/content/Context.java': 'package android.content; public class Context {public static final '
                                 'String WINDOW_SERVICE="window",POWER_SERVICE="power";public static '
                                 'final int MODE_PRIVATE=0;static final '
                                 'java.util.Map<String,FixturePreferences> prefs=new '
                                 'java.util.HashMap<>();public static void '
                                 'clearPreferences(){prefs.clear();}public SharedPreferences '
                                 'getSharedPreferences(String name,int mode){return '
                                 'prefs.computeIfAbsent(name,k->new FixturePreferences());}public '
                                 'static final int RECEIVER_NOT_EXPORTED=4,RECEIVER_EXPORTED=2;final '
                                 'java.util.Map<BroadcastReceiver,IntentFilter> receivers=new '
                                 'java.util.LinkedHashMap<>();public android.content.res.Resources '
                                 'getResources(){return new android.content.res.Resources();}public '
                                 'Context getApplicationContext(){return this;}public Object '
                                 'getSystemService(String s){return '
                                 'POWER_SERVICE.equals(s)?android.os.PowerManager.INSTANCE:android.view.WindowManager.INSTANCE;}public '
                                 '<T>T getSystemService(Class<T>c){return null;}public Context '
                                 'createDisplayContext(android.view.Display d){return this;}public '
                                 'Context createWindowContext(int t,Object o){return this;}public void '
                                 'registerReceiver(BroadcastReceiver r,IntentFilter '
                                 'f){receivers.put(r,f);}public void registerReceiver(BroadcastReceiver '
                                 'r,IntentFilter f,int flags){receivers.put(r,f);}public void '
                                 'unregisterReceiver(BroadcastReceiver r){receivers.remove(r);}public '
                                 'void sendBroadcast(Intent i){for(var entry:new '
                                 'java.util.ArrayList<>(receivers.entrySet()))if(entry.getValue().actions.contains(i.getAction()))entry.getKey().onReceive(this,i);}}',
 'android/graphics/PixelFormat.java': 'package android.graphics;public class PixelFormat {public static '
                                      'final int TRANSLUCENT=-3;}',
 'android/hardware/input/InputManager.java': 'package android.hardware.input;public class InputManager '
                                             '{public float getMaximumObscuringOpacityForTouch(){return '
                                             '.8f;}}',
 'android/provider/Settings.java': 'package android.provider;public class Settings {public static '
                                   'boolean canDrawOverlays(android.content.Context c){return true;}}',
 'android/view/Display.java': 'package android.view;public class Display {public void '
                              'getRealMetrics(android.util.DisplayMetrics '
                              'm){m.widthPixels=WindowManager.screenWidth;}}',
 'android/view/Gravity.java': 'package android.view;public class Gravity {public static final int '
                              'TOP=48,START=3,CENTER=17;}',
 'android/view/KeyEvent.java': 'package android.view;public class KeyEvent {public static final int '
                               'ACTION_DOWN=0;}',
 'android/view/View.java': 'package android.view;public class View {public static final int '
                           'SYSTEM_UI_FLAG_LAYOUT_STABLE=1,SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN=2,SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION=4;public '
                           'boolean attached;public void setSystemUiVisibility(int flags){}public '
                           'boolean isAttachedToWindow(){return attached;}}',
 'android/view/ViewGroup.java': 'package android.view;public class ViewGroup {public static class '
                                'LayoutParams {public static final int MATCH_PARENT=-1;}}',
 'android/view/WindowManager.java': 'package android.view;public class WindowManager {\n'
                                    ' public static final WindowManager INSTANCE=new '
                                    'WindowManager();public static int screenWidth=1280;\n'
                                    ' public int adds,removes;public View current;public LayoutParams '
                                    'last;\n'
                                    ' public Display getDefaultDisplay(){return new Display();}public '
                                    'WindowMetrics getCurrentWindowMetrics(){return new '
                                    'WindowMetrics();}\n'
                                    ' public void addView(View v,LayoutParams '
                                    'p){adds++;current=v;last=p;v.attached=true;}\n'
                                    ' public void removeViewImmediate(View '
                                    'v){removes++;v.attached=false;if(current==v)current=null;}\n'
                                    ' public static class LayoutParams {\n'
                                    ' public static final int '
                                    'FLAG_LAYOUT_IN_SCREEN=1,FLAG_LAYOUT_NO_LIMITS=2,FLAG_NOT_TOUCHABLE=4,FLAG_NOT_FOCUSABLE=8,FLAG_NOT_TOUCH_MODAL=16,TYPE_APPLICATION_OVERLAY=2038,TYPE_PHONE=2002,LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES=1;\n'
                                    ' public int '
                                    'gravity,layoutInDisplayCutoutMode,width,height,flags;public float '
                                    'alpha=1f;\n'
                                    ' public LayoutParams(int w,int h,int t,int f,int '
                                    'p){width=w;height=h;flags=f;}public void setTitle(String s){}\n'
                                    ' }}',
 'kia/app/core/AppIds.java': 'package kia.app.core;public class AppIds {public static final String '
                             'ACTION_STATE_CHANGED="changed";}',
 'kia/app/core/AppLog.java': 'package kia.app.core;public class AppLog {public static final '
                             'java.util.List<String> lines=new java.util.ArrayList<>();public static '
                             'void line(android.content.Context c,String s){lines.add(s);}}',
 'kia/app/core/StateStore.java': 'package kia.app.core;import kia.app.core.model.*;public class '
                                 'StateStore {static VehicleState vehicle=VehicleState.empty();static '
                                 'AdapterState adapter=AdapterState.empty();static RctaState '
                                 'rcta=RctaState.empty();static TpmsState tpms=new TpmsState();public '
                                 'static VehicleState vehicle(){return vehicle;}public static '
                                 'AdapterState adapter(){return adapter;}public static RctaState '
                                 'rcta(){return rcta;}public static TpmsState tpms(){return '
                                 'tpms;}public static void setVehicle(android.content.Context '
                                 'c,VehicleState v){vehicle=v;new '
                                 'android.os.Handler(android.os.Looper.getMainLooper()).post(()->c.sendBroadcast(new '
                                 'android.content.Intent(AppIds.ACTION_STATE_CHANGED)));}public static '
                                 'void setAdapter(android.content.Context c,AdapterState '
                                 'v){adapter=v;}}',
 'kia/app/core/model/TpmsState.java': 'package kia.app.core.model;public class TpmsState {}',
 'kia/app/core/settings/AppSettings.java': r'''package kia.app.core.settings;
public class AppSettings {
 public static boolean masterEnabled=true,parkingEnabled=true;
 public static boolean sunroofOverlayEnabled(android.content.Context c){return masterEnabled;}
 public static boolean sunroofParkingWarningEnabled(android.content.Context c){return parkingEnabled;}
 public static boolean sunroofSoundEnabled(android.content.Context c){return false;}
 public static boolean sunroofTransitionSoundEnabled(android.content.Context c){return false;}
 public static boolean rctaOverlayEnabled(android.content.Context c){return true;}
}''',
 'kia/app/protocol/adapter/AdapterGateway.java': 'package kia.app.protocol.adapter;public class '
                                                 'AdapterGateway {static AdapterGateway instance=new '
                                                 'AdapterGateway();public static boolean '
                                                 'connected=true;public static long epoch=1;public '
                                                 'static AdapterGateway get(android.content.Context '
                                                 'c){return instance;}public boolean usbReady(){return '
                                                 'connected;}public long connectionEpoch(){return '
                                                 'epoch;}}',
 'kia/app/protocol/adapter/AdapterProtocol.java': 'package kia.app.protocol.adapter;public class '
                                                  'AdapterProtocol {public static final int '
                                                  'CMD_VERSION=0x56,CMD_HEALTH=0x79,CMD_AMP=0x30,CMD_RAW_CAN=0x76,CMD_RAW_CAN_TX=0x78,CMD_SETTINGS=0x60,CMD_SOURCE_STATUS=0x7a,CMD_RCTA=0x75,CMD_FIRMWARE=0x55,CMD_VEHICLE_SNAPSHOT=0x77,CMD_TPMS=0x51,CMD_TPMS_LEGACY=0x7b;public '
                                                  'static String hex(byte[] f){return "frame";}}',
 'kia/app/tpms/TpmsAlertController.java': 'package kia.app.tpms;public class TpmsAlertController '
                                          '{public static int severity;public static boolean '
                                          'hasWarnings(android.content.Context '
                                          'c,kia.app.core.model.TpmsState s){return severity>0;}public '
                                          'static boolean hasCriticalWarnings(android.content.Context '
                                          'c,kia.app.core.model.TpmsState s){return severity>=2;}}',
 'kia/app/sunroof/SunroofOverlayView.java': 'package kia.app.sunroof;public class SunroofOverlayView '
                                            'extends android.view.View {\n'
                                            ' public static final long '
                                            'TRANSIENT_DURATION_MS=3400;public float '
                                            'compactWidth;public Runnable dismiss;\n'
                                            ' public SunroofAlertPolicy.Kind kind;public '
                                            'SunroofOverlayView(android.content.Context c){}\n'
                                            ' public void setCompactCarWidthPx(float '
                                            'w){compactWidth=w;}\n'
                                            ' public void present(SunroofAlertPolicy.Kind k,Runnable '
                                            'r){kind=k;dismiss=r;}\n'
                                            ' public void stopAnimations(){}\n'
                                            '}',
 'kia/app/sunroof/SunroofSoundPlayer.java': r'''package kia.app.sunroof;
/** Audio boundary only: records active playback, including real interruptions. */
public class SunroofSoundPlayer {
 public static int warningPlays,transitionPlays,actualStops;
 public static boolean playing;
 boolean enabled;
 public SunroofSoundPlayer(android.content.Context c){}
 public void stop(){if(playing){actualStops++;playing=false;}}
 public void setEnabled(boolean b){enabled=b;if(!b)stop();}
 public void play(SunroofAlertPolicy.Kind kind){
  stop();
  if(!enabled||kind==null||kind==SunroofAlertPolicy.Kind.NONE)return;
  if(kind==SunroofAlertPolicy.Kind.PARKING_WARNING)warningPlays++;else transitionPlays++;
  playing=true;
 }
 /** The media completion callback releases audio without changing controller state. */
 public static void complete(){playing=false;}
}''',
 'kia/app/sunroof/TeyesSunroofObserver.java': 'package kia.app.sunroof;public class '
                                              'TeyesSunroofObserver {\n'
                                              ' public interface Listener {void onAcc(boolean '
                                              'known,boolean on,long at,String source);void '
                                              'onHardwareAction(int key,int action);void '
                                              'onSleep(boolean sleep);}\n'
                                              ' public static Listener listener;public '
                                              'TeyesSunroofObserver(android.content.Context c,Listener '
                                              'l){listener=l;}\n'
                                              ' public void start(){}public void stop(){}public String '
                                              'statusSummary(){return "fixture native ACC boundary";}\n'
                                              '}',
 'kia/app/sunroof/ControllerBackgroundProof.java': r'''package kia.app.sunroof;
import kia.app.protocol.adapter.FrameRouter;import kia.app.core.*;import kia.app.core.model.*;
import android.os.*;import android.view.WindowManager;import kia.app.tpms.*;
public class ControllerBackgroundProof {
 static int checks;static android.content.Context context=new android.content.Context();
 static SunroofOverlayController roof=SunroofOverlayController.get(context);
 static WindowManager wm=WindowManager.INSTANCE;static FrameRouter router;
 static void check(boolean b,String message){checks++;if(!b)throw new AssertionError(message);}
 static void frame(boolean open,long at)throws Exception{
  SystemClock.now=at;Thread worker=new Thread(()->router.onFrame(new byte[]{0,0,0,7,0x76,(byte)(open?1:0),0},1,at),"fixture-usb-reader");
  worker.start();worker.join();Handler.drain();
 }
 static void reset()throws Exception{
  roof.stop();android.content.Context.clearPreferences();android.os.PowerManager.interactive=true;kia.app.protocol.adapter.AdapterGateway.connected=true;kia.app.protocol.adapter.AdapterGateway.epoch=1;SunroofSoundPlayer.warningPlays=0;SunroofSoundPlayer.transitionPlays=0;SunroofSoundPlayer.actualStops=0;SunroofSoundPlayer.playing=false;kia.app.core.settings.AppSettings.masterEnabled=true;kia.app.core.settings.AppSettings.parkingEnabled=true;StateStore.setVehicle(context,VehicleState.empty());Handler.drain();
  TpmsAlertController.severity=0;TpmsWarningOverlayController.visible=false;wm.adds=0;wm.removes=0;
  SystemClock.now=100;rootClock();router=new FrameRouter(context);roof.start();frame(false,100);
 }
 static void rootClock(){android.content.res.Resources.metrics.density=1;android.content.res.Resources.metrics.densityDpi=160;}
 static SunroofOverlayView view(){return (SunroofOverlayView)wm.current;}
 static void acc(boolean on,long at){
  SystemClock.now=at;TeyesSunroofObserver.listener.onAcc(true,on,at,"nativefixture");Handler.drain();
 }
 static void screen(boolean interactive){
  android.os.PowerManager.interactive=interactive;
  context.sendBroadcast(new android.content.Intent(interactive
   ?android.content.Intent.ACTION_SCREEN_ON:android.content.Intent.ACTION_SCREEN_OFF));
  Handler.drain();
 }
 static void startWarning()throws Exception{
  reset();acc(true,150);frame(true,200);acc(false,300);
  check(view()!=null&&view().kind==SunroofAlertPolicy.Kind.PARKING_WARNING,"warning setup did not use real policy");
  check(SunroofSoundPlayer.warningPlays==1&&SunroofSoundPlayer.playing,"real ACC off did not start exactly one active cue");
  check(SunroofSoundPlayer.actualStops==0,"new warning was interrupted during setup");
 }
 static void hiddenWarning()throws Exception{
  startWarning();SystemClock.now=351;TeyesSunroofObserver.listener.onSleep(true);
  check(view()==null,"sleep must hide warning window");
  check(SunroofSoundPlayer.playing&&SunroofSoundPlayer.actualStops==0,"sleep 51 ms after ACC off interrupted active warning audio");
 }
 static void checkHiddenStop(String cause){
  check(view()==null&&!SunroofSoundPlayer.playing,"hidden warning was not cancelled by "+cause);
  check(SunroofSoundPlayer.actualStops==1,"hidden warning did not stop exactly once for "+cause);
 }
 static void sleepAudioRegressions()throws Exception{
  // Reproduce the physical ordering: ACC off, then native sleep 51 ms into the cue.
  hiddenWarning();screen(false);
  check(SunroofSoundPlayer.playing&&SunroofSoundPlayer.actualStops==0,"screen-off broadcast cut the cue after native sleep");
  roof.apply();frame(true,352);acc(false,353);Handler.advance(1_300);
  check(view()==null&&SunroofSoundPlayer.playing&&SunroofSoundPlayer.warningPlays==1,"hidden refresh or duplicate source restarted/cancelled audio");
  screen(true);TeyesSunroofObserver.listener.onSleep(false);
  check(view()!=null&&SunroofSoundPlayer.playing&&SunroofSoundPlayer.warningPlays==1&&SunroofSoundPlayer.actualStops==0,"wake reattachment interrupted or replayed active cue");
  SunroofSoundPlayer.complete();roof.apply();frame(true,1_301);Handler.advance(2_400);
  check(!SunroofSoundPlayer.playing&&SunroofSoundPlayer.warningPlays==1,"natural completion followed by visible refresh replayed cue");

  // Sleep and screen-off may arrive before the true ACC sample; they never imply ACC off.
  reset();acc(true,150);frame(true,200);SystemClock.now=300;TeyesSunroofObserver.listener.onSleep(true);screen(false);
  check(view()==null&&SunroofSoundPlayer.warningPlays==0,"sleep alone fabricated a parking warning");
  acc(false,351);
  check(view()==null&&SunroofSoundPlayer.playing&&SunroofSoundPlayer.warningPlays==1,"sleep-before-ACC-off lost mandatory cue or opened a window");
  SunroofSoundPlayer.complete();frame(true,352);acc(false,353);Handler.advance(1_400);screen(true);TeyesSunroofObserver.listener.onSleep(false);roof.apply();
  check(view()!=null&&!SunroofSoundPlayer.playing&&SunroofSoundPlayer.warningPlays==1,"completed hidden cue replayed on wake or duplicate ACC");

  hiddenWarning();frame(false,400);checkHiddenStop("real roof close");
  check(!SunroofStateMemory.snapshot(context).open,"real close failed to update persisted roof position");
  roof.apply();check(SunroofSoundPlayer.warningPlays==1,"closed hidden roof replayed warning");

  hiddenWarning();TeyesSunroofObserver.listener.onHardwareAction(66,android.view.KeyEvent.ACTION_DOWN);checkHiddenStop("hardware acknowledgement");
  check(SunroofStateMemory.snapshot(context).acknowledged,"hidden acknowledgement was not persisted");
  acc(false,400);roof.apply();check(SunroofSoundPlayer.warningPlays==1,"acknowledged hidden episode replayed cue");

  hiddenWarning();kia.app.core.settings.AppSettings.masterEnabled=false;roof.apply();checkHiddenStop("master disable");
  hiddenWarning();kia.app.core.settings.AppSettings.parkingEnabled=false;roof.apply();checkHiddenStop("parking-warning disable");

  hiddenWarning();acc(true,400);checkHiddenStop("ACC on");
  acc(false,500);
  check(view()==null&&SunroofSoundPlayer.playing&&SunroofSoundPlayer.warningPlays==2,"new actual ACC cycle failed to rearm hidden warning");

  hiddenWarning();TpmsAlertController.severity=2;TpmsWarningOverlayController.visible=true;roof.apply();checkHiddenStop("critical TPMS");
  TpmsWarningOverlayController.visible=false;roof.apply();
  check(!SunroofSoundPlayer.playing&&SunroofSoundPlayer.warningPlays==1,"critical hazard recovery replayed consumed cue");

  // A hazard before the first attempt defers the cue, unlike interrupting a consumed cue.
  reset();acc(true,150);frame(true,200);TeyesSunroofObserver.listener.onSleep(true);
  TpmsAlertController.severity=2;TpmsWarningOverlayController.visible=true;acc(false,300);
  check(view()==null&&SunroofSoundPlayer.warningPlays==0&&!SunroofSoundPlayer.playing,"preexisting critical hazard did not defer hidden cue");
  TpmsWarningOverlayController.visible=false;roof.apply();
  check(view()==null&&SunroofSoundPlayer.warningPlays==1&&SunroofSoundPlayer.playing,"deferred first warning was lost while asleep");

  hiddenWarning();roof.stop();checkHiddenStop("service stop");
  Handler.advance(10_000);roof.apply();check(SunroofSoundPlayer.warningPlays==1&&!SunroofSoundPlayer.playing,"stopped service replayed hidden warning");
 }
 public static void main(String[] args)throws Exception{
  reset();frame(true,200);
  check(wm.current!=null,"background production transition did not add window");
  check(view().kind==SunroofAlertPolicy.Kind.OPENING,"wrong background transition");
  check("0x76".equals(StateStore.vehicle().sunroofSource),"test bypassed production roof router");
  check(StateStore.vehicle().sunroofConnectionEpoch==1,"router did not retain USB session");
  int logs=AppLog.lines.size();roof.apply();roof.apply();frame(true,201);
  check(AppLog.lines.size()==logs,"duplicate frame/tick flooded decision log");
  check(roof.statusSummary().contains("running=true")&&roof.statusSummary().contains("master=true")
   &&roof.statusSummary().contains("permission=true")&&roof.statusSummary().contains("window=true")
   &&roof.statusSummary().contains("usbEpoch=1")&&roof.statusSummary().contains("roofEpoch=1"),"missing production diagnostics");
  check(wm.last.alpha==1f,"transient car is translucent");
  check(wm.last.gravity==android.view.Gravity.CENTER,"compact car is not centered");
  check((wm.last.flags&WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)!=0,"transient steals focus");
  check((wm.last.flags&WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)!=0,"outside-window touches cannot pass");
  check((wm.last.flags&WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)==0,"transient cannot acknowledge car tap");
  check(wm.last.width==248&&wm.last.height==371,"compact window does not bound 19.32 percent car");
  check(Math.abs(view().compactWidth-247.296f)<.001f,"car pixel width lost exact relative 20 percent growth");
  view().dismiss.run();check(wm.current==null,"car tap did not dismiss compact window");
  reset();TpmsAlertController.severity=1;TpmsWarningOverlayController.visible=true;frame(true,200);
  check(wm.current!=null,"minor TPMS warning blocked roof transition");
  reset();TpmsAlertController.severity=2;TpmsWarningOverlayController.visible=false;frame(true,200);
  check(wm.current!=null,"acknowledged critical TPMS warning blocked roof transition");
  reset();TpmsAlertController.severity=2;TpmsWarningOverlayController.visible=true;frame(true,200);
  check(wm.current==null,"visible critical warning did not take priority");
  check(roof.statusSummary().contains("critical_defer"),"critical defer is not diagnosable");
  SystemClock.now=500;TpmsWarningOverlayController.visible=false;roof.apply();
  check(wm.current!=null&&view().kind==SunroofAlertPolicy.Kind.OPENING,"deferred transition was permanently lost");
  reset();TpmsAlertController.severity=2;TpmsWarningOverlayController.visible=true;frame(true,200);
  SystemClock.now=10199;TpmsWarningOverlayController.visible=false;roof.apply();
  check(wm.current!=null,"transition did not recover just before TTL");
  Handler.advance(10200);check(wm.current!=null,"TTL cut short an already recovered animation");
  reset();TpmsAlertController.severity=2;TpmsWarningOverlayController.visible=true;frame(true,200);
  Handler.advance(10200);TpmsWarningOverlayController.visible=false;roof.apply();
  check(wm.current==null,"expired deferred transition replayed after TTL");
  reset();frame(true,200);SystemClock.now=300;TeyesSunroofObserver.listener.onAcc(true,false,300,"nativefixture");
  check(view()!=null&&view().kind==SunroofAlertPolicy.Kind.PARKING_WARNING,"real policy did not receive native ACC off");
  check(wm.last.alpha==1f&&wm.last.width==-1&&wm.last.height==-1,"warning must remain opaque fullscreen");
  check((wm.last.flags&WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)==0,"warning cannot receive hardware keys");
  check(view().compactWidth==0f,"fullscreen warning uses compact canvas coordinates");
  Handler.advance(20000);check(view()!=null,"parking warning incorrectly expired with transition TTL");
  view().dismiss.run();check(wm.current==null,"warning acknowledgement did not hide window");
  check(Math.abs(SunroofOverlayGeometry.carWidthPx(1000)-193.2f)<.001f,"native 1000 pixel width incorrect");
  check(Math.abs(SunroofOverlayGeometry.SCREEN_WIDTH_FRACTION-(.161f*1.20f))<.000001f,"size is not exactly 20 percent larger");
  for(int dpi:new int[]{120,160,213,240,320,480}){
   reset();android.content.res.Resources.metrics.densityDpi=dpi;android.content.res.Resources.metrics.density=dpi/160f;
   frame(true,200);check(wm.last.width==248&&wm.last.height==371,"DPI changed pixel geometry: "+dpi);
  }
  android.os.Build.VERSION.SDK_INT=29;reset();frame(true,200);
  check(wm.last.width==248&&wm.last.height==371,"API29 pixel display fallback changed geometry");
  reset();roof.preview(true);check(SunroofStateMemory.snapshot(context).known&&!SunroofStateMemory.snapshot(context).open,"opening preview persisted a fake roof position");
  roof.previewParkingWarning();check(SunroofStateMemory.snapshot(context).known&&!SunroofStateMemory.snapshot(context).open,"warning preview persisted a fake roof position");
  roof.dismissCurrent();check(!SunroofStateMemory.snapshot(context).open,"preview acknowledgement changed real position");
  SunroofStateMemory.record(context,true,"qa");check(!SunroofStateMemory.snapshot(context).open,"QA source was persisted as real roof state");
  reset();frame(true,200);check(SunroofStateMemory.snapshot(context).known&&SunroofStateMemory.snapshot(context).open,"real roof packet was not remembered");
  check(SunroofSoundPlayer.transitionPlays==0,"transition mute ignored");
  TeyesSunroofObserver.listener.onAcc(true,true,201,"nativefixture");
  kia.app.protocol.adapter.AdapterGateway.connected=false;StateStore.setVehicle(context,StateStore.vehicle().withoutSunroof());Handler.drain();
  SystemClock.now=900_000;TeyesSunroofObserver.listener.onAcc(true,false,900_000,"nativefixture");
  check(view()!=null&&view().kind==SunroofAlertPolicy.Kind.PARKING_WARNING,"sparse remembered open did not warn after long disconnect");
  check(!StateStore.vehicle().sunroofKnown,"cached position was restored into fresh CAN state");
  check(roof.statusSummary().contains("последнее сохранённое"),"cached source not identified in status");
  check(SunroofSoundPlayer.warningPlays>0,"warning obeyed removed legacy mute flag");
  SunroofStateMemory.Snapshot saved=SunroofStateMemory.snapshot(context);long savedAt=saved.recordedAt;
  check(savedAt>0&&"0x76".equals(saved.source),"real cache provenance missing");
  context.sendBroadcast(new android.content.Intent(android.content.Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason","dream"));
  check(view()!=null&&!SunroofStateMemory.snapshot(context).acknowledged,"automatic dream dialog falsely acknowledged roof warning");
  android.os.PowerManager.interactive=false;context.sendBroadcast(new android.content.Intent(android.content.Intent.ACTION_SCREEN_OFF));
  int beforeOffSound=SunroofSoundPlayer.warningPlays;Handler.advance(901_000);
  check(view()==null&&SunroofSoundPlayer.warningPlays==beforeOffSound,"off-screen refresh recreated warning or replayed audio");
  check(!SunroofStateMemory.snapshot(context).acknowledged,"automatic screen off persisted an acknowledgement");
  android.os.PowerManager.interactive=true;context.sendBroadcast(new android.content.Intent(android.content.Intent.ACTION_SCREEN_ON));
  check(view()!=null,"screen wake with actual ACC off did not restore unacknowledged warning");
  TeyesSunroofObserver.listener.onSleep(true);check(view()==null,"native sleep did not hide warning");
  check(!SunroofStateMemory.snapshot(context).acknowledged,"automatic native sleep persisted an acknowledgement");
  TeyesSunroofObserver.listener.onSleep(false);check(view()!=null,"native wake lost remembered roof warning");
  roof.stop();StateStore.setVehicle(context,VehicleState.empty());Handler.drain();
  java.lang.reflect.Field singleton=SunroofOverlayController.class.getDeclaredField("instance");singleton.setAccessible(true);singleton.set(null,null);
  context=new android.content.Context();roof=SunroofOverlayController.get(context);roof.start();
  check(SunroofStateMemory.snapshot(context).recordedAt==savedAt,"controller/context recreation lost saved roof state");
  TeyesSunroofObserver.listener.onAcc(true,false,901_001,"nativefixture");
  check(view()!=null&&view().kind==SunroofAlertPolicy.Kind.PARKING_WARNING,"restart without USB or roof packet did not warn from saved open");
  view().dismiss.run();check(view()==null&&SunroofStateMemory.snapshot(context).acknowledged,"real user acknowledgement was not persisted");
  check(SunroofStateMemory.snapshot(context).open,"acknowledgement changed actual roof position");
  roof.stop();StateStore.setVehicle(context,VehicleState.empty());Handler.drain();singleton.set(null,null);
  context=new android.content.Context();roof=SunroofOverlayController.get(context);roof.start();TeyesSunroofObserver.listener.onAcc(true,false,901_002,"nativefixture");
  check(view()==null,"restart rearmed the same acknowledged ACC-off episode");
  TeyesSunroofObserver.listener.onAcc(true,true,901_003,"nativefixture");
  check(!SunroofStateMemory.snapshot(context).acknowledged,"known ACC on failed to rearm memory");
  TeyesSunroofObserver.listener.onAcc(true,false,901_004,"nativefixture");check(view()!=null,"next actual ACC cycle failed to warn");
  SunroofStateMemory.record(context,false,"0x76");roof.apply();check(view()==null,"saved real close failed to clear warning while USB absent");
  check(!SunroofStateMemory.snapshot(context).open,"saved close was not retained");
  roof.stop();StateStore.setVehicle(context,VehicleState.empty());Handler.drain();singleton.set(null,null);
  context=new android.content.Context();roof=SunroofOverlayController.get(context);roof.start();TeyesSunroofObserver.listener.onAcc(true,false,901_005,"nativefixture");
  check(view()==null,"closed roof replayed an old open warning after wake/recreation");
  SunroofStateMemory.record(context,true,"0x76");roof.apply();check(view()!=null,"real close to open failed to rearm within ACC-off cycle");
  check(!SunroofStateMemory.snapshot(context).acknowledged,"new real opening retained old acknowledgement");
  context.sendBroadcast(new android.content.Intent(android.content.Intent.ACTION_CLOSE_SYSTEM_DIALOGS));
  check(view()!=null&&!SunroofStateMemory.snapshot(context).acknowledged,"missing dialog reason falsely acknowledged roof warning");
  for(String reason:new String[]{"homekey","recentapps"}){
   context.sendBroadcast(new android.content.Intent(android.content.Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason",reason));
   check(view()==null&&SunroofStateMemory.snapshot(context).acknowledged,"explicit "+reason+" failed to persist acknowledgement");
   check(SunroofStateMemory.snapshot(context).open,"explicit "+reason+" changed actual roof position");
   TeyesSunroofObserver.listener.onAcc(true,true,901_006,"nativefixture");
   TeyesSunroofObserver.listener.onAcc(true,false,901_007,"nativefixture");
   check(view()!=null,"next ACC cycle failed after "+reason+" acknowledgement");
  }
  sleepAudioRegressions();
  roof.stop();System.out.println("Sunroof controller production regression passed: "+checks+" assertions");
 }
}
''',
 'kia/app/amp/AmpController.java': 'package kia.app.amp;public class AmpController {static '
                                   'AmpController i=new AmpController();public static AmpController '
                                   'get(android.content.Context c){return i;}public void '
                                   'handleFrame(byte[] f){}}',
 'kia/app/media/domain/MediaFeature.java': 'package kia.app.media.domain;public class MediaFeature '
                                           '{static MediaFeature i=new MediaFeature();public static '
                                           'MediaFeature get(android.content.Context c){return '
                                           'i;}public void handleRealMediaStatus(byte[] f){}}',
 'kia/app/rcta/RctaController.java': 'package kia.app.rcta;public class RctaController {static '
                                     'RctaController i=new RctaController();public static '
                                     'RctaController get(android.content.Context c){return i;}public '
                                     'void handleFrame(byte[] f){}}',
 'kia/app/update/FirmwareUpdateController.java': 'package kia.app.update;public class '
                                                 'FirmwareUpdateController {static '
                                                 'FirmwareUpdateController i=new '
                                                 'FirmwareUpdateController();public static '
                                                 'FirmwareUpdateController get(android.content.Context '
                                                 'c){return i;}public void handleAck(byte[] f){}}',
 'kia/app/tpms/TpmsController.java': 'package kia.app.tpms;public class TpmsController {static '
                                     'TpmsController i=new TpmsController();public static '
                                     'TpmsController get(android.content.Context c){return i;}public '
                                     'boolean handleAdapterFrame(byte[] f){return false;}}',
 'kia/app/tpms/TeyesTpmsWidgetForwarder.java': 'package kia.app.tpms;public class '
                                               'TeyesTpmsWidgetForwarder {static '
                                               'TeyesTpmsWidgetForwarder i=new '
                                               'TeyesTpmsWidgetForwarder();public static '
                                               'TeyesTpmsWidgetForwarder get(android.content.Context '
                                               'c){return i;}public void onUsbFrame(byte[] f,long '
                                               'e,long t){}}',
 'android/graphics/Rect.java': 'package android.graphics;public class Rect {public int width(){return '
                               'android.view.WindowManager.screenWidth;}}',
 'android/util/DisplayMetrics.java': 'package android.util;public class DisplayMetrics {public int '
                                     'widthPixels=1280;public float density=1f;public int '
                                     'densityDpi=160;public void setTo(DisplayMetrics '
                                     'm){widthPixels=m.widthPixels;density=m.density;densityDpi=m.densityDpi;}}',
 'android/content/res/Resources.java': 'package android.content.res;public class Resources {public '
                                       'static android.util.DisplayMetrics metrics=new '
                                       'android.util.DisplayMetrics();public '
                                       'android.util.DisplayMetrics getDisplayMetrics(){return '
                                       'metrics;}}',
 'android/view/WindowMetrics.java': 'package android.view;public class WindowMetrics {public '
                                    'android.graphics.Rect getBounds(){return new '
                                    'android.graphics.Rect();}}',
 'kia/app/tpms/TpmsWarningOverlayController.java': 'package kia.app.tpms;public class '
                                                   'TpmsWarningOverlayController {static final '
                                                   'TpmsWarningOverlayController INSTANCE=new '
                                                   'TpmsWarningOverlayController();public static '
                                                   'boolean visible;public static '
                                                   'TpmsWarningOverlayController '
                                                   'get(android.content.Context c){return '
                                                   'INSTANCE;}public boolean isShowing(){return '
                                                   'visible;}}',
 'android/content/SharedPreferences.java': 'package android.content;public interface SharedPreferences '
                                           '{java.util.Map<String,?> getAll();Editor edit();interface '
                                           'Editor {Editor putBoolean(String k,boolean v);Editor '
                                           'putString(String k,String v);Editor putLong(String k,long '
                                           'v);boolean commit();}}',
 'android/content/FixturePreferences.java': 'package android.content;public class FixturePreferences '
                                            'implements SharedPreferences {final '
                                            'java.util.Map<String,Object> values=new '
                                            'java.util.HashMap<>();public '
                                            'java.util.Map<String,?>getAll(){return new '
                                            'java.util.HashMap<>(values);}public Editor edit(){return '
                                            'new Change();}final class Change implements Editor {final '
                                            'java.util.Map<String,Object> pending=new '
                                            'java.util.HashMap<>();public Editor putBoolean(String '
                                            'k,boolean v){pending.put(k,v);return this;}public Editor '
                                            'putString(String k,String v){pending.put(k,v);return '
                                            'this;}public Editor putLong(String k,long '
                                            'v){pending.put(k,v);return this;}public boolean '
                                            'commit(){values.putAll(pending);return true;}}}',
 'android/os/PowerManager.java': 'package android.os;public class PowerManager {public static final '
                                 'PowerManager INSTANCE=new PowerManager();public static boolean '
                                 'interactive=true;public boolean isInteractive(){return interactive;}}'}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source',type=Path,default=Path(__file__).resolve().parents[1])
    parser.add_argument('--output',type=Path)
    args=parser.parse_args()
    root=args.source.expanduser().resolve()
    out=args.output.expanduser().resolve() if args.output else Path(tempfile.mkdtemp(prefix='kia-sunroof-controller-'))
    fix=out/'fixtures';classes=out/'classes';classes.mkdir(parents=True,exist_ok=True)
    paths=[]
    for rel,src in FIXTURES.items():
        p=fix/rel;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(src);paths.append(p)
    java_home=Path(os.environ['JAVA_HOME']) if os.environ.get('JAVA_HOME') else None
    commands={name:str(java_home/'bin'/name) if java_home else shutil.which(name) for name in ('java','javac')}
    if not all(commands.values()):parser.error('JDK required: set JAVA_HOME or put java/javac in PATH')
    prod=[root/'app/app/src/main/java/kia/app'/p for p in [
        'sunroof/SunroofOverlayController.java','sunroof/SunroofAlertPolicy.java',
        'sunroof/SunroofOverlayGeometry.java','sunroof/SunroofStateMemory.java','protocol/adapter/FrameRouter.java',
        'core/model/VehicleState.java','core/model/AdapterState.java','core/model/RctaState.java']]
    r=subprocess.run([commands['javac'],'-encoding','UTF-8','-d',str(classes),*map(str,paths+prod)],capture_output=True,text=True)
    (out/'compile.log').write_text(r.stdout+r.stderr)
    if r.returncode:raise SystemExit(r.stderr)
    r=subprocess.run([commands['java'],'-cp',str(classes),'kia.app.sunroof.ControllerBackgroundProof'],capture_output=True,text=True)
    (out/'regression.log').write_text(r.stdout+r.stderr)
    (out/'receipt.json').write_text(json.dumps({'returncode':r.returncode,'source':str(root),
      'production_sha256':{str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in prod},
      'boundary':'Production router/model/policy/controller; Android, USB readiness, TPMS visibility and native ACC boundaries stubbed. No MainActivity or QA policy.'},indent=2)+'\n')
    print(r.stdout+r.stderr,end='');print('Artifacts:',out)
    raise SystemExit(r.returncode)

if __name__=='__main__':
    main()
