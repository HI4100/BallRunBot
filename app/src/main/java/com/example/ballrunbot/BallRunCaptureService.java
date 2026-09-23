package com.example.ballrunbot;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.hardware.display.*;
import android.media.*;
import android.media.projection.MediaProjection;
import android.os.*;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicBoolean;

public class BallRunCaptureService extends Service {
    private MediaProjection projection;
    private ImageReader reader;
    private VirtualDisplay display;
    private SharedPreferences prefs;
    private long frames, lastProcess;
    private float ballX = -1f;
    private float roadCenter = -1f;
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private static final int STEP = 5;

    private static final class C {
        int area,minX,maxX,minY,maxY;
        float cx,cy;
    }
    private static final class Segment {
        float left,right;
        Segment(float l,float r){left=l;right=r;}
        float center(){return (left+right)*.5f;}
        float width(){return right-left;}
    }
    private static final class RoadPoint {
        float y,left,right;
        RoadPoint(float yy,float l,float r){y=yy;left=l;right=r;}
    }
    private static final class Gap {
        float left,right;
        Gap(float l,float r){left=l;right=r;}
        float center(){return (left+right)*.5f;}
        float width(){return right-left;}
    }

    @Override public void onCreate(){
        super.onCreate();
        prefs=getSharedPreferences("bot",MODE_PRIVATE);
        if(Build.VERSION.SDK_INT>=26)
            getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel("capture","Ball Run Bot",NotificationManager.IMPORTANCE_LOW));
    }

    private Notification note(String s){
        Notification.Builder b=Build.VERSION.SDK_INT>=26
            ?new Notification.Builder(this,"capture"):new Notification.Builder(this);
        return b.setContentTitle("Ball Run Bot").setContentText(s)
            .setSmallIcon(android.R.drawable.ic_media_play).setOngoing(true).build();
    }

    @Override public int onStartCommand(Intent in,int flags,int id){
        if(in==null)return START_NOT_STICKY;
        int rc=in.getIntExtra("resultCode",Activity.RESULT_CANCELED);
        Intent data=in.getParcelableExtra("data");
        if(rc!=Activity.RESULT_OK||data==null){fail("Capture permission missing");return START_NOT_STICKY;}
        if(Build.VERSION.SDK_INT>=29)
            startForeground(10,note("Screen capture active"),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        else startForeground(10,note("Screen capture active"));
        android.media.projection.MediaProjectionManager m=
            (android.media.projection.MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
        projection=m.getMediaProjection(rc,data);
        if(projection==null){fail("Capture permission failed");return START_NOT_STICKY;}
        projection.registerCallback(new MediaProjection.Callback(){
            @Override public void onStop(){fail("Screen capture stopped");}
        },new Handler(Looper.getMainLooper()));
        createDisplay();
        return START_NOT_STICKY;
    }

    private void fail(String s){
        prefs.edit().putBoolean("capture_active",false).putBoolean("bot_running",false)
            .putString("command","NONE").putLong("command_until",0)
            .putString("vision_detail",s).apply();
        stopSelf();
    }

    private void createDisplay(){
        android.util.DisplayMetrics dm=getResources().getDisplayMetrics();
        int w=dm.widthPixels,h=dm.heightPixels;
        reader=ImageReader.newInstance(w,h,PixelFormat.RGBA_8888,2);
        reader.setOnImageAvailableListener(r->{
            Image im=null;
            try{
                im=r.acquireLatestImage();
                if(im==null||busy.get())return;
                busy.set(true);frames++;
                prefs.edit().putLong("frames",frames).putBoolean("capture_active",true).apply();
                long now=SystemClock.uptimeMillis();
                if(now-lastProcess>=90){lastProcess=now;analyze(im);}
            }catch(Throwable e){clear("VISION ERROR");}
            finally{if(im!=null)im.close();busy.set(false);}
        },new Handler(Looper.getMainLooper()));
        display=projection.createVirtualDisplay("BallRunBot",w,h,dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader.getSurface(),null,null);
    }

    // Ball: the game ball is saturated pink/magenta and is the only large
    // component in the lower half. This deliberately does NOT classify every
    // pink pixel as a ball.
    private boolean ballPixel(ByteBuffer b,int lim,int row,int pix,int x,int y){
        int i=y*row+x*pix;if(i<0||i+3>=lim)return false;
        int r=b.get(i)&255,g=b.get(i+1)&255,bl=b.get(i+2)&255;
        int mx=Math.max(r,Math.max(g,bl)),mn=Math.min(r,Math.min(g,bl));
        return mx>=115&&mx-mn>=65&&r>=g+70&&bl>=g+45;
    }

    // From the supplied gameplay: lethal blocks are a different, brighter
    // violet/purple hue than the ball. Detect that hue only, then require it
    // to physically sit inside the detected road corridor.
    private boolean obstaclePixel(ByteBuffer b,int lim,int row,int pix,int x,int y){
        int i=y*row+x*pix;if(i<0||i+3>=lim)return false;
        int r=b.get(i)&255,g=b.get(i+1)&255,bl=b.get(i+2)&255;
        int mx=Math.max(r,Math.max(g,bl)),mn=Math.min(r,Math.min(g,bl));
        if(mx<125||mx-mn<75||bl<r+15||r<g+75)return false;
        // approximate OpenCV hue 135..150 without floating-point HSV
        int max=mx,min=mn,d=max-min;
        int hue;
        if(max==r) hue=(60*(g-bl)/d+360)%360;
        else if(max==g) hue=60*(bl-r)/d+120;
        else hue=60*(r-g)/d+240;
        return hue>=135&&hue<=152;
    }

    private ArrayList<C> components(ByteBuffer b,int lim,int row,int pix,
                                    int w,int y0,int y1,boolean obstacle){
        int gx=(w+STEP-1)/STEP;
        int rows=Math.max(1,(int)Math.ceil((y1-y0)/(float)STEP));
        boolean[] m=new boolean[gx*rows];
        for(int gy=0;gy<rows;gy++){
            int y=Math.min(y1-1,y0+gy*STEP);
            for(int xg=0;xg<gx;xg++){
                int x=xg*STEP;
                if(x<w&&(obstacle?obstaclePixel(b,lim,row,pix,x,y):ballPixel(b,lim,row,pix,x,y)))
                    m[gy*gx+xg]=true;
            }
        }
        boolean[] seen=new boolean[m.length];
        int[] q=new int[m.length];
        ArrayList<C> out=new ArrayList<>();
        for(int z=0;z<m.length;z++){
            if(!m[z]||seen[z])continue;
            int head=0,tail=0;q[tail++]=z;seen[z]=true;
            int area=0,minX=gx,maxX=0,minY=rows,maxY=0;long sx=0,sy=0;
            while(head<tail){
                int p=q[head++],xg=p%gx,yg=p/gx,px=xg*STEP,py=y0+yg*STEP;
                area++;sx+=px;sy+=py;
                if(xg<minX)minX=xg;if(xg>maxX)maxX=xg;if(yg<minY)minY=yg;if(yg>maxY)maxY=yg;
                int a=p-1,c=p+1,u=p-gx,d=p+gx;
                if(xg>0&&m[a]&&!seen[a]){seen[a]=true;q[tail++]=a;}
                if(xg+1<gx&&m[c]&&!seen[c]){seen[c]=true;q[tail++]=c;}
                if(yg>0&&m[u]&&!seen[u]){seen[u]=true;q[tail++]=u;}
                if(yg+1<rows&&m[d]&&!seen[d]){seen[d]=true;q[tail++]=d;}
            }
            C c=new C();c.area=area;c.minX=minX*STEP;c.maxX=Math.min(w-1,(maxX+1)*STEP-1);
            c.minY=y0+minY*STEP;c.maxY=Math.min(y1-1,y0+(maxY+1)*STEP-1);
            c.cx=sx/(float)area;c.cy=sy/(float)area;out.add(c);
        }
        return out;
    }

    private float findBall(ByteBuffer b,int lim,int row,int pix,int w,int h){
        ArrayList<C> cs=components(b,lim,row,pix,w,(int)(h*.55f),(int)(h*.90f),false);
        C best=null;
        for(C c:cs){
            int ww=c.maxX-c.minX+1,hh=c.maxY-c.minY+1;
            float ratio=(float)ww/Math.max(1,hh);
            if(c.area<800||c.area>90000||ww<35||hh<35||ratio<.55f||ratio>1.8f)continue;
            if(best==null||c.area>best.area)best=c;
        }
        return best==null?-1:best.cx;
    }

    // The track is the bright blue/purple surface between the black voids.
    // We trace the central road from the ball upward, row by row. Side walls
    // are disconnected from this central segment by the black background.
    private ArrayList<RoadPoint> roadProfile(ByteBuffer b,int lim,int row,int pix,
                                             int w,int h,float expectedX){
        ArrayList<RoadPoint> pts=new ArrayList<>();
        float prev=expectedX;
        for(int k=0;k<10;k++){
            int y=(int)(h*(.76f-k*.045f));
            if(y< h*.40f)break;
            ArrayList<Segment> segs=rowSegments(b,lim,row,pix,w,y);
            Segment best=null;float bd=Float.MAX_VALUE;
            for(Segment s:segs){
                if(s.width()<28)continue;
                float d;
                if(prev>=s.left&&prev<=s.right)d=0;
                else d=Math.min(Math.abs(prev-s.left),Math.abs(prev-s.right));
                // Reject giant side-wall segments unless they are the only
                // plausible continuation of the previous road.
                if(s.width()>w*.92f&&d>20)continue;
                if(d<bd){bd=d;best=s;}
            }
            if(best!=null){
                pts.add(new RoadPoint(y,best.left,best.right));
                prev=best.center();
            }
        }
        Collections.sort(pts,Comparator.comparingDouble(a->a.y));
        return pts;
    }

    private ArrayList<Segment> rowSegments(ByteBuffer b,int lim,int row,int pix,int w,int y){
        boolean[] on=new boolean[w];
        for(int x=0;x<w;x++){
            int i=y*row+x*pix;if(i<0||i+2>=lim)continue;
            int bl=b.get(i)&255,g=b.get(i+1)&255,r=b.get(i+2)&255;
            on[x]=(bl>42&&bl>r+10&&bl>g+22);
        }
        // Close small gaps made by the grid lines.
        for(int pass=0;pass<2;pass++){
            int gap=8;
            int s=-1;
            for(int x=0;x<=w;x++){
                boolean v=x<w&&on[x];
                if(v&&s<0)s=x;
                if((!v||x==w)&&s>=0){
                    int e=x-1;
                    if(x<w && e-s<gap){
                        int j=x;while(j<w&&!on[j]&&j-x<gap)j++;
                        if(j<w){for(int q=x;q<j;q++)on[q]=true;x=j-1;}
                    }
                    if(x==w)s=-1;
                }
            }
        }
        ArrayList<Segment> out=new ArrayList<>();
        int s=-1;
        for(int x=0;x<=w;x++){
            boolean v=x<w&&on[x];
            if(v&&s<0)s=x;
            if((!v||x==w)&&s>=0){
                int e=x-1;if(e-s+1>=20)out.add(new Segment(s,e));s=-1;
            }
        }
        return out;
    }

    private float roadLeft(ArrayList<RoadPoint> p,float y,float fallback){
        if(p.isEmpty())return Math.max(0,fallback);
        RoadPoint a=p.get(0),z=p.get(p.size()-1);
        if(y<=a.y)return a.left;
        if(y>=z.y)return z.left;
        for(int i=1;i<p.size();i++){
            RoadPoint b=p.get(i-1),c=p.get(i);
            if(y<=c.y){
                float t=(y-b.y)/Math.max(1,c.y-b.y);
                return b.left+(c.left-b.left)*t;
            }
        }
        return z.left;
    }
    private float roadRight(ArrayList<RoadPoint> p,float y,float fallback){
        if(p.isEmpty())return Math.min(fallback,1e9f);
        RoadPoint a=p.get(0),z=p.get(p.size()-1);
        if(y<=a.y)return a.right;
        if(y>=z.y)return z.right;
        for(int i=1;i<p.size();i++){
            RoadPoint b=p.get(i-1),c=p.get(i);
            if(y<=c.y){
                float t=(y-b.y)/Math.max(1,c.y-b.y);
                return b.right+(c.right-b.right)*t;
            }
        }
        return z.right;
    }

    private ArrayList<C> findObstacles(ByteBuffer b,int lim,int row,int pix,int w,int h,
                                       ArrayList<RoadPoint> road){
        ArrayList<C> raw=components(b,lim,row,pix,w,(int)(h*.28f),(int)(h*.72f),true);
        ArrayList<C> out=new ArrayList<>();
        for(C o:raw){
            int ww=o.maxX-o.minX+1,hh=o.maxY-o.minY+1;
            float ratio=(float)ww/Math.max(1,hh);
            float left=roadLeft(road,o.cy,o.cx),right=roadRight(road,o.cy,o.cx);
            float overlap=Math.max(0,Math.min(right,o.maxX)-Math.max(left,o.minX));
            if(o.area<30||o.area>12000||ww<10||hh<8||ww>260||hh>260)continue;
            if(ratio<.25f||ratio>4.0f)continue;
            if(right-left<35||overlap<Math.max(8,ww*.35f))continue;
            out.add(o);
        }
        return out;
    }

    private float mapAtBottom(ArrayList<RoadPoint> road,float y,float x,float bottomY){
        float l=roadLeft(road,y,x),r=roadRight(road,y,x);
        float bl=roadLeft(road,bottomY,x),br=roadRight(road,bottomY,x);
        float n=(x-l)/Math.max(1,r-l);
        n=Math.max(.04f,Math.min(.96f,n));
        return bl+n*(br-bl);
    }

    private void plan(ByteBuffer b,int lim,int row,int pix,int w,int h,float bx,
                      ArrayList<RoadPoint> road,ArrayList<C> obs){
        if(road.isEmpty()){
            clear("TRACK LOST • STOP STEERING");return;
        }
        float bottomY=h*.76f;
        float bl=roadLeft(road,bottomY,bx),br=roadRight(road,bottomY,bx);
        if(br-bl<40){clear("TRACK TOO NARROW • HOLD");return;}

        // First priority is remaining on the track. If there is no immediate
        // lethal block, gently return toward the current road center.
        float target=bl+(br-bl)*.5f;
        String detail="TRACK "+(int)bl+"-"+(int)br+" • BALL "+(int)bx;

        if(!obs.isEmpty()){
            // Consider the nearest obstacle in travel direction, but account
            // for ALL blocks at nearly the same depth as a gate.
            float nearest=Float.MAX_VALUE;
            for(C o:obs)if(o.cy<nearest&&o.cy>h*.36f)nearest=o.cy;
            ArrayList<C> gate=new ArrayList<>();
            for(C o:obs)if(Math.abs(o.cy-nearest)<Math.max(45,h*.045f))gate.add(o);

            float gl=roadLeft(road,nearest,bx),gr=roadRight(road,nearest,bx);
            ArrayList<float[]> blocked=new ArrayList<>();
            for(C o:gate){
                float pad=Math.max(10,Math.min(26,(o.maxY-o.minY+o.maxX-o.minX)*.08f));
                blocked.add(new float[]{Math.max(gl,o.minX-pad),Math.min(gr,o.maxX+pad)});
            }
            blocked.sort(Comparator.comparingDouble(a->a[0]));
            ArrayList<float[]> merged=new ArrayList<>();
            for(float[] q:blocked){
                if(q[1]<=q[0])continue;
                if(merged.isEmpty()||q[0]>merged.get(merged.size()-1)[1]+3)
                    merged.add(new float[]{q[0],q[1]});
                else
                    merged.get(merged.size()-1)[1]=Math.max(merged.get(merged.size()-1)[1],q[1]);
            }

            ArrayList<Gap> gaps=new ArrayList<>();
            float cur=gl;
            for(float[] q:merged){
                if(q[0]-cur>=18)gaps.add(new Gap(cur,q[0]));
                cur=Math.max(cur,q[1]);
            }
            if(gr-cur>=18)gaps.add(new Gap(cur,gr));

            Gap best=null;float score=Float.MAX_VALUE;
            for(Gap g:gaps){
                if(g.width()<22)continue;
                float tx=mapAtBottom(road,nearest,g.center(),bottomY);
                float s=Math.abs(tx-bx);
                // Prefer wide corridors, but never trade away track safety.
                s+=Math.max(0,50-g.width())*1.8f;
                if(s<score){score=s;best=g;}
            }
            if(best!=null){
                target=mapAtBottom(road,nearest,best.center(),bottomY);
                detail="OBSTACLE "+gate.size()+" • SAFE "+(int)target;
            }else{
                // No safe gap at the upcoming gate: do not fling the ball.
                clear("NO SAFE GAP • HOLD");
                return;
            }
        }

        float err=target-bx;
        float edgeMargin=Math.max(24,w*.055f);
        if(bx<bl+edgeMargin){target=Math.max(target,bl+edgeMargin);}
        if(bx>br-edgeMargin){target=Math.min(target,br-edgeMargin);}
        err=target-bx;

        float dead=Math.max(10,w*.018f);
        if(Math.abs(err)<=dead){
            clear(detail+" • HOLD");return;
        }

        // Small closed-loop drags. The old controller used up to ~13% of the
        // screen width per swipe; that is far too aggressive for this game.
        float delta=Math.max(w*.025f,Math.min(w*.065f,Math.abs(err)*.20f));
        long duration=85;
        String cmd=err<0?"LEFT":"RIGHT";
        prefs.edit().putString("command",cmd)
            .putFloat("steer_delta",delta).putLong("steer_duration",duration)
            .putFloat("steer_target_x",target)
            .putLong("command_until",SystemClock.uptimeMillis()+155)
            .putString("vision_detail",detail+" • "+cmd+" "+(int)target).apply();
    }

    private void clear(String s){
        prefs.edit().putString("command","NONE").putLong("command_until",0)
            .putString("vision_detail",s).apply();
    }

    private void analyze(Image im){
        Image.Plane p=im.getPlanes()[0];ByteBuffer b=p.getBuffer();
        int row=p.getRowStride(),pix=p.getPixelStride(),w=im.getWidth(),h=im.getHeight(),lim=b.limit();

        float detected=findBall(b,lim,row,pix,w,h);
        if(detected<0){clear("NO BALL • BOT HOLD");return;}
        if(ballX<0)ballX=detected;
        else{
            float jump=Math.abs(detected-ballX);
            if(jump>w*.20f){clear("BALL TRACK LOST • HOLD");return;}
            ballX=ballX*.72f+detected*.28f;
        }

        ArrayList<RoadPoint> road=roadProfile(b,lim,row,pix,w,h,ballX);
        if(road.isEmpty()){clear("TRACK LOST • BOT HOLD");return;}
        roadCenter=(road.get(road.size()-1).left+road.get(road.size()-1).right)*.5f;

        prefs.edit().putFloat("player_x",ballX)
            .putFloat("player_y",h*.70f).apply();

        ArrayList<C> obs=findObstacles(b,lim,row,pix,w,h,road);
        plan(b,lim,row,pix,w,h,ballX,road,obs);
    }

    @Override public void onDestroy(){
        prefs.edit().putBoolean("capture_active",false).putBoolean("bot_running",false)
            .putString("command","NONE").putLong("command_until",0).apply();
        if(display!=null)display.release();
        if(reader!=null)reader.close();
        if(projection!=null)projection.stop();
        super.onDestroy();
    }
    @Override public android.os.IBinder onBind(Intent i){return null;}
}