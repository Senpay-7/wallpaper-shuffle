package app.wallpapershuffle;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import android.graphics.*;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class LibraryStore extends SQLiteOpenHelper {
    private static LibraryStore instance;
    public static synchronized LibraryStore get(Context c) {
        if (instance == null) instance = new LibraryStore(c.getApplicationContext());
        return instance;
    }
    private final Context ctx;
    private LibraryStore(Context c) { super(c, "library.db", null, 1); ctx=c; setWriteAheadLoggingEnabled(true); }
    @Override public void onCreate(SQLiteDatabase d) {
        d.execSQL("CREATE TABLE media(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT,mime TEXT,path TEXT,size INTEGER,width INTEGER,height INTEGER,added INTEGER,modified INTEGER,taken INTEGER,position INTEGER,screens INTEGER DEFAULT 3)");
        d.execSQL("CREATE TABLE sources(uri TEXT PRIMARY KEY,name TEXT,recursive INTEGER,enabled INTEGER DEFAULT 1)");
        d.execSQL("CREATE TABLE seen(source TEXT,document TEXT,PRIMARY KEY(source,document))");
    }
    @Override public void onUpgrade(SQLiteDatabase d,int a,int b) { throw new IllegalStateException("Unsupported database upgrade"); }
    public synchronized List<MediaItem> list(String order, boolean descending) {
        String column = switch(order) { case "name"->"name COLLATE NOCASE"; case "modified"->"modified"; case "taken"->"taken"; case "size"->"size"; case "resolution"->"width*height"; case "manual"->"position"; default->"added"; };
        ArrayList<MediaItem> out = new ArrayList<>();
        try(Cursor c=getReadableDatabase().query("media", null,null,null,null,null,column+(descending?" DESC":" ASC")+",id ASC")) {
            while(c.moveToNext()) out.add(read(c));
        }
        return out;
    }
    private MediaItem read(Cursor c) {
        MediaItem m=new MediaItem(); m.id=c.getLong(0);m.name=c.getString(1);m.mime=c.getString(2);m.path=c.getString(3);m.size=c.getLong(4);m.width=c.getInt(5);m.height=c.getInt(6);m.added=c.getLong(7);m.modified=c.getLong(8);m.taken=c.getLong(9);m.position=c.getLong(10);m.screens=c.getInt(11);return m;
    }
    public synchronized MediaItem item(long id) {
        try(Cursor c=getReadableDatabase().query("media",null,"id=?",new String[]{""+id},null,null,null)) { return c.moveToFirst()?read(c):null; }
    }
    public synchronized void screens(long id, int mask) { ContentValues v=new ContentValues();v.put("screens",mask);getWritableDatabase().update("media",v,"id=?",new String[]{""+id}); }
    /** Assigns many files at once (home / lock / both / hidden) in one transaction. */
    public synchronized void screens(Collection<Long> ids, int mask) {
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try { ContentValues v=new ContentValues();v.put("screens",mask);for(long id:ids)db.update("media",v,"id=?",new String[]{""+id});db.setTransactionSuccessful(); } finally { db.endTransaction(); }
    }
    /** Free space kept untouched by imports. Package-visible so tests can simulate a full disk. */
    static long minFreeBytes = 64L*1024*1024;
    public static final class NoSpaceException extends IOException { NoSpaceException(){super("Not enough free storage");} }
    /** Screens newly imported files get: 0 hidden until marked (default), 1 home, 2 lock, 3 both. */
    int newFileScreens(){ return MediaItem.BOTH; }
    /** Turns every file on (true) or off (false). */
    public synchronized void setAll(boolean on){ContentValues v=new ContentValues();v.put("screens",on?MediaItem.BOTH:MediaItem.HIDDEN);getWritableDatabase().update("media",v,null,null);}
    public void setOn(long id,boolean on){screens(id,on?MediaItem.BOTH:MediaItem.HIDDEN);}
    public synchronized void remove(long id) {
        MediaItem m=item(id); if(m==null)return;
        getWritableDatabase().delete("media","id=?",new String[]{""+id});
        // These paths are generated internally, never supplied by the provider.
        m.file().delete(); new File(ctx.getCacheDir(),"thumb-"+id+".jpg").delete();
    }
    public synchronized void move(long id, int direction) {
        List<MediaItem> all=list("manual",false);int at=-1;
        for(int i=0;i<all.size();i++)if(all.get(i).id==id)at=i;
        int to=at+direction;if(at<0||to<0||to>=all.size())return;
        Collections.swap(all,at,to);SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try { for(int i=0;i<all.size();i++){ContentValues v=new ContentValues();v.put("position",i);db.update("media",v,"id=?",new String[]{""+all.get(i).id});} db.setTransactionSuccessful(); }finally{db.endTransaction();}
    }
    public MediaItem importUri(Uri uri, AtomicBoolean cancel) throws IOException {
        return importUri(uri,cancel,null,null);
    }
    public MediaItem importUri(Uri uri, AtomicBoolean cancel,String source,String document) throws IOException {
        String name=uri.getLastPathSegment()==null?"Media":uri.getLastPathSegment(),mime=ctx.getContentResolver().getType(uri);long modified=System.currentTimeMillis(),expected=0;
        try(Cursor c=ctx.getContentResolver().query(uri,null,null,null,null)) {
            if(c!=null&&c.moveToFirst()) {
                int n=c.getColumnIndex(OpenableColumns.DISPLAY_NAME);if(n>=0&&c.getString(n)!=null)name=c.getString(n);
                int t=c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED);if(t>=0&&c.getLong(t)>0)modified=c.getLong(t);
                int s=c.getColumnIndex(OpenableColumns.SIZE);if(s>=0&&!c.isNull(s))expected=Math.max(0,c.getLong(s));
            }
        } catch(RuntimeException ignored) { }
        if(ctx.getFilesDir().getUsableSpace()<expected+minFreeBytes)throw new NoSpaceException();
        if(mime==null||(!mime.startsWith("image/")&&!mime.startsWith("video/"))) {
            String lower=name.toLowerCase(Locale.ROOT);
            mime=lower.endsWith(".gif")?"image/gif":lower.endsWith(".mp4")||lower.endsWith(".mkv")||lower.endsWith(".webm")?"video/mp4":"image/*";
        }
        File dir=new File(ctx.getFilesDir(),"media");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Cannot create media storage");
        File tmp=new File(dir,UUID.randomUUID()+".part"), dest=new File(dir,UUID.randomUUID()+".media");
        try {
            try(InputStream in=ctx.getContentResolver().openInputStream(uri);FileOutputStream out=new FileOutputStream(tmp)) {
                if(in==null)throw new IOException("File cannot be opened");
                byte[] bytes=new byte[65536];int n;
                long written=0;
                while((n=in.read(bytes))!=-1){if(cancel.get())throw new InterruptedIOException("Import cancelled");out.write(bytes,0,n);written+=n;if((written&0xFFFFFF)<n&&dir.getUsableSpace()<minFreeBytes/2)throw new NoSpaceException();}
                out.getFD().sync();
            }
            if(tmp.length()==0)throw new IOException("Empty file");
            int w,h;long taken=modified;
            if(mime.startsWith("video/")) {
                try(MediaMetadataRetriever r=new MediaMetadataRetriever()) {
                    r.setDataSource(tmp.getAbsolutePath());
                    w=Integer.parseInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));h=Integer.parseInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                    String rotate=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);if("90".equals(rotate)||"270".equals(rotate)){int sw=w;w=h;h=sw;}
                }
            } else {
                final int[] dims=new int[2];final String[] imageMime=new String[1];
                Bitmap validation=ImageDecoder.decodeBitmap(ImageDecoder.createSource(tmp),(d,i,s)->{dims[0]=i.getSize().getWidth();dims[1]=i.getSize().getHeight();imageMime[0]=i.getMimeType();d.setTargetSize(1,1);d.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);});
                validation.recycle();w=dims[0];h=dims[1];if(imageMime[0]!=null)mime=imageMime[0];
                try { android.media.ExifInterface exif=new android.media.ExifInterface(tmp);String date=exif.getAttribute(android.media.ExifInterface.TAG_DATETIME_ORIGINAL);if(date!=null){java.text.SimpleDateFormat f=new java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss",Locale.US);Date t=f.parse(date);if(t!=null)taken=t.getTime();} }catch(Exception ignored){}
            }
            if(w<=0||h<=0)throw new IOException("Unsupported image or video");
            if(cancel.get())throw new InterruptedIOException("Import cancelled");
            if(!tmp.renameTo(dest))throw new IOException("Cannot save imported file");
            ContentValues v=new ContentValues();v.put("name",name);v.put("mime",mime);v.put("path",dest.getAbsolutePath());v.put("size",dest.length());v.put("width",w);v.put("height",h);v.put("added",System.currentTimeMillis());v.put("modified",modified);v.put("taken",taken);v.put("screens",newFileScreens());
            synchronized(this){SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{long position=android.database.DatabaseUtils.longForQuery(db,"SELECT COALESCE(MAX(position),-1)+1 FROM media",null);v.put("position",position);long id=db.insertOrThrow("media",null,v);if(source!=null&&document!=null)markSeen(source,document);db.setTransactionSuccessful();return item(id);}finally{db.endTransaction();}}
        } catch(Exception e) {
            dest.delete();
            if(e instanceof IOException io){String msg=String.valueOf(io.getMessage());if(msg.contains("ENOSPC")||msg.contains("No space"))throw new NoSpaceException();throw io;}
            throw new IOException("Unsupported or unreadable file: "+name,e);
        }
        finally { tmp.delete(); }
    }
    public synchronized void addSource(Uri uri, boolean recursive) {
        ContentValues v=new ContentValues();v.put("uri",uri.toString());String label="Folder";
        try(Cursor c=ctx.getContentResolver().query(DocumentsContract.buildDocumentUriUsingTree(uri,DocumentsContract.getTreeDocumentId(uri)),new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())label=c.getString(0);}catch(Exception ignored){}
        v.put("name",label);v.put("recursive",recursive?1:0);v.put("enabled",1);getWritableDatabase().insertWithOnConflict("sources",null,v,SQLiteDatabase.CONFLICT_REPLACE);
    }
    public record Source(String uri,String name,boolean recursive,boolean enabled) {}
    public synchronized List<Source> sources() { ArrayList<Source> out=new ArrayList<>();try(Cursor c=getReadableDatabase().rawQuery("SELECT uri,name,recursive,enabled FROM sources",null)){while(c.moveToNext())out.add(new Source(c.getString(0),c.getString(1),c.getInt(2)==1,c.getInt(3)==1));}return out; }
    /** enabled=false marks a folder whose access permission was lost; re-adding the folder enables it again. */
    public synchronized void setSourceEnabled(String uri,boolean enabled) {ContentValues v=new ContentValues();v.put("enabled",enabled?1:0);getWritableDatabase().update("sources",v,"uri=?",new String[]{uri});}
    public synchronized void removeSource(String uri) {getWritableDatabase().delete("sources","uri=?",new String[]{uri});getWritableDatabase().delete("seen","source=?",new String[]{uri});}
    public synchronized boolean seen(String source,String document) {try(Cursor c=getReadableDatabase().rawQuery("SELECT 1 FROM seen WHERE source=? AND document=?",new String[]{source,document})){return c.moveToFirst();}}
    public synchronized void markSeen(String source,String document) {ContentValues v=new ContentValues();v.put("source",source);v.put("document",document);getWritableDatabase().insertWithOnConflict("seen",null,v,SQLiteDatabase.CONFLICT_IGNORE);}
}
