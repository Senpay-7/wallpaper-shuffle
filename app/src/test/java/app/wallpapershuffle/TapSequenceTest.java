package app.wallpapershuffle;
import org.junit.Test;
import static org.junit.Assert.*;
public class TapSequenceTest {
    @Test public void singleTapDoesNothing(){TapSequence t=new TapSequence();assertEquals(0,t.tap(1000,10,10,60,300));assertEquals(0,t.finish());}
    @Test public void doubleTapIsDeferredUntilThirdTapWindowExpires(){TapSequence t=new TapSequence();t.tap(1000,10,10,60,300);assertEquals(0,t.tap(1150,11,10,60,300));assertEquals(1,t.finish());assertEquals(0,t.finish());}
    @Test public void tripleTapOnlyGoesPrevious(){TapSequence t=new TapSequence();t.tap(1000,10,10,60,300);t.tap(1150,10,10,60,300);assertEquals(-1,t.tap(1300,10,10,60,300));assertEquals(0,t.finish());}
    @Test public void farApartTapsAndSlowTapsDoNothing(){TapSequence t=new TapSequence();t.tap(1000,10,10,60,300);t.tap(1100,200,10,60,300);assertEquals(0,t.finish());t.tap(2000,10,10,60,300);t.tap(2500,10,10,60,300);assertEquals(0,t.finish());}
}
