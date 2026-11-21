package app.wallpapershuffle;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One simple screen: current wallpaper, Next, interval, Set wallpaper, and all pictures (tap = on/off). */
@SuppressWarnings("deprecation")
public final class MainActivity extends Activity {
    static final int BG=Color.BLACK,CARD=Color.rgb(11,16,32),BTN=Color.rgb(20,38,92),BTN_PRIMARY=Color.rgb(30,63,174),BLUE=Color.rgb(45,96,230),TEXT=Color.WHITE,MUTED=Color.rgb(140,152,180);
    private static final int GALLERY=1,FOLDER=2,WALLPAPER=3;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService thumbs=Executors.newFixedThreadPool(2);
    private final android.util.LruCache<Long,Bitmap> cache=new android.util.LruCache<>(24*1024*1024){@Override protected int sizeOf(Long k,Bitmap b){return b.getAllocationByteCount();}};
    private AppController app;
    private List<MediaItem> items=new ArrayList<>();
    private BaseAdapter adapter;
    private ImageView preview;
    private TextView countdown,countText,status;
    private Button intervalBtn,allBtn;
    private long previewId=-2;
    private boolean importing;
    private final Runnable onChange=this::refreshHeader;
    private final Runnable tick=new Runnable(){public void run(){updateCountdown();main.postDelayed(this,1000);}};

    @Override public void onCreate(Bundle b){super.onCreate(b);app=AppController.get(this);app.ensure();app.addListener(onChange);render();}
    @Override protected void onResume(){super.onResume();refreshHeader();main.post(tick);}
    @Override protected void onPause(){main.removeCallbacks(tick);super.onPause();}
    @Override protected void onDestroy(){app.removeListener(onChange);thumbs.shutdownNow();main.removeCallbacksAndMessages(null);super.onDestroy();}

