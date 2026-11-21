package app.wallpapershuffle;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.*;
import android.media.MediaPlayer;
import android.opengl.*;
import android.os.*;
import android.view.Surface;
import java.nio.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** One bounded render thread. No drawing or video decoding while not visible. */
public final class SceneRenderer {
    private final Context ctx;
    private final HandlerThread thread=new HandlerThread("Wallpaper renderer");
    private final Handler h;
    private final Consumer<String> failure;
    private EGLDisplay display;
    private EGLContext context;
    private EGLSurface window;
    private int width=1,height=1,texture,program,externalProgram,posterTexture;
    private float posterWidth=1,posterHeight=1;
    private boolean videoFrame;
    private final float[] identity=new float[16];
    private float mediaWidth=1,mediaHeight=1;
    private final FloatBuffer vertices=ByteBuffer.allocateDirect(8*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private final FloatBuffer uv=ByteBuffer.allocateDirect(8*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private final FloatBuffer videoUv=ByteBuffer.allocateDirect(8*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private final float[] transform=new float[16];
    private MediaPlayer player;
    private boolean prepared,visible,ready,external,closed,snapshots;
    private SurfaceTexture videoTexture;
    private Surface decoderSurface;
    private AnimatedImageDrawable gif;
    private Bitmap animationBitmap;
    private boolean framePending;
    private String fit="crop",scene="home",loadedKey="";
    private MediaItem requested;
    private Bitmap directBoot;
    private long generation;
    private final java.util.concurrent.atomic.AtomicLong requestedVersion=new java.util.concurrent.atomic.AtomicLong();

    public SceneRenderer(Context c,Consumer<String> onFailure){ctx=c.getApplicationContext();failure=onFailure;vertices.put(new float[]{-1,-1,1,-1,-1,1,1,1}).position(0);uv.put(new float[]{0,1,1,1,0,0,1,0}).position(0);videoUv.put(new float[]{0,0,1,0,0,1,1,1}).position(0);android.opengl.Matrix.setIdentityM(transform,0);android.opengl.Matrix.setIdentityM(identity,0);thread.start();h=new Handler(thread.getLooper());}
    public void snapshots(boolean enabled){h.post(()->snapshots=enabled);}
    public void surface(Surface surface,int w,int ht){h.post(()->{if(closed)return;releaseGl();width=Math.max(1,w);height=Math.max(1,ht);try{initGl(surface);ready=true;loadedKey="";load();}catch(Exception e){report();releaseGl();}});}
    public void resize(int w,int ht){h.post(()->{width=Math.max(1,w);height=Math.max(1,ht);draw();});}
    public void show(MediaItem item,String screen,String fitting,boolean isVisible){long version=requestedVersion.incrementAndGet();h.post(()->{if(closed||version!=requestedVersion.get())return;if(snapshots)MediaImages.bootFit(ctx,screen,fitting);requested=item;scene=screen;fit=fitting;visible=isVisible;load();updatePlayback();if(visible)draw();});}
    public void showBoot(Bitmap bitmap,String screen,String fitting,boolean isVisible){h.post(()->{if(closed){if(bitmap!=null)bitmap.recycle();return;}if(directBoot!=null)directBoot.recycle();directBoot=bitmap;requested=null;scene=screen;fit=fitting;visible=isVisible;loadedKey="";load();draw();});}
    public void visible(boolean value){h.post(()->{visible=value;updatePlayback();if(visible){load();draw();}});}
    private void report(){if(failure!=null)new Handler(Looper.getMainLooper()).post(()->failure.accept(ctx.getString(R.string.play_error)));}
    private void initGl(Surface surface){
        display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);int[] version=new int[2];if(!EGL14.eglInitialize(display,version,0,version,1))throw new IllegalStateException("EGL init");
        int[] attrs={EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,EGL14.EGL_SURFACE_TYPE,EGL14.EGL_WINDOW_BIT,EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_NONE};
        EGLConfig[] configs=new EGLConfig[1];int[] count=new int[1];if(!EGL14.eglChooseConfig(display,attrs,0,configs,0,1,count,0)||count[0]==0)throw new IllegalStateException("EGL config");
        context=EGL14.eglCreateContext(display,configs[0],EGL14.EGL_NO_CONTEXT,new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE},0);
        window=EGL14.eglCreateWindowSurface(display,configs[0],surface,new int[]{EGL14.EGL_NONE},0);if(window==EGL14.EGL_NO_SURFACE)throw new IllegalStateException("EGL surface");
        EGL14.eglMakeCurrent(display,window,window,context);program=link(false);externalProgram=link(true);
    }
    private int shader(int type,String source){int s=GLES20.glCreateShader(type);GLES20.glShaderSource(s,source);GLES20.glCompileShader(s);int[] ok=new int[1];GLES20.glGetShaderiv(s,GLES20.GL_COMPILE_STATUS,ok,0);if(ok[0]==0)throw new IllegalStateException(GLES20.glGetShaderInfoLog(s));return s;}
    private int link(boolean ext){
        String vertex="attribute vec2 pos;attribute vec2 tex;uniform mat4 matrix;uniform vec2 scale;varying vec2 v;void main(){gl_Position=vec4(pos*scale,0.0,1.0);v=(matrix*vec4(tex,0.0,1.0)).xy;}";
        String fragment=(ext?"#extension GL_OES_EGL_image_external : require\n":"")+"precision mediump float;uniform "+(ext?"samplerExternalOES":"sampler2D")+" image;uniform float blur;varying vec2 v;void main(){vec4 c=texture2D(image,v)*0.20;c+=texture2D(image,clamp(v+vec2(blur,0.0),0.0,1.0))*0.12;c+=texture2D(image,clamp(v-vec2(blur,0.0),0.0,1.0))*0.12;c+=texture2D(image,clamp(v+vec2(0.0,blur),0.0,1.0))*0.12;c+=texture2D(image,clamp(v-vec2(0.0,blur),0.0,1.0))*0.12;c+=texture2D(image,clamp(v+vec2(blur,blur),0.0,1.0))*0.08;c+=texture2D(image,clamp(v-vec2(blur,blur),0.0,1.0))*0.08;c+=texture2D(image,clamp(v+vec2(blur,-blur),0.0,1.0))*0.08;c+=texture2D(image,clamp(v+vec2(-blur,blur),0.0,1.0))*0.08;gl_FragColor=c;}";
        int vs=shader(GLES20.GL_VERTEX_SHADER,vertex),fs=shader(GLES20.GL_FRAGMENT_SHADER,fragment),p=GLES20.glCreateProgram();GLES20.glAttachShader(p,vs);GLES20.glAttachShader(p,fs);GLES20.glLinkProgram(p);GLES20.glDeleteShader(vs);GLES20.glDeleteShader(fs);int[] ok=new int[1];GLES20.glGetProgramiv(p,GLES20.GL_LINK_STATUS,ok,0);if(ok[0]==0)throw new IllegalStateException("Shader link");return p;
    }
    private void newTexture(boolean ext){if(texture!=0)GLES20.glDeleteTextures(1,new int[]{texture},0);external=ext;int[] t=new int[1];GLES20.glGenTextures(1,t,0);texture=t[0];int target=ext?GLES11Ext.GL_TEXTURE_EXTERNAL_OES:GLES20.GL_TEXTURE_2D;GLES20.glBindTexture(target,texture);GLES20.glTexParameteri(target,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);GLES20.glTexParameteri(target,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);GLES20.glTexParameteri(target,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);GLES20.glTexParameteri(target,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);android.opengl.Matrix.setIdentityM(transform,0);}
    private void load(){
        if(!ready||!visible)return;
        String key=(requested==null?"empty":Long.toString(requested.id))+":"+scene;
        if(key.equals(loadedKey))return;loadedKey=key;releaseMedia();long version=++generation;
        try {
            if(requested==null){newTexture(false);if(directBoot!=null){mediaWidth=directBoot.getWidth();mediaHeight=directBoot.getHeight();GLUtils.texImage2D(GLES20.GL_TEXTURE_2D,0,directBoot,0);}else{Bitmap b=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888);b.eraseColor(Color.rgb(23,23,32));mediaWidth=2;mediaHeight=2;GLUtils.texImage2D(GLES20.GL_TEXTURE_2D,0,b,0);b.recycle();}return;}
            MediaItem m=requested;
            // Save a bounded, static copy of the last visible scene for first boot before the PIN.
            Bitmap still=MediaImages.decode(m,Math.min(1920,Math.max(width,height)));if(snapshots)MediaImages.bootStill(ctx,scene,still);
            mediaWidth=still.getWidth();mediaHeight=still.getHeight();
            if(m.video()) {
                // Keep the first frame visible until the decoder delivers real video frames (no black flash).
                posterTexture=upload(still);posterWidth=still.getWidth();posterHeight=still.getHeight();still.recycle();newTexture(true);mediaWidth=m.width;mediaHeight=m.height;
                videoTexture=new SurfaceTexture(texture);decoderSurface=new Surface(videoTexture);
                videoTexture.setOnFrameAvailableListener(st->{if(!visible||version!=generation||!ready)return;try{st.updateTexImage();st.getTransformMatrix(transform);videoFrame=true;draw();}catch(RuntimeException ignored){}},h);
                player=new MediaPlayer();player.setDataSource(m.path);player.setSurface(decoderSurface);player.setVolume(0,0);player.setLooping(true);
                player.setOnPreparedListener(p->{if(version!=generation)return;prepared=true;updatePlayback();});
                player.setOnErrorListener((p,w,e)->{if(version==generation){report();releaseMedia();try{Bitmap fallback=MediaImages.decode(m,1920);newTexture(false);mediaWidth=fallback.getWidth();mediaHeight=fallback.getHeight();GLUtils.texImage2D(GLES20.GL_TEXTURE_2D,0,fallback,0);fallback.recycle();draw();}catch(Exception ignored){}}return true;});player.prepareAsync();
            } else if(m.animated()) {
                still.recycle();newTexture(false);
                Drawable drawable=ImageDecoder.decodeDrawable(ImageDecoder.createSource(m.file()),(d,i,s)->{int max=Math.min(1440,Math.max(width,height));float scale=Math.min(1f,(float)max/Math.max(i.getSize().getWidth(),i.getSize().getHeight()));d.setTargetSize(Math.max(1,(int)(i.getSize().getWidth()*scale)),Math.max(1,(int)(i.getSize().getHeight()*scale)));d.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);});
                if(drawable instanceof AnimatedImageDrawable animation){gif=animation;mediaWidth=gif.getIntrinsicWidth();mediaHeight=gif.getIntrinsicHeight();animationBitmap=Bitmap.createBitmap((int)mediaWidth,(int)mediaHeight,Bitmap.Config.ARGB_8888);gif.setBounds(0,0,(int)mediaWidth,(int)mediaHeight);gif.setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE);gif.setCallback(new Drawable.Callback(){public void invalidateDrawable(Drawable d){requestGifFrame();}public void scheduleDrawable(Drawable d,Runnable r,long when){h.postAtTime(r,d,when);}public void unscheduleDrawable(Drawable d,Runnable r){h.removeCallbacks(r);}});gifFrame.run();updatePlayback();}
                else {Bitmap b=MediaImages.decode(m,Math.max(width,height));GLUtils.texImage2D(GLES20.GL_TEXTURE_2D,0,b,0);mediaWidth=b.getWidth();mediaHeight=b.getHeight();b.recycle();}
            } else {newTexture(false);GLUtils.texImage2D(GLES20.GL_TEXTURE_2D,0,still,0);still.recycle();}
        }catch(Exception|OutOfMemoryError e){report();releaseMedia();newTexture(false);Bitmap b=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888);b.eraseColor(Color.rgb(23,23,32));mediaWidth=2;mediaHeight=2;GLUtils.texImage2D(GLES20.GL_TEXTURE_2D,0,b,0);b.recycle();}
    }
    private final Runnable gifFrame=()->{framePending=false;if(gif==null||animationBitmap==null||!ready)return;Canvas c=new Canvas(animationBitmap);c.drawColor(Color.BLACK);gif.draw(c);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texture);GLUtils.texImage2D(GLES20.GL_TEXTURE_2D,0,animationBitmap,0);if(visible)draw();};
    private void requestGifFrame(){if(!visible||framePending)return;framePending=true;h.postDelayed(gifFrame,33);}
    private void updatePlayback(){if(player!=null&&prepared){try{if(visible&&!player.isPlaying())player.start();else if(!visible&&player.isPlaying())player.pause();}catch(IllegalStateException ignored){}}if(gif!=null){if(visible){gif.start();requestGifFrame();}else gif.stop();}}
    private int upload(Bitmap b){int[] t=new int[1];GLES20.glGenTextures(1,t,0);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,t[0]);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);GLUtils.texImage2D(GLES20.GL_TEXTURE_2D,0,b,0);return t[0];}
    private void draw(){if(!ready||!visible||texture==0)return;GLES20.glViewport(0,0,width,height);GLES20.glClearColor(0,0,0,1);GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        boolean poster=external&&!videoFrame&&posterTexture!=0;int tex=poster?posterTexture:texture;boolean ext=external&&!poster;float mw=poster?posterWidth:mediaWidth,mh=poster?posterHeight:mediaHeight;float[] m=ext?transform:identity;
        if(fit.equals("blur"))quad(true,0.025f,tex,ext,mw,mh,m);quad(fit.equals("crop"),0f,tex,ext,mw,mh,m);EGL14.eglSwapBuffers(display,window);}
    private void quad(boolean crop,float blur,int textureId,boolean external,float mediaWidth,float mediaHeight,float[] transform){
        int p=external?externalProgram:program;GLES20.glUseProgram(p);float aspect=(mediaWidth/mediaHeight)/((float)width/height);float x,y;if(crop){x=Math.max(1,aspect);y=Math.max(1,1/aspect);}else{x=Math.min(1,aspect);y=Math.min(1,1/aspect);}
        GLES20.glUniform2f(GLES20.glGetUniformLocation(p,"scale"),x,y);GLES20.glUniform1f(GLES20.glGetUniformLocation(p,"blur"),blur);GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(p,"matrix"),1,false,transform,0);
        int pos=GLES20.glGetAttribLocation(p,"pos"),tex=GLES20.glGetAttribLocation(p,"tex");FloatBuffer coordinates=external?videoUv:uv;vertices.position(0);coordinates.position(0);GLES20.glEnableVertexAttribArray(pos);GLES20.glEnableVertexAttribArray(tex);GLES20.glVertexAttribPointer(pos,2,GLES20.GL_FLOAT,false,0,vertices);GLES20.glVertexAttribPointer(tex,2,GLES20.GL_FLOAT,false,0,coordinates);GLES20.glActiveTexture(GLES20.GL_TEXTURE0);GLES20.glBindTexture(external?GLES11Ext.GL_TEXTURE_EXTERNAL_OES:GLES20.GL_TEXTURE_2D,textureId);GLES20.glUniform1i(GLES20.glGetUniformLocation(p,"image"),0);GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
    }
    private void releaseMedia(){generation++;videoFrame=false;if(posterTexture!=0){if(display!=null)GLES20.glDeleteTextures(1,new int[]{posterTexture},0);posterTexture=0;}h.removeCallbacks(gifFrame);framePending=false;if(gif!=null){gif.stop();h.removeCallbacksAndMessages(gif);gif.setCallback(null);gif=null;}if(animationBitmap!=null){animationBitmap.recycle();animationBitmap=null;}if(player!=null){player.release();player=null;}prepared=false;if(decoderSurface!=null){decoderSurface.release();decoderSurface=null;}if(videoTexture!=null){videoTexture.release();videoTexture=null;}}
    private void releaseGl(){releaseMedia();ready=false;if(display!=null&&display!=EGL14.EGL_NO_DISPLAY){if(texture!=0){GLES20.glDeleteTextures(1,new int[]{texture},0);texture=0;}if(program!=0)GLES20.glDeleteProgram(program);if(externalProgram!=0)GLES20.glDeleteProgram(externalProgram);program=externalProgram=0;EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);if(window!=null)EGL14.eglDestroySurface(display,window);if(context!=null)EGL14.eglDestroyContext(display,context);EGL14.eglTerminate(display);}display=null;window=null;context=null;}
    public void destroySurface(){CountDownLatch done=new CountDownLatch(1);h.post(()->{try{releaseGl();loadedKey="";}finally{done.countDown();}});try{done.await(1500,TimeUnit.MILLISECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    public void close(){h.post(()->{closed=true;releaseGl();if(directBoot!=null)directBoot.recycle();thread.quitSafely();});}
}
