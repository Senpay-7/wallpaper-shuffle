package app.wallpapershuffle;

import android.content.*;
import android.os.*;
import android.service.wallpaper.WallpaperService;
import android.view.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** The live wallpaper. The same carousel is shown on every screen the user chose in the system picker. */
public final class ShuffleWallpaperService extends WallpaperService {
    static final AtomicInteger installedEngines=new AtomicInteger();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Set<ShuffleEngine> engines=new HashSet<>();
    private AppController controller;
    private boolean interactive;
    private final Runnable changed=()->{for(ShuffleEngine e:new ArrayList<>(engines))e.refresh();schedule();};
    private final Runnable timer=()->{if(controller!=null&&interactive&&installed())controller.event("timer",System.currentTimeMillis());schedule();};
    private final BroadcastReceiver events=new BroadcastReceiver(){
        @Override public void onReceive(Context c,Intent i){String a=i.getAction();
            if(Intent.ACTION_SCREEN_OFF.equals(a)){interactive=false;main.removeCallbacks(timer);for(ShuffleEngine e:engines)e.renderer.visible(false);return;}
            initUnlocked();
            if(Intent.ACTION_SCREEN_ON.equals(a)){interactive=true;if(controller!=null&&installed())controller.event("timer",System.currentTimeMillis());}
            if(Intent.ACTION_USER_PRESENT.equals(a)){interactive=true;if(controller!=null&&installed())controller.event("unlock",System.currentTimeMillis());}
            changed.run();
        }
    };
    @Override public void onCreate(){super.onCreate();interactive=getSystemService(PowerManager.class).isInteractive();initUnlocked();IntentFilter f=new IntentFilter();f.addAction(Intent.ACTION_SCREEN_ON);f.addAction(Intent.ACTION_SCREEN_OFF);f.addAction(Intent.ACTION_USER_PRESENT);f.addAction(Intent.ACTION_USER_UNLOCKED);registerReceiver(events,f,Context.RECEIVER_EXPORTED);}
    private void initUnlocked(){if(controller==null&&getSystemService(UserManager.class).isUserUnlocked()){controller=AppController.get(this);controller.addListener(changed);if(installed())controller.activated();}}
    private boolean installed(){for(ShuffleEngine e:engines)if(!e.isPreview())return true;return false;}
    private void schedule(){main.removeCallbacks(timer);if(controller==null||!interactive||!installed())return;long now=System.currentTimeMillis(),d=controller.nextDeadline(now);if(d!=Long.MAX_VALUE)main.postDelayed(timer,Math.max(100,d-now));}
    @Override public Engine onCreateEngine(){return new ShuffleEngine();}
    @Override public void onDestroy(){unregisterReceiver(events);main.removeCallbacksAndMessages(null);if(controller!=null)controller.removeListener(changed);super.onDestroy();}

    final class ShuffleEngine extends Engine {
        final SceneRenderer renderer=new SceneRenderer(ShuffleWallpaperService.this,null);
        final TapSequence taps=new TapSequence();
        float downX,downY;long downAt;boolean moved,surface;
        final Runnable finishTap=()->{if(taps.finish()>0&&controller!=null&&!isPreview())controller.next();};
        @Override public void onCreate(SurfaceHolder holder){super.onCreate(holder);engines.add(this);if(!isPreview()){installedEngines.incrementAndGet();if(controller!=null)controller.activated();}renderer.snapshots(!isPreview());setTouchEventsEnabled(true);setOffsetNotificationsEnabled(false);schedule();}
        void refresh(){if(!surface)return;boolean shown=isVisible()&&interactive;
            if(controller==null)renderer.showBoot(MediaImages.readBoot(ShuffleWallpaperService.this,"main"),"main","crop",shown);
            else{if(!isPreview())controller.ensure();renderer.show(controller.current(),"main","crop",shown);}}
        @Override public void onSurfaceCreated(SurfaceHolder h){super.onSurfaceCreated(h);surface=true;renderer.surface(h.getSurface(),Math.max(1,h.getSurfaceFrame().width()),Math.max(1,h.getSurfaceFrame().height()));refresh();}
        @Override public void onSurfaceChanged(SurfaceHolder h,int format,int w,int ht){renderer.resize(w,ht);refresh();}
        @Override public void onSurfaceRedrawNeeded(SurfaceHolder h){refresh();}
        @Override public void onVisibilityChanged(boolean visible){if(visible){interactive=getSystemService(PowerManager.class).isInteractive();initUnlocked();if(controller!=null&&!isPreview())controller.event("timer",System.currentTimeMillis());}refresh();schedule();}
        @Override public void onSurfaceDestroyed(SurfaceHolder h){surface=false;renderer.destroySurface();super.onSurfaceDestroyed(h);}
        @Override public void onTouchEvent(MotionEvent e){
            if(controller==null||isPreview())return;float density=getResources().getDisplayMetrics().density;
            switch(e.getActionMasked()){
                case MotionEvent.ACTION_DOWN->{downX=e.getX();downY=e.getY();downAt=e.getEventTime();moved=false;}
                case MotionEvent.ACTION_MOVE->{if(Math.hypot(e.getX()-downX,e.getY()-downY)>18*density)moved=true;}
                case MotionEvent.ACTION_CANCEL->{taps.cancel();main.removeCallbacks(finishTap);}
                case MotionEvent.ACTION_UP->{main.removeCallbacks(finishTap);if(moved||e.getEventTime()-downAt>350){taps.cancel();return;}
                    int action=taps.tap(e.getEventTime(),e.getX(),e.getY(),60*density,ViewConfiguration.getDoubleTapTimeout());if(action<0)controller.previous();else main.postDelayed(finishTap,ViewConfiguration.getDoubleTapTimeout());}
                default->{}
            }
        }
        @Override public void onDestroy(){main.removeCallbacks(finishTap);if(engines.remove(this)&&!isPreview())installedEngines.decrementAndGet();renderer.close();super.onDestroy();}
    }
}
