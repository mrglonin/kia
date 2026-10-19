#!/usr/bin/env python3
"""Run real CompassMonitor GPS regression checks with deterministic JVM fixtures.

No Android runtime, device, network access, source rewrite, or APK build is used.
Requires a JDK via JAVA_HOME/bin or PATH, plus the existing JUnit 4 and hamcrest
JARs in ~/.gradle/caches (normally populated by the project unit-test task).

Examples:
    python3 tools/compass_regression.py --output /tmp/compass-regression
    python3 tools/compass_regression.py --source /path/to/repo --output /tmp/check

The output directory receives fixture sources, compiled classes, logs and a
source-hash receipt. Without --output a fresh temporary directory is retained.
Android orientation/geomagnetic calculations and peripheral services are fixture
boundaries; these checks do not establish physical compass accuracy or USB output.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile


# The production Monitor and policies are compiled directly from --source.
# Only Android and external application/service boundaries are replaced below.
FIXTURE_SOURCES = {
    'android/Manifest.java': r'''
package android; public class Manifest { public static class permission { public static final String ACCESS_FINE_LOCATION="fine", ACCESS_COARSE_LOCATION="coarse"; }}''',
    'android/annotation/SuppressLint.java': r'''
package android.annotation; public @interface SuppressLint { String[] value(); }''',
    'android/content/Context.java': r'''
package android.content; public class Context {
 public static final String LOCATION_SERVICE="location", SENSOR_SERVICE="sensor", POWER_SERVICE="power", WINDOW_SERVICE="window";
 public final java.util.Map<String,Object> services=new java.util.HashMap<>();
 public Context getApplicationContext(){return this;} public Object getSystemService(String name){return services.get(name);}
 public int checkSelfPermission(String p){return 0;}
}''',
    'android/content/pm/PackageManager.java': r'''
package android.content.pm; public class PackageManager { public static final int PERMISSION_GRANTED=0; }''',
    'android/hardware/GeomagneticField.java': r'''
package android.hardware; public class GeomagneticField { public GeomagneticField(float a,float b,float c,long t){} public float getDeclination(){return 0;} }''',
    'android/hardware/Sensor.java': r'''
package android.hardware; public class Sensor { public static final int TYPE_ROTATION_VECTOR=11, TYPE_GEOMAGNETIC_ROTATION_VECTOR=20; public final int type; public Sensor(int t){type=t;} public int getType(){return type;} public String getName(){return "fixture"+type;} }''',
    'android/hardware/SensorEvent.java': r'''
package android.hardware; public class SensorEvent { public Sensor sensor; public float[] values={0,0,0,1}; public int accuracy; public long timestamp; }''',
    'android/hardware/SensorEventListener.java': r'''
package android.hardware; public interface SensorEventListener { void onSensorChanged(SensorEvent e); void onAccuracyChanged(Sensor s,int a); }''',
    'android/hardware/SensorManager.java': r'''
package android.hardware; public class SensorManager {
 public static final int SENSOR_STATUS_UNRELIABLE=0, SENSOR_STATUS_ACCURACY_LOW=1, SENSOR_STATUS_ACCURACY_MEDIUM=2, SENSOR_STATUS_ACCURACY_HIGH=3, SENSOR_DELAY_GAME=1, AXIS_Y=2, AXIS_X=1, AXIS_MINUS_X=129, AXIS_MINUS_Y=130;
 public boolean available, failRotation; public final java.util.List<Integer> attempts=new java.util.ArrayList<>();
 public Sensor getDefaultSensor(int type){return available?new Sensor(type):null;} public boolean registerListener(SensorEventListener l,Sensor s,int delay,android.os.Handler h){attempts.add(s.type);return !(failRotation&&s.type==11);} public void unregisterListener(SensorEventListener l){}
 public static void getRotationMatrixFromVector(float[] out,float[] values){java.util.Arrays.fill(out,0);out[0]=out[4]=out[8]=1;}
 public static boolean remapCoordinateSystem(float[] in,int x,int y,float[] out){System.arraycopy(in,0,out,0,9);return true;}
 public static float[] getOrientation(float[] in,float[] out){out[0]=0;return out;}
}''',
    'android/location/Location.java': r'''
package android.location; public class Location {
 public String provider; public float bearing,speed; public boolean hasBearing,hasSpeed; public long elapsed,time; public double latitude,longitude,altitude;
 public Location(String p){provider=p;} public Location(Location o){provider=o.provider;bearing=o.bearing;speed=o.speed;hasBearing=o.hasBearing;hasSpeed=o.hasSpeed;elapsed=o.elapsed;time=o.time;latitude=o.latitude;longitude=o.longitude;altitude=o.altitude;}
 public void setSpeed(float v){speed=v;hasSpeed=true;} public void setBearing(float v){bearing=v;hasBearing=true;} public String getProvider(){return provider;} public boolean hasBearing(){return hasBearing;} public float getBearing(){return bearing;} public boolean hasSpeed(){return hasSpeed;} public float getSpeed(){return speed;} public long getElapsedRealtimeNanos(){return elapsed;} public long getTime(){return time;} public double getLatitude(){return latitude;} public double getLongitude(){return longitude;} public double getAltitude(){return altitude;}
}''',
    'android/location/LocationListener.java': r'''
package android.location; public interface LocationListener { void onLocationChanged(Location l); void onStatusChanged(String p,int s,android.os.Bundle b); void onProviderEnabled(String p); void onProviderDisabled(String p); }''',
    'android/location/LocationManager.java': r'''
package android.location; public class LocationManager {
 public static final String GPS_PROVIDER="gps", NETWORK_PROVIDER="network", PASSIVE_PROVIDER="passive";
 public final java.util.Map<String,Location> fixes=new java.util.HashMap<>(); public Object getProvider(String p){return new Object();} public boolean isProviderEnabled(String p){return true;} public void requestLocationUpdates(String p,long t,float d,LocationListener l,android.os.Looper looper){} public void removeUpdates(LocationListener l){} public Location getLastKnownLocation(String p){return fixes.get(p);}
}''',
    'android/os/Bundle.java': r'''
package android.os; public class Bundle {}''',
    'android/os/Handler.java': r'''
package android.os; public class Handler { public Handler(Looper l){} public void removeCallbacks(Runnable r){} public boolean post(Runnable r){return true;} public boolean postDelayed(Runnable r,long ms){return true;} }''',
    'android/os/Looper.java': r'''
package android.os; public class Looper { public static Looper getMainLooper(){return new Looper();} }''',
    'android/os/PowerManager.java': r'''
package android.os; public class PowerManager { public static final int PARTIAL_WAKE_LOCK=1; public WakeLock newWakeLock(int type,String tag){return new WakeLock();} public static class WakeLock { boolean held; public void setReferenceCounted(boolean v){} public boolean isHeld(){return held;} public void acquire(long t){held=true;} public void release(){held=false;} }}''',
    'android/os/SystemClock.java': r'''
package android.os; public class SystemClock { public static long now=100000; public static long elapsedRealtime(){return now;} public static long elapsedRealtimeNanos(){return now*1000000L;} }''',
    'android/view/Surface.java': r'''
package android.view; public class Surface { public static final int ROTATION_0=0, ROTATION_90=1, ROTATION_180=2, ROTATION_270=3; }''',
    'android/view/WindowManager.java': r'''
package android.view; public class WindowManager { public Display getDefaultDisplay(){return new Display();} public static class Display { public int getRotation(){return 0;} }}''',
    'kia/app/core/AppLog.java': r'''
package kia.app.core; public class AppLog { public static void line(android.content.Context c,String s){} }''',
    'kia/app/core/StateStore.java': r'''
package kia.app.core; public class StateStore { public static kia.app.core.model.NavigationState state=new kia.app.core.model.NavigationState(); public static kia.app.core.model.NavigationState navigation(){return state;} }''',
    'kia/app/core/model/NavigationState.java': r'''
package kia.app.core.model; public class NavigationState { public String clusterTx=""; }''',
    'kia/app/core/settings/AppSettings.java': r'''
package kia.app.core.settings; public class AppSettings { public static boolean navigationEnabled(android.content.Context c){return true;} public static boolean compassForceEnabled(android.content.Context c){return false;} }''',
    'kia/app/navigation/cluster/NavigationClusterSender.java': r'''
package kia.app.navigation.cluster; public class NavigationClusterSender { public static final java.util.List<Integer> sent=new java.util.ArrayList<>(); public NavigationClusterSender(android.content.Context c){} public kia.app.protocol.adapter.AdapterTxOutcome sendCompassStep(int step){sent.add(step);return kia.app.protocol.adapter.AdapterTxOutcome.WRITTEN;} }''',
    'kia/app/navigation/compass/CompassFixRegression.java': r'''
package kia.app.navigation.compass;

import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.hardware.SensorManager;
import android.os.SystemClock;
import kia.app.core.StateStore;
import kia.app.navigation.cluster.NavigationClusterSender;
import kia.app.navigation.domain.NavigationFeature;
import java.lang.reflect.*;

/** Actual production Monitor is invoked; boundary fixtures supply deterministic time/data. */
public final class CompassFixRegression {
    static int passed, failed;
    static final String[] PROVIDERS = {"gps", "fused", "network", "passive"};
    interface Checked { void run() throws Exception; }
    static Object get(Object o, String n) throws Exception {
        Field f = o.getClass().getDeclaredField(n); f.setAccessible(true); return f.get(o);
    }
    static Object call(Object o, String n, Class<?>[] types, Object... args) throws Exception {
        Method m = CompassMonitor.class.getDeclaredMethod(n, types); m.setAccessible(true);
        return m.invoke(o, args);
    }
    static void check(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }
    static void test(String name, Checked body) {
        try { body.run(); passed++; System.out.println("PASS " + name); }
        catch (Throwable e) { failed++; System.out.println("FAIL " + name + ": " + e); }
    }
    static Location fix(String provider, long ageMs, long wallTime, float bearing, boolean hasBearing) {
        Location l = new Location(provider);
        l.elapsed = (SystemClock.now-ageMs)*1_000_000L;
        l.time = wallTime; l.hasBearing = hasBearing; l.bearing = bearing;
        l.hasSpeed = true; l.speed = 10;
        return l;
    }
    static final class Fixture {
        final Context c = new Context();
        final LocationManager lm = new LocationManager();
        final SensorManager sm = new SensorManager();
        final CompassMonitor m;
        Fixture() {
            SystemClock.now=100000;
            StateStore.state.clusterTx="";
            NavigationClusterSender.sent.clear();
            NavigationFeature.instance=new NavigationFeature();
            c.services.put(Context.LOCATION_SERVICE,lm);
            c.services.put(Context.SENSOR_SERVICE,sm);
            m=new CompassMonitor(c);
        }
        void start() { m.start(); }
        void accept(Location l) { m.onLocationChanged(l); }
        int target() throws Exception { return (int)get(m,"targetStep"); }
        long gpsAt() throws Exception { return (long)get(m,"lastGpsBearingAt"); }
        Location latest() throws Exception { return (Location)get(m,"lastLocation"); }
        void tick() throws Exception { ((Runnable)get(m,"watchdog")).run(); }
    }
    public static void main(String[] args) throws Exception {
        test("callback before start is ignored", () -> {
            Fixture f=new Fixture(); f.accept(fix("gps",0,1000000,90,true));
            check(f.target()==-1 && NavigationFeature.instance.gpsUpdates==0,"state changed while stopped");
        });
        test("normal cached start preserves grace then sends current course", () -> {
            Fixture f=new Fixture();f.lm.fixes.put("gps",fix("gps",100,1000000,90,true));f.start();
            check(f.target()==9,"cached90 not accepted");
            check(NavigationClusterSender.sent.isEmpty(),"sent before startup grace");
            SystemClock.now+=3001;f.tick();
            check(NavigationClusterSender.sent.size()==1 && NavigationClusterSender.sent.get(0)==9,"course not sent after grace");
        });
        test("moving north at inclusive speed threshold remains valid", () -> {
            Fixture f=new Fixture();f.start();Location north=fix("gps",0,1000000,0,true);north.speed=1.4f;f.accept(north);
            check(f.target()==0,"valid north rejected");
        });
        test("stationary bearing never creates north target", () -> {
            Fixture f=new Fixture();f.start();Location l=fix("gps",0,1000000,0,true);l.speed=0;f.accept(l);
            check(f.target()==-1 && NavigationFeature.instance.lastGps==l,"stationary heading accepted or speed lost");
        });
        test("bearing without speed remains ineligible", () -> {
            Fixture f=new Fixture();f.start();Location l=fix("gps",0,1000000,90,true);l.hasSpeed=false;f.accept(l);
            check(f.target()==-1,"bearing without speed accepted");
        });
        test("10-second-old live fix cannot replace current heading or location", () -> {
            Fixture f=new Fixture();f.start();Location current=fix("gps",100,1000000,90,true);f.accept(current);long original=f.gpsAt();
            SystemClock.now+=100;f.accept(fix("passive",10000,990000,270,true));
            check(f.target()==9,"stale fix turned target");check(f.gpsAt()==original,"stale fix refreshed GPS priority");
            check(f.latest().elapsed==current.elapsed && NavigationFeature.instance.lastGps==current,"stale fix replaced latest location");
            check(NavigationFeature.instance.gpsUpdates==1,"stale fix forwarded to navigation");
        });
        test("fresh but out-of-order bearing cannot regress accepted course", () -> {
            Fixture f=new Fixture();f.start();Location current=fix("gps",100,1000000,90,true);f.accept(current);
            f.accept(fix("fused",2000,999000,180,true));
            check(f.target()==9 && f.latest().elapsed==current.elapsed,"older fresh fix regressed state");
        });
        test("identical timestamp with changed bearing is rejected", () -> {
            Fixture f=new Fixture();f.start();Location current=fix("gps",100,1000000,90,true);f.accept(current);long original=f.gpsAt();
            Location duplicate=new Location(current);duplicate.provider="passive";duplicate.bearing=270;
            SystemClock.now+=100;f.accept(duplicate);
            check(f.target()==9 && f.gpsAt()==original && NavigationFeature.instance.gpsUpdates==1,"duplicate changed state or priority");
        });
        test("same fix replayed after three seconds cannot refresh GPS priority", () -> {
            Fixture f=new Fixture();f.start();Location l=fix("gps",100,1000000,90,true);f.accept(l);long original=f.gpsAt();
            SystemClock.now+=3000;f.accept(l);
            check(f.gpsAt()==original && NavigationFeature.instance.gpsUpdates==1,"replay refreshed GPS priority");
        });
        test("priority time is actual fix time, not callback receipt time", () -> {
            Fixture f=new Fixture();f.start();Location l=fix("gps",2000,1000000,90,true);f.accept(l);
            check(f.target()==9 && f.gpsAt()==l.elapsed/1_000_000L,"priority anchor is not fix time");
        });
        test("2500ms inclusive freshness boundary is accepted", () -> {
            Fixture f=new Fixture();f.start();f.accept(fix("gps",2500,1000000,90,true));check(f.target()==9,"inclusive boundary rejected");
        });
        test("2500ms plus one nanosecond is rejected", () -> {
            Fixture f=new Fixture();f.start();Location l=fix("gps",2500,1000000,90,true);l.elapsed--;f.accept(l);
            check(f.target()==-1 && f.latest()==null && NavigationFeature.instance.gpsUpdates==0,"stale boundary accepted");
        });
        test("future live timestamp is rejected", () -> {
            Fixture f=new Fixture();f.start();f.accept(fix("gps",-1,1000000,90,true));
            check(f.target()==-1 && f.latest()==null,"future fix accepted");
        });
        test("missing live monotonic timestamp is rejected", () -> {
            Fixture f=new Fixture();f.start();Location l=fix("gps",0,1000000,90,true);l.elapsed=0;f.accept(l);
            check(f.target()==-1 && f.latest()==null,"timestamp-less fix accepted");
        });
        test("newer speed-only live location does not hide slightly older moving bearing", () -> {
            Fixture f=new Fixture();f.start();Location speed=fix("fused",50,1000050,0,false);speed.speed=8;f.accept(speed);
            Location bearing=fix("gps",100,1000000,90,true);f.accept(bearing);
            check(f.target()==9,"moving course lost behind newer speed-only fix");
            check(f.latest().elapsed==speed.elapsed && NavigationFeature.instance.lastGps==speed,"bearing fallback regressed latest location/speed");
            check(NavigationFeature.instance.lastSpeedLocation.getSpeed()==8,"older bearing sample overwrote newer navigation speed");
            check(f.gpsAt()==bearing.elapsed/1_000_000L,"bearing priority not based on selected fix");
        });
        test("newer location without motion data does not hide usable moving bearing", () -> {
            Fixture f=new Fixture();f.start();Location location=fix("network",50,1000050,0,false);location.hasSpeed=false;f.accept(location);
            f.accept(fix("gps",100,1000000,90,true));
            check(f.target()==9 && f.latest().elapsed==location.elapsed,"location-only sample hid course or latest location regressed");
        });
        test("newer stationary live speed blocks an older moving course", () -> {
            Fixture f=new Fixture();f.start();Location stationary=fix("fused",50,1000050,0,false);stationary.speed=0;f.accept(stationary);
            f.accept(fix("gps",100,1000000,90,true));
            check(f.target()==-1 && NavigationFeature.instance.lastGps==stationary,"older moving course overrode a newer stop");
            check(NavigationFeature.instance.lastSpeedLocation.getSpeed()==0,"older moving sample overrode stopped navigation speed");
        });
        test("a stop holds previous direction and newer movement can resume", () -> {
            Fixture f=new Fixture();f.start();f.accept(fix("gps",200,1000000,90,true));
            Location stationary=fix("fused",100,1000100,0,false);stationary.speed=0;f.accept(stationary);
            f.accept(fix("passive",150,1000050,180,true));check(f.target()==9,"older course replaced heading after a stop");
            f.accept(fix("gps",0,1000200,270,true));check(f.target()==27,"newer movement failed to resume");
        });
        test("cached newer speed-only provider preserves GPS bearing and latest speed", () -> {
            Fixture f=new Fixture();Location gps=fix("gps",100,1000000,90,true), speed=fix("fused",50,1000050,0,false);speed.speed=8;
            f.lm.fixes.put("gps",gps);f.lm.fixes.put("fused",speed);f.start();
            check(f.target()==9,"GPS course hidden");check(f.latest().elapsed==speed.elapsed && NavigationFeature.instance.lastGps==speed,"latest speed/location lost");
            check(NavigationFeature.instance.lastSpeedLocation.getSpeed()==8 && NavigationFeature.instance.lastBearingLocation.getBearing()==90,"cache failed to separate latest speed and usable bearing");
        });
        test("stale cached provider with newer wall time cannot hide fresh GPS", () -> {
            Fixture f=new Fixture();Location gps=fix("gps",100,1000000,90,true);
            f.lm.fixes.put("gps",gps);f.lm.fixes.put("fused",fix("fused",10000,1001000,180,true));f.start();
            check(f.target()==9 && f.latest().elapsed==gps.elapsed,"stale winner prevented fallback");
        });
        test("cached provider missing monotonic time cannot hide fresh GPS", () -> {
            Fixture f=new Fixture();Location gps=fix("gps",100,1000000,90,true), invalid=fix("fused",0,1001000,180,true);invalid.elapsed=0;
            f.lm.fixes.put("gps",gps);f.lm.fixes.put("fused",invalid);f.start();
            check(f.target()==9 && f.latest().elapsed==gps.elapsed,"invalid winner prevented fallback");
        });
        test("future cached provider cannot hide fresh GPS", () -> {
            Fixture f=new Fixture();Location gps=fix("gps",100,1000000,90,true);
            f.lm.fixes.put("gps",gps);f.lm.fixes.put("fused",fix("fused",-1,1001000,180,true));f.start();
            check(f.target()==9 && f.latest().elapsed==gps.elapsed,"future winner prevented fallback");
        });
        test("cache ranking follows monotonic recency despite backward wall clock", () -> {
            Fixture f=new Fixture();f.lm.fixes.put("gps",fix("gps",500,2000000,90,true));
            Location newer=fix("fused",100,1000000,270,true);f.lm.fixes.put("fused",newer);f.start();
            check(f.target()==27 && f.latest().elapsed==newer.elapsed,"wall-clock inversion selected older course");
        });
        test("freshest usable bearing may come from any of four cached providers", () -> {
            for(int chosen=0;chosen<PROVIDERS.length;chosen++) {
                Fixture f=new Fixture();
                for(int i=0;i<PROVIDERS.length;i++)f.lm.fixes.put(PROVIDERS[i],fix(PROVIDERS[i],i==chosen?50:500+i,1000000+i*100, i==chosen?270:90,true));
                f.start();check(f.target()==27 && f.latest().provider.equals(PROVIDERS[chosen]),"provider order won over timestamp: "+PROVIDERS[chosen]);
            }
        });
        test("speed-only versus bearing selection is invariant across all 12 provider pairs", () -> {
            for(int a=0;a<PROVIDERS.length;a++)for(int b=0;b<PROVIDERS.length;b++)if(a!=b) {
                Fixture f=new Fixture();f.lm.fixes.put(PROVIDERS[a],fix(PROVIDERS[a],100,1000000,90,true));
                f.lm.fixes.put(PROVIDERS[b],fix(PROVIDERS[b],50,1000050,0,false));f.start();
                check(f.target()==9 && f.latest().provider.equals(PROVIDERS[b]),"provider pair lost heading or location: "+a+","+b);
            }
        });
        test("newest cached stationary speed blocks older moving course in every provider order", () -> {
            for(int a=0;a<PROVIDERS.length;a++)for(int b=0;b<PROVIDERS.length;b++)if(a!=b) {
                Fixture f=new Fixture();f.lm.fixes.put(PROVIDERS[a],fix(PROVIDERS[a],100,1000000,90,true));
                Location stopped=fix(PROVIDERS[b],50,1000050,0,false);stopped.speed=0;f.lm.fixes.put(PROVIDERS[b],stopped);f.start();
                check(f.target()==-1 && f.latest().provider.equals(PROVIDERS[b]),"cached stationary state overridden: "+a+","+b);
            }
        });
        test("reregister cannot replay identical cached sample or refresh bearing time", () -> {
            Fixture f=new Fixture();Location gps=fix("gps",100,1000000,90,true);f.lm.fixes.put("gps",gps);f.start();long original=f.gpsAt();int updates=NavigationFeature.instance.gpsUpdates;
            SystemClock.now+=100;f.m.onProviderEnabled("gps");
            check(f.target()==9 && f.gpsAt()==original && NavigationFeature.instance.gpsUpdates==updates,"cache replay refreshed data");
        });
        test("stop ignores callbacks and restart accepts new fresh heading", () -> {
            Fixture f=new Fixture();f.start();f.accept(fix("gps",100,1000000,90,true));f.m.stop();int updates=NavigationFeature.instance.gpsUpdates;
            f.accept(fix("gps",0,1000100,270,true));f.tick();
            check(f.target()==-1 && f.latest()==null && NavigationFeature.instance.gpsUpdates==updates,"stopped monitor accepted callback");
            f.start();f.accept(fix("gps",0,1000200,270,true));check(f.target()==27,"restart failed to reset acceptance state");
        });
        test("moving north after west is not treated as absent bearing", () -> {
            Fixture f=new Fixture();f.start();f.accept(fix("gps",100,1000000,270,true));f.accept(fix("gps",0,1000100,0,true));
            check(f.target()==0,"north failed after prior west target");
        });
        test("same-timestamp position-only sample can gain bearing without rewinding navigation", () -> {
            Fixture f=new Fixture();f.start();Location position=fix("network",100,1000000,0,false);position.hasSpeed=false;f.accept(position);
            Location enriched=new Location(position);enriched.provider="gps";enriched.hasSpeed=true;enriched.speed=10;enriched.hasBearing=true;enriched.bearing=90;f.accept(enriched);
            check(f.target()==9,"same-time usable course rejected after position-only sample");
            check(NavigationFeature.instance.lastGps.elapsed==position.elapsed,"same-time enrichment rewound navigation timestamp");
            check(f.gpsAt()==enriched.elapsed/1_000_000L,"same-time course used wrong priority anchor");
        });
        test("same-timestamp richer GPS reaches navigation speed once in repeated provider pairs", () -> {
            Fixture f=new Fixture();f.start();
            for(int cycle=0;cycle<4;cycle++) {
                SystemClock.now=100000+cycle*500;
                Location position=fix("fused",0,1000000+cycle*500,0,false);position.hasSpeed=false;f.accept(position);
                Location richer=new Location(position);richer.provider="gps";richer.hasSpeed=true;richer.speed=10;richer.hasBearing=true;richer.bearing=90;f.accept(richer);
                check(NavigationFeature.instance.lastSpeedLocation!=null && NavigationFeature.instance.lastSpeedLocation.getSpeed()==10,"cycle "+cycle+" lost speed behind same-time position-only callback");
                check(NavigationFeature.instance.lastGps.elapsed==position.elapsed && f.target()==9,"enrichment changed timestamp or lost course");
                int updates=NavigationFeature.instance.gpsUpdates, motionUpdates=NavigationFeature.instance.motionUpdates;long priority=f.gpsAt();SystemClock.now+=100;f.accept(new Location(richer));
                check(NavigationFeature.instance.gpsUpdates==updates && NavigationFeature.instance.motionUpdates==motionUpdates && f.gpsAt()==priority,"identical enriched replay refreshed navigation or GPS priority");
            }
        });
        test("newer position-only callbacks cannot starve offset GPS motion across repeated cycles", () -> {
            Fixture f=new Fixture();f.start();
            for(int cycle=0;cycle<4;cycle++) {
                SystemClock.now=100000+cycle*500;
                Location position=fix("fused",0,1000050+cycle*500,0,false);position.hasSpeed=false;position.latitude=11+cycle;f.accept(position);
                Location moving=fix("gps",50,1000000+cycle*500,90,true);moving.latitude=-10;f.accept(moving);
                check(f.target()==9,"offset course missing");
                check(f.latest().elapsed==position.elapsed && f.latest().latitude==position.latitude && NavigationFeature.instance.lastGps.elapsed==position.elapsed,"motion sample rewound latest position");
                Location speed=NavigationFeature.instance.lastSpeedLocation, bearing=NavigationFeature.instance.lastBearingLocation;
                check(speed!=null && speed.elapsed==moving.elapsed && speed.getSpeed()==10 && bearing!=null && bearing.elapsed==moving.elapsed && bearing.getBearing()==90,"cycle "+cycle+" lost offset speed/bearing behind newer position-only sample");
                int updates=NavigationFeature.instance.motionUpdates;f.accept(new Location(moving));
                check(NavigationFeature.instance.motionUpdates==updates,"offset motion replay was forwarded twice");
            }
        });
        test("same-timestamp stop dominates known moving speed without synthetic moving-north merge", () -> {
            Fixture f=new Fixture();f.start();Location moving=fix("gps",100,1000000,0,false);f.accept(moving);
            Location stopped=new Location(moving);stopped.provider="fused";stopped.speed=0;stopped.hasBearing=true;stopped.bearing=0;f.accept(stopped);
            Location motion=NavigationFeature.instance.lastSpeedLocation;
            check(motion!=null && motion.getSpeed()==0,"same-time stop failed to replace known moving speed");
            check(f.target()==-1 && NavigationFeature.instance.lastBearingLocation==null,"same-time stop created moving compass or navigation direction");
            int updates=NavigationFeature.instance.motionUpdates;f.accept(new Location(stopped));
            check(NavigationFeature.instance.motionUpdates==updates,"same stop replay was forwarded twice");
            Location conflict=new Location(moving);conflict.hasBearing=true;conflict.bearing=90;f.accept(conflict);
            check(NavigationFeature.instance.lastSpeedLocation.getSpeed()==0 && f.target()==-1,"same-time conflicting movement replaced confirmed stop");
        });
        test("stationary barrier survives newer position-only data in live and all 24 cached orders", () -> {
            Fixture f=new Fixture();f.start();Location stopped=fix("fused",1000,1000000,0,false);stopped.speed=0;f.accept(stopped);
            Location position=fix("network",900,1000100,0,false);position.hasSpeed=false;f.accept(position);
            f.accept(fix("gps",1100,999900,90,true));check(f.target()==-1,"position-only sample erased known stop");
            check(NavigationFeature.instance.lastGps==position,"delayed course regressed navigation location");
            f.accept(fix("gps",800,1000200,270,true));check(f.target()==27,"newer movement did not clear stop for eligible course");
            for(int a=0;a<PROVIDERS.length;a++)for(int b=0;b<PROVIDERS.length;b++)for(int c=0;c<PROVIDERS.length;c++)if(a!=b&&a!=c&&b!=c) {
                Fixture cached=new Fixture();Location stop=fix(PROVIDERS[a],1000,1000000,0,false);stop.speed=0;
                Location pos=fix(PROVIDERS[b],900,1000100,0,false);pos.hasSpeed=false;
                cached.lm.fixes.put(PROVIDERS[a],stop);cached.lm.fixes.put(PROVIDERS[b],pos);
                cached.lm.fixes.put(PROVIDERS[c],fix(PROVIDERS[c],1100,999900,90,true));cached.start();
                check(cached.target()==-1 && cached.latest().provider.equals(PROVIDERS[b]),"cache discarded stop barrier behind position-only winner: "+a+","+b+","+c);
                check(NavigationFeature.instance.lastSpeedLocation!=null && NavigationFeature.instance.lastSpeedLocation.getSpeed()==0,"cache lost stopped navigation speed behind position-only winner");
            }
        });
        test("existing circular math and all 144 canonical transitions remain valid", () -> {
            Fixture f=new Fixture();
            for(int deg=-1080;deg<=1080;deg++) {
                float out=(float)call(f.m,"normalize",new Class<?>[]{float.class},(float)deg);
                check(out>=0 && out<360 && Math.abs((((deg%360)+360)%360)-out)<0.0001,"normalize "+deg);
            }
            check((float)call(f.m,"blendDegrees",new Class<?>[]{float.class,float.class,float.class},350f,10f,0.5f)==0,"circular blend");
            for(int a=0;a<36;a+=3)for(int b=0;b<36;b+=3) {
                int cur=a;for(int i=0;i<6&&cur!=b;i++)cur=(int)call(null,"nextStep",new Class<?>[]{int.class,int.class},cur,b);
                check(cur==b,"transition "+a+" -> "+b);
            }
        });
        System.out.println("RESULT passed="+passed+" failed="+failed+" total="+(passed+failed));
        if(failed>0)System.exit(1);
    }
}
''',
    'kia/app/navigation/domain/NavigationFeature.java': r'''
package kia.app.navigation.domain;
public class NavigationFeature {
 public static NavigationFeature instance=new NavigationFeature();
 public android.location.Location lastGps, lastMotion, lastSpeedLocation, lastBearingLocation;
 public float lastHeading; public int gpsUpdates, motionUpdates;
 public static NavigationFeature get(android.content.Context c){return instance;}
 public void updateGpsLocation(android.location.Location l){updateGpsLocation(l,true,true);}
 public void updateGpsLocation(android.location.Location l,boolean speed,boolean bearing){lastGps=l;gpsUpdates++;updateGpsMotion(l,speed,bearing);}
 public void updateGpsMotion(android.location.Location l){updateGpsMotion(l,true,true);}
 public void updateGpsMotion(android.location.Location l,boolean speed,boolean bearing){
  if(l==null)return;
  boolean acceptedSpeed=speed&&l.hasSpeed(),acceptedBearing=bearing&&l.hasBearing();
  if(acceptedSpeed)lastSpeedLocation=new android.location.Location(l);
  if(acceptedBearing)lastBearingLocation=new android.location.Location(l);
  if(acceptedSpeed||acceptedBearing){lastMotion=new android.location.Location(l);motionUpdates++;}
 }
 public void updateDeviceHeading(float h,float a,String name){lastHeading=h;}
 public boolean finishDirectionHeadingNeeded(){return false;}
 public boolean activeForCompassSuppression(){return false;}
 public boolean finishCompassSuppressed(){return false;}
}
''',
    'kia/app/protocol/adapter/AdapterGateway.java': r'''
package kia.app.protocol.adapter; public class AdapterGateway { public static final AdapterGateway INSTANCE=new AdapterGateway(); public static AdapterGateway get(android.content.Context c){return INSTANCE;} public long connectionEpoch(){return 1;} public boolean usbReady(){return true;} }''',
    'kia/app/protocol/adapter/AdapterTxOutcome.java': r'''
package kia.app.protocol.adapter; public enum AdapterTxOutcome { WRITTEN, BLOCKED }''',
}


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def find_java_tools():
    suffix = ".exe" if os.name == "nt" else ""
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        java = Path(java_home) / "bin" / ("java" + suffix)
        javac = Path(java_home) / "bin" / ("javac" + suffix)
        if java.is_file() and javac.is_file():
            return str(java), str(javac)
    java, javac = shutil.which("java"), shutil.which("javac")
    if java and javac:
        return java, javac
    raise SystemExit("A JDK is required. Set JAVA_HOME or put java and javac on PATH.")


def find_test_jar(pattern):
    cache = Path.home() / ".gradle" / "caches"
    matches = sorted(
        path for path in cache.rglob(pattern)
        if not path.name.endswith(("-sources.jar", "-javadoc.jar"))
    ) if cache.is_dir() else []
    if not matches:
        raise SystemExit(
            "Missing " + pattern + " under ~/.gradle/caches. "
            "Run the project unit-test task to populate its existing test dependencies."
        )
    return matches[-1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=Path(__file__).resolve().parents[1],
                        help="repository root containing app/app/src (default: this script's repository)")
    parser.add_argument("--output", type=Path,
                        help="directory for fixtures, classes, logs and receipt; default: new retained temporary directory")
    args = parser.parse_args()
    source = args.source.expanduser().resolve()
    output = args.output.expanduser().resolve() if args.output else Path(tempfile.mkdtemp(prefix="compass-regression-"))
    output.mkdir(parents=True, exist_ok=True)
    java, javac = find_java_tools()
    junit = find_test_jar("junit-4*.jar")
    hamcrest = find_test_jar("hamcrest-core-*.jar")
    production_dir = source / "app/app/src/main/java/kia/app/navigation/compass"
    test_dir = source / "app/app/src/test/java/kia/app/navigation/compass"
    production = [production_dir / name for name in (
        "CompassMonitor.java", "CompassLocationPolicy.java", "CompassTxPolicy.java")]
    tests = [test_dir / name for name in (
        "CompassLocationPolicyTest.java", "CompassTxPolicyTest.java")]
    missing = [str(path) for path in production + tests if not path.is_file()]
    if missing:
        raise SystemExit("Required source files missing: " + ", ".join(missing))
    original_hashes = {str(path.relative_to(source)): sha256(path) for path in production + tests}
    fixture_dir, classes = output / "fixture-src", output / "classes"
    classes.mkdir(exist_ok=True)
    fixture_files = []
    for name, content in FIXTURE_SOURCES.items():
        fixture = fixture_dir / name
        fixture.parent.mkdir(parents=True, exist_ok=True)
        fixture.write_text(content.lstrip("\n"), encoding="utf-8")
        fixture_files.append(fixture)
    classpath = os.pathsep.join(map(str, (classes, junit, hamcrest)))
    command = [javac, "-encoding", "UTF-8", "-cp", classpath, "-d", str(classes)]
    command.extend(str(path) for path in fixture_files + production + tests)
    compiled = subprocess.run(command, text=True, capture_output=True)
    compile_log = compiled.stdout + compiled.stderr
    (output / "compile.txt").write_text(compile_log, encoding="utf-8")
    if compiled.returncode:
        print(compile_log, end="")
        print("Compilation failed; output:", output)
        return compiled.returncode
    results, runs = [], []
    for entry in (
        ["org.junit.runner.JUnitCore", "kia.app.navigation.compass.CompassLocationPolicyTest",
         "kia.app.navigation.compass.CompassTxPolicyTest"],
        ["kia.app.navigation.compass.CompassFixRegression"],
    ):
        run = subprocess.run([java, "-cp", classpath] + entry, text=True, capture_output=True)
        result = run.stdout + run.stderr
        print(result, end="")
        results.append(result)
        runs.append({"entry": entry, "exit_code": run.returncode})
    combined = "\n".join(results)
    (output / "results.txt").write_text(combined, encoding="utf-8")
    match = re.search(r"RESULT passed=(\d+) failed=(\d+) total=(\d+)", combined)
    current_hashes = {str(path.relative_to(source)): sha256(path) for path in production + tests}
    source_unchanged = original_hashes == current_hashes
    receipt = {
        "source": str(source), "output": str(output),
        "scope": "unchanged production Monitor and policies with deterministic Android/application boundary fixtures",
        "source_hashes_at_compile": original_hashes,
        "source_unchanged_during_run": source_unchanged,
        "runner_sha256": sha256(Path(__file__).resolve()),
        "runs": runs,
        "regression_checks": dict(zip(("passed", "failed", "total"), map(int, match.groups()))) if match else None,
        "product_modified_by_runner": False,
        "limitations": [
            "No device, network, ADB, APK build, installation or physical compass validation.",
            "Android orientation, geomagnetic model, application services and USB transport are fixtures.",
            "Dormant sensor branches and persisted-step restoration are outside this GPS regression scope.",
        ],
    }
    (output / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    if not source_unchanged:
        print("Source changed during this run; repeat after edits settle.")
    print("Output:", output)
    return 1 if any(run["exit_code"] for run in runs) or not source_unchanged or not match else 0


if __name__ == "__main__":
    raise SystemExit(main())
