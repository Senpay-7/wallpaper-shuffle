package app.wallpapershuffle;

import android.content.Context;
import android.graphics.*;
import android.media.MediaMetadataRetriever;
import java.io.*;

public final class MediaImages {
    public static Bitmap decode(MediaItem m,int max) throws IOException {
        if(m.video()) {
            try(MediaMetadataRetriever r=new MediaMetadataRetriever()){
                r.setDataSource(m.path);float scale=Math.min(1f,(float)max/Math.max(m.width,m.height));
                Bitmap b=r.getScaledFrameAtTime(0,MediaMetadataRetriever.OPTION_CLOSEST_SYNC,Math.max(1,(int)(m.width*scale)),Math.max(1,(int)(m.height*scale)));
                if(b==null)throw new IOException("No video preview");return b;
            }catch(RuntimeException e){throw new IOException(e);}
        }
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(m.file()),(d,i,s)->{
            int w=i.getSize().getWidth(),h=i.getSize().getHeight();float scale=Math.min(1f,(float)max/Math.max(w,h));d.setTargetSize(Math.max(1,(int)(w*scale)),Math.max(1,(int)(h*scale)));d.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
        });
    }
    public static Bitmap thumb(Context c,MediaItem m) throws IOException {
        File f=new File(c.getCacheDir(),"thumb-"+m.id+".jpg");Bitmap b=f.isFile()?BitmapFactory.decodeFile(f.getAbsolutePath()):null;if(b!=null)return b;
        b=decode(m,400);try(FileOutputStream out=new FileOutputStream(f)){b.compress(Bitmap.CompressFormat.JPEG,85,out);}catch(IOException ignored){}return b;
    }
    public static void bootStill(Context c,String scene,Bitmap b){
        Context dp=c.createDeviceProtectedStorageContext();android.util.AtomicFile f=new android.util.AtomicFile(new File(dp.getFilesDir(),scene+".jpg"));FileOutputStream out=null;
        try{out=f.startWrite();b.compress(Bitmap.CompressFormat.JPEG,88,out);f.finishWrite(out);}catch(IOException e){if(out!=null)f.failWrite(out);}
    }
    /** Fit mode for the Direct Boot still, kept in device-protected storage next to the picture. */
    public static void bootFit(Context c,String scene,String fit){c.createDeviceProtectedStorageContext().getSharedPreferences("boot",Context.MODE_PRIVATE).edit().putString(scene+".fit",fit).apply();}
    public static String readBootFit(Context c,String scene){return c.createDeviceProtectedStorageContext().getSharedPreferences("boot",Context.MODE_PRIVATE).getString(scene+".fit","crop");}
    /** Renders a bitmap into a w*h picture using crop, fit (black bars) or blur (blurred background). */
    public static Bitmap compose(Bitmap src,int w,int h,String fit){
        Bitmap out=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(out);c.drawColor(Color.BLACK);Paint p=new Paint(Paint.FILTER_BITMAP_FLAG|Paint.ANTI_ALIAS_FLAG);
        float sw=src.getWidth(),sh=src.getHeight(),cover=Math.max(w/sw,h/sh),contain=Math.min(w/sw,h/sh);
        if(fit.equals("blur")){
            Bitmap small=Bitmap.createScaledBitmap(src,Math.max(1,(int)(sw/24)),Math.max(1,(int)(sh/24)),true);
            c.drawBitmap(small,null,centered(sw*cover,sh*cover,w,h),p);small.recycle();c.drawColor(0x55000000);
        }
        float scale=fit.equals("crop")?cover:contain;
        c.drawBitmap(src,null,centered(sw*scale,sh*scale,w,h),p);
        return out;
    }
    private static RectF centered(float dw,float dh,int w,int h){return new RectF((w-dw)/2f,(h-dh)/2f,(w+dw)/2f,(h+dh)/2f);}
    public static Bitmap readBoot(Context c,String scene){return BitmapFactory.decodeFile(new File(c.createDeviceProtectedStorageContext().getFilesDir(),scene+".jpg").getAbsolutePath());}
}
