package app.wallpapershuffle;

import android.app.WallpaperManager;
import android.content.*;
import android.os.*;
import org.json.*;
import java.util.*;

/** One carousel: shuffled, changes on a timer and/or on unlock. */
public final class AppController {
    private static AppController instance;
    public static synchronized AppController get(Context c) { if(instance==null)instance=new AppController(c.getApplicationContext());return instance; }
    public final Context ctx;
    public final LibraryStore library;
    public final SharedPreferences prefs;
    public final Playback state=new Playback();
    private final Set<Runnable> listeners=new HashSet<>();
    private final Random rng=new Random();
    private final Handler main=new Handler(Looper.getMainLooper());

    private AppController(Context c){
        ctx=c;library=LibraryStore.get(c);prefs=c.getSharedPreferences("settings",Context.MODE_PRIVATE);load();
        // 1.2: everything in the library is on by default (earlier versions hid new files).
        if(!prefs.getBoolean("v12",false)){library.setAll(true);prefs.edit().putBoolean("v12",true).apply();}
    }
    /** Change interval in ms; 0 = timer off. */
    public long interval(){return prefs.getLong("interval",15*60000L);}
    public boolean onUnlock(){return prefs.getBoolean("onUnlock",true);}
    public synchronized void setSchedule(long interval,boolean unlock){prefs.edit().putLong("interval",interval).putBoolean("onUnlock",unlock).apply();state.due=System.currentTimeMillis()+interval;save();changed();}

    private void load(){try{JSONObject j=new JSONObject(prefs.getString("main.state","{}"));state.current=j.optLong("current",-1);state.previous=j.optLong("previous",-1);state.due=j.optLong("due",0);JSONArray bag=j.optJSONArray("bag"),cycle=j.optJSONArray("cycle");if(bag!=null)for(int i=0;i<bag.length();i++)state.bag.add(bag.getLong(i));if(cycle!=null)for(int i=0;i<cycle.length();i++)state.cycle.add(cycle.getLong(i));}catch(JSONException ignored){}}
    private void save(){JSONObject j=new JSONObject();try{j.put("current",state.current);j.put("previous",state.previous);j.put("due",state.due);j.put("bag",new JSONArray(state.bag));j.put("cycle",new JSONArray(state.cycle));}catch(JSONException e){throw new IllegalStateException(e);}prefs.edit().putString("main.state",j.toString()).apply();}

    public List<Long> ids(){ArrayList<Long> out=new ArrayList<>();for(MediaItem m:library.list("added",false))if(m.shown()&&m.file().isFile())out.add(m.id);return out;}
    public synchronized MediaItem current(){return library.item(state.current);}
    public synchronized void ensure(){List<Long> ids=ids();if(!ids.contains(state.current)){state.next(ids,true,rng,System.currentTimeMillis(),interval());save();}}
    public synchronized void next(){state.next(ids(),true,rng,System.currentTimeMillis(),interval());save();changed();}
    public synchronized void previous(){state.previous(System.currentTimeMillis(),interval(),ids());save();changed();}
    public synchronized void select(long id){if(!ids().contains(id))return;state.choose(id,System.currentTimeMillis(),interval());save();changed();}
    /** event: "unlock" or "timer". */
    public synchronized void event(String event,long now){
        ensure();long iv=interval();
        boolean go=event.equals("unlock")?onUnlock():event.equals("timer")&&iv>0&&state.due>0&&now>=state.due;
        if(go){state.next(ids(),true,rng,now,iv);save();}
        changed();
    }
    public synchronized long nextDeadline(long now){long iv=interval();if(iv<=0||state.current<0)return Long.MAX_VALUE;return Math.max(now+100,state.due);}
    public synchronized void libraryChanged(){ensure();changed();}
    public synchronized void addListener(Runnable r){listeners.add(r);}
    public synchronized void removeListener(Runnable r){listeners.remove(r);}
    public void changed(){main.post(()->{List<Runnable> copy;synchronized(this){copy=new ArrayList<>(listeners);}for(Runnable r:copy)r.run();});}
    /** Android 13 hides getWallpaperInfo() from apps, so use our running engine or the wallpaper id seen when it started. */
    public boolean active(){
        if(ShuffleWallpaperService.installedEngines.get()>0)return true;
        try{WallpaperManager w=WallpaperManager.getInstance(ctx);int ours=prefs.getInt("ourSystemId",-1);return ours>=0&&w.getWallpaperId(WallpaperManager.FLAG_SYSTEM)==ours;}catch(RuntimeException e){return false;}
    }
    public void activated(){int id=-1;try{id=WallpaperManager.getInstance(ctx).getWallpaperId(WallpaperManager.FLAG_SYSTEM);}catch(RuntimeException ignored){}prefs.edit().putInt("ourSystemId",id).apply();}
}
