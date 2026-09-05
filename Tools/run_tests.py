#!/usr/bin/env python3
"""Offline contract tests. Needs Python 3 and a JDK 8+ (not Android SDK).

Compiles the actual three optimizer classes against deliberately small Android
fakes and runs JVM tests. Also parses all modified production Java files.
These fakes do NOT replace an Android Gradle build or tests on a real camera.
"""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
CAMERA = ROOT / 'TMessagesProj/src/main/java/org/telegram/messenger/camera'
SOURCES = {}

def source(path, text):
    SOURCES[path] = text

source('android/annotation/TargetApi.java', 'package android.annotation; public @interface TargetApi { int value(); }')
source('android/content/SharedPreferences.java', """
package android.content;
import java.util.*;
public class SharedPreferences {
 public final Map<String,Object> data = new HashMap<>();
 public boolean getBoolean(String k, boolean d) { Object v=data.get(k); return v==null?d:(Boolean)v; }
 public String getString(String k, String d) { Object v=data.get(k); return v==null?d:(String)v; }
 public Editor edit() { return new Editor(); }
 public class Editor {
  public Editor putBoolean(String k,boolean v) { data.put(k,v);return this; }
  public Editor putString(String k,String v) { data.put(k,v);return this; }
  public Editor remove(String k) { data.remove(k);return this; }
  public Editor clear() { data.clear();return this; }
  public void apply() {}
 }
}
""")
source('android/content/Context.java', """
package android.content;
public class Context {
 public static final int MODE_PRIVATE=0;
 public final SharedPreferences prefs=new SharedPreferences();
 public SharedPreferences getSharedPreferences(String n,int m) { return prefs; }
}
""")
source('android/os/Build.java', 'package android.os; public class Build { public static String FINGERPRINT="test-firmware"; }')
source('android/os/Handler.java', 'package android.os; public class Handler {}')
source('android/graphics/SurfaceTexture.java', 'package android.graphics; public class SurfaceTexture {}')
source('android/util/Size.java', """
package android.util;
public final class Size {
 final int w,h; public Size(int w,int h) { this.w=w;this.h=h; }
 public int getWidth(){return w;} public int getHeight(){return h;}
 public String toString(){return w+"x"+h;}
}
""")
source('android/util/Range.java', """
package android.util;
public final class Range<T> {
 final T lo,hi; public Range(T a,T b){lo=a;hi=b;}
 public T getLower(){return lo;} public T getUpper(){return hi;}
 public String toString(){return "["+lo+", "+hi+"]";}
}
""")
source('org/telegram/messenger/ApplicationLoader.java', 'package org.telegram.messenger; public class ApplicationLoader { public static android.content.Context applicationContext=new android.content.Context(); }')
source('org/telegram/messenger/BuildVars.java', 'package org.telegram.messenger; public class BuildVars { public static boolean LOGS_ENABLED=true; }')
source('org/telegram/messenger/FileLog.java', """
package org.telegram.messenger;
public class FileLog {
 public static final java.util.List<String> messages=new java.util.ArrayList<>();
 public static void d(String s){messages.add(s);}
 public static void e(String s,Throwable t){messages.add(s+" "+t.getClass().getSimpleName());}
}
""")
source('org/telegram/messenger/Utilities.java', """
package org.telegram.messenger;
public class Utilities {
 public static String MD5(String s){try {
  byte[] hash=java.security.MessageDigest.getInstance("MD5").digest(s.getBytes("UTF-8"));
  StringBuilder b=new StringBuilder();for(byte x:hash)b.append(String.format("%02x",x&255));return b.toString();
 }catch(Exception e){throw new RuntimeException(e);}}
}
""")
source('android/hardware/Camera.java', """
package android.hardware;
import java.util.*;
public class Camera {
 public int calls; public boolean rejectTuning, rejectAll;
 public Parameters last;
 public void setParameters(Parameters p) {
  calls++;
  if(rejectAll || rejectTuning && "auto".equals(p.getAntibanding()))throw new RuntimeException("driver rejected");
  last=p;
 }
 public static class Size { public int width,height; public Size(int w,int h){width=w;height=h;} }
 public static class Parameters {
  public static final String FOCUS_MODE_CONTINUOUS_VIDEO="continuous-video", FOCUS_MODE_CONTINUOUS_PICTURE="continuous-picture", FOCUS_MODE_AUTO="auto", WHITE_BALANCE_AUTO="auto", ANTIBANDING_AUTO="auto";
  public List<String> focuses=Arrays.asList("continuous-video","continuous-picture","auto");
  public List<String> balances=Arrays.asList("auto"); public List<String> antis=Arrays.asList("auto");
  public List<int[]> fps=Arrays.asList(new int[]{15000,30000},new int[]{30000,30000});
  public List<Size> video=Arrays.asList(new Size(1280,720),new Size(640,480));
  public List<Size> previews=video;
  public boolean supportsStabilization=true;
  private Map<String,String> values=new HashMap<>();
  private final Map<String,Map<String,String>> snapshots=new HashMap<>();
  public Parameters(){values.put("focus","fixed");values.put("wb","daylight");values.put("anti","off");values.put("eis","false");values.put("flash","torch");values.put("zoom","7");values.put("rotation","90");}
  public List<String> getSupportedFocusModes(){return focuses;}
  public List<String> getSupportedWhiteBalance(){return balances;}
  public List<String> getSupportedAntibanding(){return antis;}
  public List<int[]> getSupportedPreviewFpsRange(){return fps;}
  public List<Size> getSupportedVideoSizes(){return video;}
  public List<Size> getSupportedPreviewSizes(){return previews;}
  public Size getPreviewSize(){return new Size(1280,720);} public Size getPictureSize(){return new Size(1920,1080);}
  public void setFocusMode(String v){values.put("focus",v);} public String getFocusMode(){return values.get("focus");}
  public void setWhiteBalance(String v){values.put("wb",v);} public String getWhiteBalance(){return values.get("wb");}
  public void setAntibanding(String v){values.put("anti",v);} public String getAntibanding(){return values.get("anti");}
  public void setPreviewFpsRange(int l,int h){values.put("fps",l+"-"+h);}
  public boolean isVideoStabilizationSupported(){return supportsStabilization;}
  public void setVideoStabilization(boolean v){values.put("eis",String.valueOf(v));}
  public boolean getVideoStabilization(){return Boolean.parseBoolean(values.get("eis"));}
  public String value(String k){return values.get(k);}
  public String flatten(){String k="snapshot-"+snapshots.size();snapshots.put(k,new HashMap<>(values));return k;}
  public void unflatten(String k){values=new HashMap<>(snapshots.get(k));}
 }
}
""")
source('android/media/CamcorderProfile.java', """
package android.media;
import java.util.*;
public class CamcorderProfile {
 public static final int QUALITY_LOW=0,QUALITY_HIGH=1,QUALITY_480P=4,QUALITY_720P=5;
 public int videoFrameWidth,videoFrameHeight,videoFrameRate,videoBitRate;
 public static final Map<Integer,CamcorderProfile> supported=new HashMap<>();
 public CamcorderProfile(int w,int h,int fps,int rate){videoFrameWidth=w;videoFrameHeight=h;videoFrameRate=fps;videoBitRate=rate;}
 public static boolean hasProfile(int camera,int q){return supported.containsKey(q);}
 public static CamcorderProfile get(int camera,int q){return supported.get(q);}
}
""")
source('android/hardware/camera2/CameraAccessException.java', 'package android.hardware.camera2; public class CameraAccessException extends Exception { public CameraAccessException(String s){super(s);} }')
source('android/hardware/camera2/TotalCaptureResult.java', 'package android.hardware.camera2; public class TotalCaptureResult {}')
source('android/hardware/camera2/CaptureFailure.java', 'package android.hardware.camera2; public class CaptureFailure { public static final int REASON_ERROR=0,REASON_FLUSHED=1; final int r;public CaptureFailure(int r){this.r=r;}public int getReason(){return r;} }')
source('android/hardware/camera2/CaptureRequest.java', """
package android.hardware.camera2;
import java.util.*;
import android.util.Range;
public class CaptureRequest {
 public static class Key<T>{ final String name;public Key(String n){name=n;} }
 public static final int CONTROL_AF_MODE_OFF=0, CONTROL_AF_MODE_AUTO=1,CONTROL_AF_MODE_CONTINUOUS_VIDEO=3,CONTROL_AF_MODE_CONTINUOUS_PICTURE=4,CONTROL_AWB_MODE_AUTO=1,CONTROL_AE_ANTIBANDING_MODE_AUTO=3,LENS_OPTICAL_STABILIZATION_MODE_OFF=0,LENS_OPTICAL_STABILIZATION_MODE_ON=1,CONTROL_VIDEO_STABILIZATION_MODE_OFF=0,CONTROL_VIDEO_STABILIZATION_MODE_ON=1;
 public static final Key<Integer> CONTROL_AF_MODE=new Key<>("af"),CONTROL_AWB_MODE=new Key<>("awb"),CONTROL_AE_ANTIBANDING_MODE=new Key<>("anti"),LENS_OPTICAL_STABILIZATION_MODE=new Key<>("ois"),CONTROL_VIDEO_STABILIZATION_MODE=new Key<>("eis"),FLASH_MODE=new Key<>("flash"),JPEG_ORIENTATION=new Key<>("orientation");
 public static final Key<Range<Integer>> CONTROL_AE_TARGET_FPS_RANGE=new Key<>("fps");
 final Map<Key<?>,Object> values;
 CaptureRequest(Map<Key<?>,Object> m){values=new HashMap<>(m);}
 @SuppressWarnings("unchecked") public <T>T get(Key<T> k){return (T)values.get(k);}
 public static class Builder {
  final Map<Key<?>,Object> values=new HashMap<>();
  public <T>void set(Key<T> k,T v){values.put(k,v);}
  @SuppressWarnings("unchecked") public <T>T get(Key<T> k){return (T)values.get(k);}
  public CaptureRequest build(){return new CaptureRequest(values);}
 }
}
""")
source('android/hardware/camera2/CameraCharacteristics.java', """
package android.hardware.camera2;
import java.util.*;
import android.util.Range;
import android.hardware.camera2.params.StreamConfigurationMap;
public class CameraCharacteristics {
 public static class Key<T>{}
 public static final Key<int[]> CONTROL_AF_AVAILABLE_MODES=new Key<>(),CONTROL_AWB_AVAILABLE_MODES=new Key<>(),CONTROL_AE_AVAILABLE_ANTIBANDING_MODES=new Key<>(),LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION=new Key<>(),CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES=new Key<>();
 public static final Key<StreamConfigurationMap> SCALER_STREAM_CONFIGURATION_MAP=new Key<>();
 public static final Key<Range<Integer>[]> CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES=new Key<>();
 public final Map<Key<?>,Object> values=new HashMap<>();
 public List<CaptureRequest.Key<?>> keys=Arrays.<CaptureRequest.Key<?>>asList(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AWB_MODE,CaptureRequest.CONTROL_AE_ANTIBANDING_MODE,CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE);
 @SuppressWarnings("unchecked") public <T>T get(Key<T> k){return (T)values.get(k);}
 public <T>void put(Key<T> k,T v){values.put(k,v);}
 public List<CaptureRequest.Key<?>> getAvailableCaptureRequestKeys(){return keys;}
}
""")
source('android/hardware/camera2/params/StreamConfigurationMap.java', """
package android.hardware.camera2.params;
import android.util.Size;
public class StreamConfigurationMap {
 public long duration;public boolean broken;
 public <T>long getOutputMinFrameDuration(Class<T> c,Size s){if(broken)throw new IllegalArgumentException("bad HAL metadata");return duration;}
}
""")
source('android/hardware/camera2/CameraCaptureSession.java', """
package android.hardware.camera2;
import android.os.Handler;
public class CameraCaptureSession {
 public int calls,captures;public boolean rejectOptimized,rejectAll;
 public CaptureRequest last;public CaptureCallback callback;
 public int setRepeatingRequest(CaptureRequest r,CaptureCallback c,Handler h)throws CameraAccessException{return submit(r,c);}
 public int capture(CaptureRequest r,CaptureCallback c,Handler h)throws CameraAccessException{captures++;return submit(r,c);}
 private int submit(CaptureRequest r,CaptureCallback c)throws CameraAccessException{
  calls++;if(rejectAll || rejectOptimized && r.get(CaptureRequest.CONTROL_AF_MODE)!=null)throw new CameraAccessException("rejected");last=r;callback=c;return calls;
 }
 public void complete(){callback.onCaptureCompleted(this,last,new TotalCaptureResult());}
 public void fail(int r){callback.onCaptureFailed(this,last,new CaptureFailure(r));}
 public abstract static class CaptureCallback {
  public void onCaptureCompleted(CameraCaptureSession s,CaptureRequest r,TotalCaptureResult t){}
  public void onCaptureFailed(CameraCaptureSession s,CaptureRequest r,CaptureFailure f){}
 }
}
""")
source('org/telegram/messenger/camera/CameraOptimizerContractTest.java', """
package org.telegram.messenger.camera;
import android.hardware.Camera;
import android.media.CamcorderProfile;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.*;
import android.util.*;
import org.telegram.messenger.*;
import java.util.*;
public class CameraOptimizerContractTest {
 static int checks=0;
 static void ok(boolean v,String s){checks++;if(!v)throw new AssertionError(s);}
 static void eq(Object a,Object b,String s){ok(Objects.equals(a,b),s+" actual="+a+" expected="+b);}
 static void reset(){CameraAutoOptimizer.resetProfiles();Build.FINGERPRINT="test-firmware";CamcorderProfile.supported.clear();}
 static CameraAutoOptimizer.Profile state2(String mode){return CameraAutoOptimizer.profile("camera2","0",mode,"1280x720");}
 static CameraAutoOptimizer.Profile state1(){return CameraAutoOptimizer.profile("camera1","0","video","1280x720/1920x1080");}
 @SuppressWarnings("unchecked") static CameraCharacteristics metadata(){
  CameraCharacteristics c=new CameraCharacteristics();
  c.put(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES,new int[]{0,1,3,4});
  c.put(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES,new int[]{1});
  c.put(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES,new int[]{0,3});
  c.put(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION,new int[]{0,1});
  c.put(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES,new int[]{0,1});
  c.put(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES,new Range[]{new Range<>(15,30),new Range<>(30,30),new Range<>(24,24),new Range<>(30,60)});
  c.put(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP,new StreamConfigurationMap());return c;
 }
 static CaptureRequest.Builder builder(){CaptureRequest.Builder b=new CaptureRequest.Builder();b.set(CaptureRequest.FLASH_MODE,2);b.set(CaptureRequest.JPEG_ORIENTATION,90);return b;}
 static void repeat(Camera2AutoOptimizer o,CameraCaptureSession s,CameraCharacteristics c,boolean bypass)throws Exception{o.repeating(s,builder(),c,new Size(1280,720),"0",true,bypass,new Handler());}
 static void policy(){
  eq(CameraOptimizationPolicy.chooseFpsRange(null,30,true),-1,"null ranges");
  eq(CameraOptimizationPolicy.chooseFpsRange(new int[][]{{30,60}},30,true),-1,"no fabricated 30-60 fallback");
  int[][] r={{15,30},{30,30},{60,60},{24,24}};
  eq(CameraOptimizationPolicy.chooseFpsRange(r,30,true),1,"stable video");
  eq(CameraOptimizationPolicy.chooseFpsRange(r,30,false),0,"photo exposure latitude");
  eq(CameraOptimizationPolicy.chooseFpsRange(r,24,true),3,"stream budget");
  eq(CameraOptimizationPolicy.chooseFpsRange(new int[][]{{15000,30000},{30000,30000}},30000,true),1,"millifps");
  eq(CameraOptimizationPolicy.chooseFpsRange(new int[][]{null,{}, {0,30},{30,15},{30}},30,true),-1,"malformed ranges");
  eq(CameraOptimizationPolicy.chooseMode(new int[]{0,1},4,3,1,0),1,"focus fallback");
  eq(CameraOptimizationPolicy.chooseMode(null,1),null,"null modes");
  eq(CameraOptimizationPolicy.camera2FpsScale(null),1,"no scale assumption for null metadata");
  eq(CameraOptimizationPolicy.camera2FpsScale(new int[][]{{15000,30000},{30000,30000}}),1000,"millifps HAL");
  eq(CameraOptimizationPolicy.camera2FpsScale(new int[][]{{15,30},{30,60}}),1,"standard fps HAL");
  eq(CameraOptimizationPolicy.camera2FpsScale(new int[][]{{15,30},{30000,30000}}),1,"mixed metadata handled conservatively");
  Random random=new Random(7081);
  for(int trial=0;trial<10000;trial++){
   int[][] ranges=new int[random.nextInt(12)][];for(int i=0;i<ranges.length;i++)ranges[i]=new int[]{random.nextInt(70)-5,random.nextInt(70)-5};
   int target=random.nextInt(65)+1;boolean video=random.nextBoolean();int best=CameraOptimizationPolicy.chooseFpsRange(ranges,target,video);
   for(int i=0;i<ranges.length;i++){
    int[] x=ranges[i];boolean valid=x[0]>0&&x[1]>=x[0]&&x[1]<=target;
    if(valid){ok(best>=0,"valid candidate is retained");int[] b=ranges[best];ok(b[1]>=x[1],"maximum within budget");if(b[1]==x[1])ok(video?b[0]>=x[0]:b[0]<=x[0],"mode tie break");}
   }
   if(best>=0)ok(ranges[best][0]>0&&ranges[best][1]>=ranges[best][0]&&ranges[best][1]<=target,"selected advertised legal range");
  }
 }
 static void legacy(){
  reset();Camera cam=new Camera();Camera.Parameters p=new Camera.Parameters();
  CameraAutoOptimizer.applyLegacy(cam,p,0,false,false);
  eq(p.getFocusMode(),"continuous-picture","photo AF");eq(p.value("fps"),"15000-30000","photo FPS");ok(!p.getVideoStabilization(),"photo EIS disabled");
  eq(p.value("flash"),"torch","preserve flash");eq(p.value("zoom"),"7","preserve zoom");eq(p.value("rotation"),"90","preserve orientation");
  CameraAutoOptimizer.applyLegacy(cam,p,0,true,false);eq(p.getFocusMode(),"continuous-video","video AF");eq(p.value("fps"),"30000-30000","video FPS");ok(p.getVideoStabilization(),"video EIS");
  reset();p=new Camera.Parameters();p.focuses=null;p.fps=null;p.balances=null;p.antis=null;p.supportsStabilization=false;
  CameraAutoOptimizer.applyLegacy(new Camera(),p,0,true,false);eq(p.getFocusMode(),"fixed","fixed-focus/null metadata");
  reset();p=new Camera.Parameters();CameraAutoOptimizer.applyLegacy(new Camera(),p,0,true,true);eq(p.value("anti"),"off","barcode bypass");
  reset();cam=new Camera();cam.rejectTuning=true;p=new Camera.Parameters();CameraAutoOptimizer.applyLegacy(cam,p,0,true,false);
  eq(cam.calls,2,"legacy one retry");eq(p.value("anti"),"off","legacy baseline restored");ok(state1().disabled(),"legacy cache after accepted baseline");
  CameraAutoOptimizer.applyLegacy(cam,new Camera.Parameters(),0,true,false);eq(cam.calls,3,"cached legacy skip");
  String old=state1().key;Build.FINGERPRINT="updated-firmware";ok(!old.equals(state1().key),"firmware invalidates key");ok(!state1().disabled(),"firmware gets fresh profile");
  reset();cam=new Camera();cam.rejectAll=true;boolean thrown=false;try{CameraAutoOptimizer.applyLegacy(cam,new Camera.Parameters(),0,true,false);}catch(RuntimeException e){thrown=true;}
  ok(thrown,"both failures propagate");ok(!state1().disabled(),"both failures do not poison cache");
  reset();CameraAutoOptimizer.setEnabled(false);p=new Camera.Parameters();CameraAutoOptimizer.applyLegacy(new Camera(),p,0,true,false);eq(p.value("anti"),"off","kill switch");CameraAutoOptimizer.setEnabled(true);
 }
 static void recorder(){
  reset();CamcorderProfile p720=new CamcorderProfile(1280,720,30,5000000),p480=new CamcorderProfile(640,480,30,2000000);
  CamcorderProfile.supported.put(5,p720);CamcorderProfile.supported.put(4,p480);CamcorderProfile.supported.put(0,p480);
  Camera.Parameters p=new Camera.Parameters();eq(CameraAutoOptimizer.recorderProfile(0,1,1,p),p720,"prefer real 720p video profile");
  eq(CameraAutoOptimizer.recorderProfile(0,4,1,p),p480,"existing LG 480p quirk");
  p.video=Arrays.asList(new Camera.Size(640,480));eq(CameraAutoOptimizer.recorderProfile(0,1,1,p),p480,"reject unsupported video dimensions");
  p.video=null;p.previews=Arrays.asList(new Camera.Size(1280,720));eq(CameraAutoOptimizer.recorderProfile(0,1,1,p),p720,"documented preview fallback");
  p.video=null;p.previews=Arrays.asList(new Camera.Size(1280,720),new Camera.Size(640,480));p720.videoFrameRate=60;
  eq(CameraAutoOptimizer.recorderProfile(0,1,1,p),p480,"skip high-speed profile outside 30 FPS budget");p720.videoFrameRate=30;
  p.video=Arrays.asList(new Camera.Size(1,1));boolean threw=false;try{CameraAutoOptimizer.recorderProfile(0,1,1,p);}catch(IllegalStateException e){threw=true;}ok(threw,"no fake video profile");
 }
 @SuppressWarnings("unchecked") static void camera2()throws Exception{
  reset();Camera2AutoOptimizer o=new Camera2AutoOptimizer();CameraCaptureSession s=new CameraCaptureSession();CameraCharacteristics c=metadata();repeat(o,s,c,false);
  eq(s.last.get(CaptureRequest.CONTROL_AF_MODE),3,"camera2 video AF");eq(s.last.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE).getLower(),30,"camera2 supported fixed FPS");
  eq(s.last.get(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE),1,"prefer OIS");eq(s.last.get(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE),0,"no double stabilization");
  eq(s.last.get(CaptureRequest.FLASH_MODE),2,"camera2 flash preserved");eq(s.last.get(CaptureRequest.JPEG_ORIENTATION),90,"camera2 orientation preserved");
  ok(!ApplicationLoader.applicationContext.prefs.data.containsKey(state2("video").key+".accepted"),"submission is not validation");s.complete();ok(ApplicationLoader.applicationContext.prefs.data.containsKey(state2("video").key+".accepted"),"real result validates profile");
  reset();c=metadata();c.put(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION,new int[]{0});s=new CameraCaptureSession();repeat(o,s,c,false);eq(s.last.get(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE),1,"EIS when no OIS");
  reset();c=metadata();c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).duration=41666667;s=new CameraCaptureSession();repeat(o,s,c,false);eq(s.last.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE).getUpper(),24,"min frame duration caps FPS");
  reset();c=metadata();c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).duration=33333334;s=new CameraCaptureSession();repeat(o,s,c,false);eq(s.last.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE).getUpper(),30,"nanosecond rounding retains 30 FPS");
  reset();c=metadata();c.keys=Collections.emptyList();s=new CameraCaptureSession();repeat(o,s,c,false);eq(s.last.get(CaptureRequest.CONTROL_AF_MODE),null,"missing request keys left alone");
  reset();s=new CameraCaptureSession();repeat(o,s,metadata(),true);eq(s.last.get(CaptureRequest.CONTROL_AF_MODE),null,"night/barcode bypass");
  reset();s=new CameraCaptureSession();s.rejectOptimized=true;repeat(o,s,metadata(),false);eq(s.calls,2,"sync fallback once");ok(!state2("video").disabled(),"not disabled before fallback frame");s.complete();ok(state2("video").disabled(),"fallback frame confirms blacklist");
  s.rejectOptimized=false;repeat(o,s,metadata(),false);eq(s.last.get(CaptureRequest.CONTROL_AF_MODE),null,"cached camera2 fallback");
  reset();s=new CameraCaptureSession();s.rejectAll=true;boolean threw=false;try{repeat(o,s,metadata(),false);}catch(CameraAccessException e){threw=true;}ok(threw,"unavailable camera propagates");ok(!state2("video").disabled(),"unavailable camera does not poison cache");
  reset();s=new CameraCaptureSession();repeat(o,s,metadata(),false);for(int i=0;i<5;i++)s.fail(CaptureFailure.REASON_FLUSHED);eq(s.calls,1,"flushed frames ignored");s.fail(0);s.fail(0);eq(s.calls,1,"two errors no retry");s.fail(0);eq(s.calls,2,"three errors one retry");s.complete();ok(state2("video").disabled(),"async fallback validated");for(int i=0;i<5;i++)s.fail(0);eq(s.calls,2,"bounded retry");
  reset();s=new CameraCaptureSession();repeat(o,s,metadata(),false);CameraCaptureSession.CaptureCallback oldCallback=s.callback;CaptureRequest oldRequest=s.last;repeat(o,s,metadata(),false);for(int i=0;i<5;i++)oldCallback.onCaptureFailed(s,oldRequest,new CaptureFailure(0));eq(s.calls,2,"stale request cannot downgrade replacement");
  o.stop();s.complete();ok(!ApplicationLoader.applicationContext.prefs.data.containsKey(state2("video").key+".accepted"),"closed session ignores late frames");
  reset();c=metadata();c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).broken=true;s=new CameraCaptureSession();repeat(o,s,c,false);eq(s.last.get(CaptureRequest.CONTROL_AF_MODE),3,"optional duration query failure does not disable other capabilities");s.complete();ok(!state2("video").disabled(),"missing optional metadata does not poison cache");
  reset();c=metadata();c.put(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES,new Range[]{new Range<>(15000,30000),new Range<>(30000,30000)});s=new CameraCaptureSession();repeat(o,s,c,false);eq(s.last.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE).getLower(),30000,"Camera2 millifps preserved in request");
  reset();c=metadata();c.put(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES,new Range[]{new Range<>(30,60)});s=new CameraCaptureSession();repeat(o,s,c,false);eq(s.last.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE),null,"only out-of-budget ranges keep template default");
  reset();s=new CameraCaptureSession();o.capture(s,builder(),metadata(),new Size(1280,720),"0",false,false,new Handler());eq(s.last.get(CaptureRequest.CONTROL_AF_MODE),4,"still photo AF");eq(s.last.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE),null,"still no artificial exposure FPS cap");s.fail(0);eq(s.captures,1,"no automatic duplicate photo");
  reset();c=metadata();c.put(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES,new int[]{CaptureRequest.CONTROL_AF_MODE_OFF,CaptureRequest.CONTROL_AF_MODE_AUTO});s=new CameraCaptureSession();repeat(o,s,c,false);
  eq(s.last.get(CaptureRequest.CONTROL_AF_MODE),null,"no AF_MODE_AUTO without a focus trigger");eq(s.last.get(CaptureRequest.CONTROL_AWB_MODE),1,"missing continuous AF keeps other tuning");
  reset();c=metadata();c.put(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES,new int[]{CaptureRequest.CONTROL_AF_MODE_OFF});s=new CameraCaptureSession();o.capture(s,builder(),c,new Size(1280,720),"0",false,false,new Handler());eq(s.last.get(CaptureRequest.CONTROL_AF_MODE),null,"fixed-focus still keeps template focus");
  reset();s=new CameraCaptureSession();o.capture(s,builder(),metadata(),new Size(1280,720),"0",true,false,new Handler());eq(s.last.get(CaptureRequest.CONTROL_AF_MODE),3,"still during recording matches the video AF mode");
  ok(!state2("photo-still-recording").disabled(),"still-during-recording is cached separately");
 }
 public static void main(String[] args)throws Exception{policy();legacy();recorder();camera2();System.out.println("PASS: "+checks+" contract assertions");}
}
""")
source('ParseSources.java', """
import javax.tools.*;
import com.sun.source.util.JavacTask;
import java.util.*;
public class ParseSources {
 public static void main(String[] args)throws Exception{
  JavaCompiler compiler=ToolProvider.getSystemJavaCompiler();
  DiagnosticCollector<JavaFileObject> diagnostics=new DiagnosticCollector<>();
  try(StandardJavaFileManager files=compiler.getStandardFileManager(diagnostics,null,java.nio.charset.StandardCharsets.UTF_8)){
   JavacTask task=(JavacTask)compiler.getTask(null,files,diagnostics,Arrays.asList("-proc:none","-source","8"),null,files.getJavaFileObjectsFromStrings(Arrays.asList(args)));
   task.parse();
   boolean error=false;for(Diagnostic<?> d:diagnostics.getDiagnostics()){if(d.getKind()==Diagnostic.Kind.ERROR){System.err.println(d);error=true;}}
   if(error)throw new AssertionError("Java syntax errors");
   System.out.println("PASS: parsed "+args.length+" production Java files (syntax only)");
  }
 }
}
""")