    int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    TextView text(String s,int size,int color){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);return v;}
    GradientDrawable round(int color,int r){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(r));return d;}
    Button button(String label,int color,Runnable action){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(15);b.setTextColor(TEXT);b.setSingleLine(true);b.setBackground(round(color,16));b.setStateListAnimator(null);b.setMinHeight(0);b.setMinimumHeight(0);b.setPadding(dp(10),0,dp(10),0);b.setOnClickListener(v->action.run());return b;}
    LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    LinearLayout.LayoutParams weight(int topDp){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(52),1);p.setMargins(dp(4),dp(topDp),dp(4),0);return p;}

    void render(){
        items=app.library.list("added",true);
        LinearLayout header=new LinearLayout(this);header.setOrientation(LinearLayout.VERTICAL);header.setPadding(dp(14),dp(10),dp(14),dp(6));
        LinearLayout top=row();TextView title=text(getString(R.string.app_name),24,TEXT);top.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        Button lang=button(getString(R.string.lang_switch),BTN,this::switchLanguage);lang.setTextSize(13);lang.setMinHeight(dp(36));top.addView(lang,new LinearLayout.LayoutParams(dp(56),dp(40)));header.addView(top);
        if(items.isEmpty()){
            LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setBackground(round(CARD,22));card.setPadding(dp(20),dp(24),dp(20),dp(20));
            card.addView(text(getString(R.string.empty_title),24,TEXT));TextView n=text(getString(R.string.empty_note),15,MUTED);n.setPadding(0,dp(8),0,dp(12));card.addView(n);
            card.addView(button(getString(R.string.add),BTN_PRIMARY,this::addMenu),new LinearLayout.LayoutParams(-1,dp(54)));
            LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.topMargin=dp(20);header.addView(card,cp);
            ScrollView s=new ScrollView(this);s.setBackgroundColor(BG);s.addView(header);setContentView(s);preview=null;adapter=null;return;
        }
        FrameLayout frame=new FrameLayout(this);frame.setBackground(round(CARD,22));frame.setClipToOutline(true);
        preview=new ImageView(this);preview.setScaleType(ImageView.ScaleType.CENTER_CROP);frame.addView(preview,new FrameLayout.LayoutParams(-1,-1));previewId=-2;
        countdown=text("",14,TEXT);countdown.setBackground(round(0xB0000000,14));countdown.setPadding(dp(12),dp(6),dp(12),dp(6));FrameLayout.LayoutParams cdp=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);cdp.bottomMargin=dp(12);frame.addView(countdown,cdp);
        LinearLayout.LayoutParams fp=new LinearLayout.LayoutParams(-1,(int)(getResources().getDisplayMetrics().heightPixels*0.42f));fp.topMargin=dp(10);header.addView(frame,fp);
        LinearLayout controls=row();controls.addView(button(getString(R.string.next),BTN,app::next),weight(10));intervalBtn=button("",BTN,this::intervalDialog);controls.addView(intervalBtn,weight(10));header.addView(controls,new LinearLayout.LayoutParams(-1,dp(64)));
        LinearLayout installRow=row();installRow.addView(button(getString(R.string.install),BTN_PRIMARY,this::install),weight(8));header.addView(installRow,new LinearLayout.LayoutParams(-1,dp(62)));
        status=text("",13,MUTED);status.setGravity(Gravity.CENTER);status.setPadding(0,dp(8),0,0);header.addView(status);
        LinearLayout libRow=row();libRow.setPadding(dp(4),dp(18),0,dp(4));countText=text("",16,TEXT);libRow.addView(countText,new LinearLayout.LayoutParams(0,-2,1));
        allBtn=button("",BTN,this::toggleAll);allBtn.setTextSize(13);allBtn.setMinHeight(dp(40));LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(-2,dp(42));ap.rightMargin=dp(6);libRow.addView(allBtn,ap);
        Button add=button(getString(R.string.add),BTN_PRIMARY,this::addMenu);add.setTextSize(13);add.setMinHeight(dp(40));libRow.addView(add,new LinearLayout.LayoutParams(-2,dp(42)));header.addView(libRow);
        TextView hint=text(getString(R.string.hint),12,MUTED);hint.setPadding(dp(4),0,0,dp(6));header.addView(hint);

        int pad=dp(14),gap=dp(6);int tileW=(getResources().getDisplayMetrics().widthPixels-2*pad-2*gap)/3,tileH=(int)(tileW*1.5f);
        ListView list=new ListView(this);list.setBackgroundColor(BG);list.setDivider(null);list.setSelector(new android.graphics.drawable.ColorDrawable(0));list.addHeaderView(header,null,false);list.setPadding(0,0,0,dp(20));list.setClipToPadding(false);
        adapter=new BaseAdapter(){
            public int getCount(){return (items.size()+2)/3;}public Object getItem(int i){return i;}public long getItemId(int i){return i;}
            public View getView(int r,View old,ViewGroup parent){LinearLayout rowV;
                if(old instanceof LinearLayout){rowV=(LinearLayout)old;}else{rowV=row();rowV.setPadding(pad,0,pad,gap);for(int k=0;k<3;k++){FrameLayout t=tile();LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(tileW,tileH);if(k<2)p.rightMargin=gap;rowV.addView(t,p);}}
                for(int k=0;k<3;k++){int at=r*3+k;FrameLayout t=(FrameLayout)rowV.getChildAt(k);if(at<items.size()){t.setVisibility(View.VISIBLE);bindTile(t,items.get(at));}else t.setVisibility(View.INVISIBLE);}
                return rowV;}
        };
        list.setAdapter(adapter);setContentView(list);refreshHeader();
    }
    FrameLayout tile(){FrameLayout t=new FrameLayout(this);t.setBackground(round(CARD,14));t.setClipToOutline(true);ImageView img=new ImageView(this);img.setScaleType(ImageView.ScaleType.CENTER_CROP);t.addView(img,new FrameLayout.LayoutParams(-1,-1));
        TextView check=text("",15,TEXT);check.setGravity(Gravity.CENTER);FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(dp(30),dp(30),Gravity.TOP|Gravity.END);cp.setMargins(0,dp(6),dp(6),0);t.addView(check,cp);
        TextView kind=text("",11,TEXT);kind.setBackground(round(0xB0000000,8));kind.setPadding(dp(6),dp(2),dp(6),dp(2));FrameLayout.LayoutParams kp=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.START);kp.setMargins(dp(6),0,0,dp(6));t.addView(kind,kp);return t;}
    void bindTile(FrameLayout t,MediaItem m){
        ImageView img=(ImageView)t.getChildAt(0);TextView check=(TextView)t.getChildAt(1),kind=(TextView)t.getChildAt(2);loadThumb(img,m);
        boolean on=m.shown();img.setAlpha(on?1f:.3f);check.setText(on?"✓":"");GradientDrawable c=new GradientDrawable();c.setShape(GradientDrawable.OVAL);c.setColor(on?BLUE:0x66000000);c.setStroke(dp(2),TEXT);check.setBackground(c);
        kind.setText(m.video()?"▶":m.animated()?"GIF":"");kind.setVisibility(m.video()||m.animated()?View.VISIBLE:View.GONE);
        t.setOnClickListener(v->{m.screens=on?MediaItem.HIDDEN:MediaItem.BOTH;app.library.setOn(m.id,!on);app.libraryChanged();adapter.notifyDataSetChanged();});
        t.setOnLongClickListener(v->{itemMenu(m);return true;});
    }
    void itemMenu(MediaItem m){new AlertDialog.Builder(this).setItems(new String[]{getString(R.string.show_now),getString(R.string.delete)},(d,n)->{
        if(n==0){if(!m.shown()){m.screens=MediaItem.BOTH;app.library.setOn(m.id,true);}app.select(m.id);adapter.notifyDataSetChanged();}
        else new AlertDialog.Builder(this).setMessage(getString(R.string.delete_q)).setPositiveButton(getString(R.string.delete),(a,b)->{app.library.remove(m.id);cache.remove(m.id);app.libraryChanged();render();}).setNegativeButton(getString(R.string.cancel),null).show();
    }).show();}
    void toggleAll(){boolean anyOff=false;for(MediaItem m:items)if(!m.shown())anyOff=true;app.library.setAll(anyOff);for(MediaItem m:items)m.screens=anyOff?MediaItem.BOTH:MediaItem.HIDDEN;app.libraryChanged();adapter.notifyDataSetChanged();}

    void refreshHeader(){
        if(preview==null||isDestroyed())return;
        int on=0;for(MediaItem m:items)if(m.shown())on++;
        countText.setText(getString(R.string.my_walls,on,items.size()));allBtn.setText(getString(on<items.size()?R.string.all_on:R.string.all_off));
        intervalBtn.setText(app.interval()>0?getString(R.string.every,duration(app.interval())):getString(R.string.only_unlock));
        status.setText(getString(app.active()?R.string.installed:R.string.not_installed)+"\n"+getString(R.string.gestures_note));
        MediaItem cur=app.current();long id=cur==null?-1:cur.id;
        if(id!=previewId){previewId=id;preview.setImageDrawable(null);if(cur!=null&&!thumbs.isShutdown())thumbs.execute(()->{try{Bitmap b=MediaImages.decode(cur,1080);main.post(()->{if(previewId==cur.id&&preview!=null)preview.setImageBitmap(b);});}catch(Exception|OutOfMemoryError ignored){}});}
        updateCountdown();
    }
    void updateCountdown(){
        if(countdown==null||preview==null)return;
        if(!app.active()){countdown.setText(getString(R.string.not_installed));return;}
        if(app.interval()<=0){countdown.setText(getString(R.string.changes_on_unlock));return;}
        long left=Math.max(0,app.state.due-System.currentTimeMillis());countdown.setText(getString(R.string.next_in,clock(left)));
    }
    static String clock(long ms){long s=(ms+999)/1000,h=s/3600,m=s%3600/60,sec=s%60;return h>0?String.format(Locale.ROOT,"%d:%02d:%02d",h,m,sec):String.format(Locale.ROOT,"%d:%02d",m,sec);}
    String duration(long ms){long s=ms/1000,h=s/3600,m=s%3600/60,sec=s%60;StringBuilder b=new StringBuilder();if(h>0)b.append(h).append(' ').append(getString(R.string.hours)).append(' ');if(m>0)b.append(m).append(' ').append(getString(R.string.minutes)).append(' ');if(sec>0)b.append(sec).append(' ').append(getString(R.string.seconds));return b.toString().trim();}

    /** Wheel picker: hours / minutes / seconds, like a clock timer. */
    void intervalDialog(){
        long s=app.interval()/1000;LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(20),dp(10),dp(20),0);
        LinearLayout wheels=row();wheels.setGravity(Gravity.CENTER);
        NumberPicker h=wheel(23,(int)(s/3600)),m=wheel(59,(int)(s%3600/60)),sec=wheel(59,(int)(s%60));
        int[] units={R.string.hours,R.string.minutes,R.string.seconds};NumberPicker[] ps={h,m,sec};
        for(int i=0;i<3;i++){wheels.addView(ps[i],new LinearLayout.LayoutParams(dp(64),-2));TextView u=text(getString(units[i]),16,TEXT);u.setPadding(dp(4),0,dp(10),0);wheels.addView(u);}
        box.addView(wheels);
        CheckBox unlock=new CheckBox(this);unlock.setText(getString(R.string.also_unlock));unlock.setTextColor(TEXT);unlock.setChecked(app.onUnlock());box.addView(unlock);
        TextView hint=text(getString(R.string.timer_hint),12,MUTED);hint.setPadding(0,dp(4),0,0);box.addView(hint);
        new AlertDialog.Builder(this).setTitle(getString(R.string.timer_title)).setView(box).setPositiveButton(getString(R.string.ok),(d,w)->{
            long total=(h.getValue()*3600L+m.getValue()*60L+sec.getValue())*1000L;app.setSchedule(total,unlock.isChecked()||total==0);
        }).setNegativeButton(getString(R.string.cancel),null).show();
    }
    NumberPicker wheel(int max,int value){NumberPicker p=new NumberPicker(this);p.setMinValue(0);p.setMaxValue(max);p.setValue(Math.min(max,value));p.setWrapSelectorWheel(true);p.setFormatter(v->String.format(Locale.ROOT,"%02d",v));p.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);return p;}

    void install(){
        if(app.ids().isEmpty()){toast(getString(R.string.need_on));return;}
        Intent i=new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);i.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,new ComponentName(this,ShuffleWallpaperService.class));
        try{startActivityForResult(i,WALLPAPER);}catch(ActivityNotFoundException e){toast(getString(R.string.no_picker));}
    }
    void switchLanguage(){
        String now=getResources().getConfiguration().getLocales().get(0).getLanguage();
        try{getSystemService(LocaleManager.class).setApplicationLocales(LocaleList.forLanguageTags(now.equals("ru")?"en":"ru"));}catch(RuntimeException ignored){}
    }

    void addMenu(){
        if(importing)return;
        new AlertDialog.Builder(this).setTitle(getString(R.string.add_title)).setItems(new String[]{getString(R.string.add_gallery),getString(R.string.add_folder)},(d,n)->{
            if(n==0){Intent i=new Intent(MediaStore.ACTION_PICK_IMAGES);try{i.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX,MediaStore.getPickImagesMaxLimit());startActivityForResult(i,GALLERY);}catch(RuntimeException e){Intent f=new Intent(Intent.ACTION_OPEN_DOCUMENT);f.setType("*/*");f.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"image/*","video/*"});f.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);f.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(f,GALLERY);}}
            else{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivityForResult(i,FOLDER);}
        }).show();
    }
    @Override protected void onActivityResult(int req,int res,Intent data){
        super.onActivityResult(req,res,data);
        if(req==WALLPAPER){refreshHeader();return;}
        if(res!=RESULT_OK||data==null)return;
        if(req==GALLERY){ArrayList<Uri> uris=new ArrayList<>();if(data.getClipData()!=null)for(int i=0;i<data.getClipData().getItemCount();i++)uris.add(data.getClipData().getItemAt(i).getUri());else if(data.getData()!=null)uris.add(data.getData());if(!uris.isEmpty())runImport(uris,null);}
        if(req==FOLDER&&data.getData()!=null)runImport(null,data.getData());
    }
    void runImport(List<Uri> uris,Uri folder){
        importing=true;AtomicBoolean cancel=new AtomicBoolean();ProgressDialog p=new ProgressDialog(this);p.setMessage(getString(R.string.copying));p.setCancelable(false);p.setButton(DialogInterface.BUTTON_NEGATIVE,getString(R.string.cancel),(d,w)->cancel.set(true));p.show();
        FolderImporter.IO.execute(()->{
            int count=0;boolean full=false;
            if(folder!=null){FolderImporter.Result r=FolderImporter.copyFolder(this,folder,cancel,n->main.post(()->p.setMessage(getString(R.string.progress_fmt,n))));count=r.imported();full=r.noSpace();}
            else for(Uri u:uris){if(cancel.get())break;try{app.library.importUri(u,cancel);count++;int n=count;main.post(()->p.setMessage(getString(R.string.progress_fmt,n)));}catch(LibraryStore.NoSpaceException e){full=true;break;}catch(Exception ignored){}}
            app.libraryChanged();int c=count;boolean f=full;
            main.post(()->{importing=false;p.dismiss();if(isDestroyed())return;render();toast(getString(R.string.added_fmt,c));if(f)toast(getString(R.string.no_space));});
        });
    }
    void loadThumb(ImageView image,MediaItem item){image.setTag(item.id);image.setImageDrawable(null);Bitmap hit=cache.get(item.id);if(hit!=null){image.setImageBitmap(hit);return;}if(thumbs.isShutdown())return;thumbs.execute(()->{try{Bitmap b=MediaImages.thumb(this,item);cache.put(item.id,b);main.post(()->{if(Objects.equals(image.getTag(),item.id))image.setImageBitmap(b);});}catch(Exception|OutOfMemoryError ignored){}});}
    void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
}
