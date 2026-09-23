package com.example.ballrunbot;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.*;
import android.view.accessibility.AccessibilityEvent;

public class BallRunAccessibilityService extends AccessibilityService {
    private static volatile boolean enabled=false;
    private Handler handler; private boolean gestureInFlight=false; private long lastGesture=0;
    public static void enableController(boolean on){ enabled=on; }
    @Override protected void onServiceConnected(){ super.onServiceConnected(); handler=new Handler(Looper.getMainLooper()); handler.post(loop); }
    private final Runnable loop=new Runnable(){ public void run(){
        if(handler==null)return;
        if(!enabled){handler.postDelayed(this,250);return;}
        android.content.SharedPreferences p=getSharedPreferences("bot",MODE_PRIVATE);
        boolean running=p.getBoolean("bot_running",false), capture=p.getBoolean("capture_active",false);
        String cmd=p.getString("command","NONE"); long now=SystemClock.uptimeMillis();
        if(!running||!capture||!(cmd.equals("LEFT")||cmd.equals("RIGHT"))){handler.postDelayed(this,120);return;}
        if(!gestureInFlight&&now-lastGesture>=320){
            float w=getResources().getDisplayMetrics().widthPixels,h=getResources().getDisplayMetrics().heightPixels;
            float cx=w/2f,y=h*.82f,dx=cmd.equals("LEFT")?-80f:80f;
            sendDrag(cx,y,cx+dx,y); lastGesture=now; p.edit().putString("command","NONE").apply();
        }
        handler.postDelayed(this,120);
    }};
    private void sendDrag(float x1,float y1,float x2,float y2){
        Path path=new Path();path.moveTo(x1,y1);path.lineTo(x2,y2);
        GestureDescription.StrokeDescription s=new GestureDescription.StrokeDescription(path,0,120);
        gestureInFlight=true;
        boolean ok=dispatchGesture(new GestureDescription.Builder().addStroke(s).build(),new GestureResultCallback(){
            @Override public void onCompleted(GestureDescription g){gestureInFlight=false;}
            @Override public void onCancelled(GestureDescription g){gestureInFlight=false;}
        },null);
        if(!ok)gestureInFlight=false;
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent e){}
    @Override public void onInterrupt(){enabled=false;if(handler!=null)handler.removeCallbacks(loop);getSharedPreferences("bot",MODE_PRIVATE).edit().putBoolean("bot_running",false).putString("command","NONE").apply();}
    @Override public void onDestroy(){enabled=false;if(handler!=null)handler.removeCallbacksAndMessages(null);getSharedPreferences("bot",MODE_PRIVATE).edit().putBoolean("bot_running",false).putString("command","NONE").apply();super.onDestroy();}
}