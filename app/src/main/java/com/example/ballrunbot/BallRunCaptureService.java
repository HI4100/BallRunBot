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
import java.util.concurrent.atomic.AtomicBoolean;

public class BallRunCaptureService extends Service {
    private MediaProjection projection;
    private ImageReader reader;
    private VirtualDisplay display;
    private SharedPreferences prefs;
    private long frames, lastProcess;
    private float ballX = -1f;
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private static final int STEP = 6;

    private static final class C {
        int area,minX,maxX,minY,maxY;
        float cx,cy,fill,ratio,meanV;
    }
    private static final class Gap {
        float left,right,center,width;
        Gap(float l,float r){left=l;right=r;center=(l+r)/2f;width=r-l;}
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
                if(now-lastProcess>=100){lastProcess=now;analyze(im);}
            }catch(Throwable e){clear("VISION ERROR");}
            finally{if(im!=null)im.close();busy.set(false);}
        },new Handler(Looper.getMainLooper()));
        display=projection.createVirtualDisplay("BallRunBot",w,h,dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader.getSurface(),null,null);
    }

    private boolean pink(ByteBuffer b,int lim,int row,int pix,int x,int y){
        int i=y*row+x*pix;if(i<0||i+3>=lim)return false;
        int r=b.get(i)&255,g=b.get(i+1)&255,bl=b.get(i+2)&255;
        int mx=Math.max(r,Math.max(g,bl)),mn=Math.min(r,Math.min(g,bl));
        return mx>=145&&mx-mn>=60&&r>=g+55&&bl>=g+30&&r+bl>=310;
    }

    private int score(ByteBuffer b,int lim,int row,int pix,int x,int y){
        int i=y*row+x*pix;if(i<0||i+2>=lim)return 0;
        int r=b.get(i)&255,g=b.get(i+1)&255,bl=b.get(i+2)&255;
        if(r<90||bl<70||r<g+35||bl<g+15)return 0;
        return Math.max(0,(r-g)+(bl-g));
    }

    private float findBall(ByteBuffer b,int lim,int row,int pix,int w,int h){
        int y0=(int)(h*.57f),y1=(int)(h*.82f);
        int expected=ballX<0?w/2:Math.round(ballX);
        int half=ballX<0?w:Math.min(150,Math.round(w*.27f));
        int lo=Math.max(0,expected-half),hi=Math.min(w-1,expected+half);
        int best=expected,bestScore=0;
        for(int x=lo;x<=hi;x+=STEP){
            int s=0;
            for(int y=y0;y<=y1;y+=STEP)s+=score(b,lim,row,pix,x,y);
            if(s>bestScore){bestScore=s;best=x;}
        }
        if(bestScore<180)return -1;
        long total=0,weighted=0;
        for(int x=Math.max(lo,best-24);x<=Math.min(hi,best+24);x+=3){
            int s=0;
            for(int y=y0;y<=y1;y+=STEP)s+=score(b,lim,row,pix,x,y);
            total+=s;weighted+=(long)s*x;
        }
        return total>0?(float)weighted/total:best;
    }

    private ArrayList<C> obstacles(ByteBuffer b,int lim,int row,int pix,int w,int h){
        int sy=(int)(h*.24f),ey=(int)(h*.70f);
        int gx=(w+STEP-1)/STEP,rows=(int)Math.ceil((ey-sy)/(float)STEP);
        boolean[] m=new boolean[gx*rows];
        for(int gy=0;gy<rows;gy++){
            int y=sy+gy*STEP;
            for(int xg=0;xg<gx;xg++){
                int x=xg*STEP;
                if(x<w&&pink(b,lim,row,pix,x,y))m[gy*gx+xg]=true;
            }
        }
        boolean[] seen=new boolean[m.length];int[] q=new int[m.length];
        ArrayList<C> out=new ArrayList<>();
        for(int z=0;z<m.length;z++){
            if(!m[z]||seen[z])continue;
            int head=0,tail=0;q[tail++]=z;seen[z]=true;
            int area=0,minX=gx,maxX=0,minY=rows,maxY=0;long sx=0,syy=0,sv=0;
            while(head<tail){
                int p=q[head++],xg=p%gx,yg=p/gx,px=xg*STEP,py=sy+yg*STEP;
                area++;sx+=px;syy+=py;
                int bi=py*row+px*pix;
                if(bi>=0&&bi+2<lim){
                    int r=b.get(bi)&255,g=b.get(bi+1)&255,bl=b.get(bi+2)&255;
                    sv+=Math.max(r,Math.max(g,bl));
                }
                if(xg<minX)minX=xg;if(xg>maxX)maxX=xg;if(yg<minY)minY=yg;if(yg>maxY)maxY=yg;
                int a=p-1,c=p+1,u=p-gx,d=p+gx;
                if(xg>0&&m[a]&&!seen[a]){seen[a]=true;q[tail++]=a;}
                if(xg+1<gx&&m[c]&&!seen[c]){seen[c]=true;q[tail++]=c;}
                if(yg>0&&m[u]&&!seen[u]){seen[u]=true;q[tail++]=u;}
                if(yg+1<rows&&m[d]&&!seen[d]){seen[d]=true;q[tail++]=d;}
            }
            int ww=(maxX-minX+1)*STEP,hh=(maxY-minY+1)*STEP;
            float ratio=hh==0?99:(float)ww/hh;
            float fill=(float)area/Math.max(1,(maxX-minX+1)*(maxY-minY+1));
            float cy=syy/(float)Math.max(1,area),mv=sv/(float)Math.max(1,area);
            if(area<25||area>8000||ww<16||hh<16||ww>230||hh>230)continue;
            if(ratio<.50f||ratio>1.75f||fill<.55f||mv<215||cy<h*.25f||cy>h*.70f)continue;
            if(cy>h*.52f&&area>5000)continue;
            C o=new C();o.area=area;o.minX=minX*STEP;o.maxX=Math.min(w-1,(maxX+1)*STEP-1);
            o.minY=sy+minY*STEP;o.maxY=Math.min(h-1,sy+(maxY+1)*STEP-1);
            o.cx=sx/(float)area;o.cy=cy;o.fill=fill;o.ratio=ratio;o.meanV=mv;out.add(o);
        }
        return out;
    }

    private Gap safeGap(ArrayList<C> os,float bx,int w){
        float L=w*.12f,R=w*.88f;
        ArrayList<float[]> rs=new ArrayList<>();
        for(C o:os){
            float pad=Math.max(10,Math.min(26,Math.min(o.maxX-o.minX,o.maxY-o.minY)*.22f));
            float a=Math.max(L,o.minX-pad),bb=Math.min(R,o.maxX+pad);
            if(bb>a)rs.add(new float[]{a,bb});
        }
        if(rs.isEmpty())return null;
        rs.sort((a,b)->Float.compare(a[0],b[0]));
        ArrayList<float[]> merged=new ArrayList<>();
        for(float[] r:rs){
            if(merged.isEmpty()||r[0]>merged.get(merged.size()-1)[1]+2)merged.add(new float[]{r[0],r[1]});
            else{float[] last=merged.get(merged.size()-1);last[1]=Math.max(last[1],r[1]);}
        }
        ArrayList<Gap> gaps=new ArrayList<>();float cur=L;
        for(float[] r:merged){if(r[0]-cur>=15)gaps.add(new Gap(cur,r[0]));cur=Math.max(cur,r[1]);}
        if(R-cur>=15)gaps.add(new Gap(cur,R));
        if(gaps.isEmpty())return null;
        Gap best=null;float bs=Float.MAX_VALUE;
        for(Gap g:gaps){
            float s=Math.abs(g.center-bx)+Math.max(0,55-g.width)*3;
            if(g.width<22)s+=500;
            if(s<bs){bs=s;best=g;}
        }
        return best;
    }

    private void plan(ArrayList<C> os,int w,float bx){
        if(os.isEmpty()){clear("BALL x="+(int)bx+" • CLEAR");return;}
        Gap g=safeGap(os,bx,w);
        if(g==null){clear("BALL x="+(int)bx+" • NO SAFE GAP");return;}
        float err=g.center-bx;
        if(Math.abs(err)<=Math.max(18,w*.025f)){
            clear("NEON "+os.size()+" • BALL x="+(int)bx+" • SAFE x="+(int)g.center+" • HOLD");return;
        }
        float delta=Math.max(32,Math.min(w*.13f,Math.abs(err)*.34f));
        String cmd=err<0?"LEFT":"RIGHT";
        prefs.edit().putString("command",cmd).putFloat("steer_delta",delta)
            .putLong("steer_duration",125).putFloat("steer_target_x",g.center)
            .putLong("command_until",SystemClock.uptimeMillis()+190)
            .putString("vision_detail","NEON "+os.size()+" • BALL x="+(int)bx+
                " • SAFE x="+(int)g.center+" • "+cmd).apply();
    }

    private void clear(String s){
        prefs.edit().putString("command","NONE").putLong("command_until",0)
            .putString("vision_detail",s).apply();
    }

    private void analyze(Image im){
        Image.Plane p=im.getPlanes()[0];ByteBuffer b=p.getBuffer();
        int row=p.getRowStride(),pix=p.getPixelStride(),w=im.getWidth(),h=im.getHeight(),lim=b.limit();
        float detected=findBall(b,lim,row,pix,w,h);
        if(detected<0){clear("NO BALL • keep bot stopped");return;}
        if(ballX<0)ballX=detected;
        else{
            float jump=Math.abs(detected-ballX);
            if(jump>w*.22f){clear("BALL TRACK LOST • waiting");return;}
            ballX=ballX*.62f+detected*.38f;
        }
        prefs.edit().putFloat("player_x",ballX).putFloat("player_y",h*.70f).apply();
        plan(obstacles(b,lim,row,pix,w,h),w,ballX);
    }

    @Override public void onDestroy(){
        prefs.edit().putBoolean("capture_active",false).putBoolean("bot_running",false)
            .putString("command","NONE").putLong("command_until",0).apply();
        if(display!=null)display.release();if(reader!=null)reader.close();if(projection!=null)projection.stop();
        super.onDestroy();
    }
    @Override public android.os.IBinder onBind(Intent i){return null;}
}