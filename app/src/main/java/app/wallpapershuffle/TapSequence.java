package app.wallpapershuffle;

/** Defers double-tap action so the third tap cannot trigger both next and previous. */
public final class TapSequence {
    private int taps;
    private long last;
    private float x,y;
    public int tap(long when,float px,float py,float slop,long timeout){
        if(when-last>timeout||Math.hypot(px-x,py-y)>slop)taps=0;
        last=when;x=px;y=py;taps++;if(taps==3){taps=0;return -1;}return 0;
    }
    public int finish(){int result=taps==2?1:0;taps=0;return result;}
    public void cancel(){taps=0;}
}