def main():
    required = [CAMERA / name for name in ['CameraOptimizationPolicy.java', 'CameraAutoOptimizer.java', 'Camera2AutoOptimizer.java', 'CameraSession.java', 'CameraController.java', 'Camera2Session.java']]
    missing = [str(p) for p in required if not p.is_file()]
    if missing:
        raise SystemExit('FILES_MISSING: request latest archive; do not reconstruct: ' + ', '.join(missing))
    java = shutil.which('java')
    javac = shutil.which('javac')
    if not java:
        raise SystemExit('A JDK is required')
    compiler = [javac] if javac else [java, '-m', 'jdk.compiler/com.sun.tools.javac.Main']
    with tempfile.TemporaryDirectory(prefix='camera-opt-tests-') as tmp:
        tmp = Path(tmp)
        for name, content in SOURCES.items():
            path = tmp / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding='utf-8')
        test_sources = [str(tmp / name) for name in SOURCES if name != 'ParseSources.java']
        common = compiler + ['-encoding', 'UTF-8', '-source', '8', '-target', '8', '-Xlint:-options', '-d', str(tmp / 'classes')]
        subprocess.run(common + test_sources + list(map(str, required[:3])), check=True)
        subprocess.run([java, '-ea', '-cp', str(tmp / 'classes'), 'org.telegram.messenger.camera.CameraOptimizerContractTest'], check=True)
        subprocess.run(common + [str(tmp / 'ParseSources.java')], check=True)
        subprocess.run([java, '-cp', str(tmp / 'classes'), 'ParseSources'] + list(map(str, required)), check=True)
    # Integration assertions complement the mocks; they do not assert Android runtime behavior.
    camera2 = (CAMERA / 'Camera2Session.java').read_text(encoding='utf-8')
    assert 'new Range<Integer>(30, 60)' not in camera2
    assert 'captureSession.setRepeatingRequest(' not in camera2
    assert 'autoOptimizer.repeating(' in camera2 and 'autoOptimizer.capture(' in camera2
    assert 'Looper.myLooper() != handler.getLooper()' in camera2
    assert 'import android.util.Range;' not in camera2
    assert camera2.count('recordingVideo, scanningBarcode || nightMode, handler);') == 2
    assert 'handler.post(this::updateCaptureRequest); // Deferred, not failed.' in camera2
    optimizer = (CAMERA / 'Camera2AutoOptimizer.java').read_text(encoding='utf-8')
    # Qualified constants only: the surrounding comment mentions the bare names.
    assert 'CaptureRequest.CONTROL_AF_MODE_AUTO' not in optimizer
    assert 'CaptureRequest.CONTROL_AF_MODE_OFF' not in optimizer
    assert 'boolean video, boolean bypass, Handler handler) throws CameraAccessException' in optimizer
    legacy = (CAMERA / 'CameraSession.java').read_text(encoding='utf-8')
    assert legacy.count('CameraAutoOptimizer.applyLegacy(') == 2
    assert 'setRecordingHint(isVideo)' not in legacy
    # Qualified call sites only: the surrounding comment mentions the bare name.
    assert legacy.count('params.setRecordingHint(') == 1  # configureRoundCamera, pre-startPreview
    assert 'params.setRecordingHint(true);' in legacy
    controller = (CAMERA / 'CameraController.java').read_text(encoding='utf-8')
    assert 'session.configureRecorder(1, recorder, recordingParameters)' in controller
    assert 'if (!CameraAutoOptimizer.isEnabled()) {' in controller
    for path in required + [ROOT / 'gradle.properties', Path(__file__)]:
        assert '\ufffd' not in path.read_text(encoding='utf-8'), str(path)
    print('PASS: integration anchors and UTF-8/FFFD=0')
    print('NOT RUN: Android SDK build, real HAL/camera, recording quality/FPS, power/thermal tests')

if __name__ == '__main__':
    main()
