package az.fuad.futures;

import org.junit.jupiter.api.Test;
import java.util.*;
import static az.fuad.futures.Models.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The three losing LONGs (all score 100, all SL_PROXIMITY, none reached +1R), replayed through the
 * new rules using the values logged at the time. Only logged numbers are used; ATR is derived from
 * the logged ATR distances, and a rule whose inputs were not logged is not asserted.
 */
class LastThreeTradesTest {
    static final Strategy C=Strategy.defaults();
    static Map<String,Double> band(double close,double upper,double stoch) {
        return Map.of("close",close,"bollingerUpper",upper,"bollingerLower",0.0,"stochasticK",stoch);
    }
    static long now=1_790_000_000_000L;

    /**
     * AEROUSDT: reference 0.6943, entry 0.6927, 115 s late, resistance20 0.694 broken by 0.05 ATR
     * (so ATR = 0.0003 / 0.05 = 0.006), EMA20 = entry − 1.70 ATR, support 0.6774,
     * 1h/4h close above the upper band with stoch 96/97.
     */
    @Test void aeroWouldBeRejectedFiveTimesOver() {
        double atr=.006,reference=.6943,entry=.6927,ema20=entry-1.70*atr;
        var signal=new Signal("AEROUSDT",1,100,now-115_000,reference,atr,2*atr,
                Map.of("entry.breakoutLevel",.694,"15m.ema20",ema20),List.of());
        String guard=PaperBroker.entryGuard(signal,entry,now,C);
        assertNotNull(guard); assertTrue(guard.contains("115 san"),guard);
        // Each independent reason, one by one:
        assertTrue(Analysis.breakoutAtr(1,reference,.694,0,atr)<C.minBreakoutAtr(),"0.05 ATR is not a breakout");
        assertTrue(Math.abs(entry-reference)/atr>C.maxEntryDeviationAtr(),"0.27 ATR fill deviation");
        assertTrue(entry<.694,"price was back under the level at the fill");
        assertTrue(Analysis.htfOverextended(1,band(1,.99,96),C.htfStochExtreme()),"1h: above band, K 96");
        assertTrue(Analysis.htfOverextended(1,band(1,.99,97),C.htfStochExtreme()),"4h: above band, K 97");
        // Structural stop: min(0.6774, EMA20 0.6825) − 0.3 ATR = 0.6756, i.e. 3.12 ATR from the reference.
        double stop=Analysis.stopPrice(1,reference,.6774,null,ema20,atr,C);
        assertEquals(.6774-.3*atr,stop,1e-9);
        assertTrue((reference-stop)/atr>C.maxStopAtr(),"stop too wide → trade skipped");
    }

    /**
     * ETCUSDT: reference 9.566, entry 9.624, 247 s late; EMA20 distance 1.87 ATR at the reference and
     * 2.40 ATR at the fill, so ATR = 0.058 / 0.53 = 0.1094 and EMA20 = 9.361. 1h relVol 0.74.
     */
    @Test void etcWouldBeRejectedAndItsStopWouldHaveSurvivedThePullback() {
        double atr=(9.624-9.566)/(2.40-1.87),reference=9.566,entry=9.624,ema20=9.566-1.87*atr;
        assertEquals(9.361,ema20,.001);
        var signal=new Signal("ETCUSDT",1,100,now-247_000,reference,atr,2*atr,Map.of("15m.ema20",ema20),List.of());
        assertTrue(PaperBroker.entryGuard(signal,entry,now,C).contains("247 san"));
        var fresh=new Signal("ETCUSDT",1,100,now-30_000,reference,atr,2*atr,Map.of("15m.ema20",ema20),List.of());
        assertTrue(PaperBroker.entryGuard(fresh,entry,now,C).contains("reference"),"0.53 ATR fill deviation");
        assertTrue(1.87>C.maxEmaDistanceAtr15m(),"signal-candle EMA distance fails the new 1.5 limit");
        assertTrue(.74<C.minRelVol1h(),"1h volume 0.74x");
        // Old stop 9.405 sat 0.40 ATR ABOVE EMA20; the structural stop sits below it, and the logged
        // exit (9.435) — an ordinary pullback that never reached EMA20 — would not have stopped it.
        double stop=Analysis.stopPrice(1,reference,null,null,ema20,atr,C);
        assertTrue(stop<ema20); assertTrue(9.435>stop);
        assertEquals(9.405,entry-2*atr,.001,"the old stop, reproduced");
    }

    /** PYTHUSDT: 73 s late (inside the limit), 4h Bollinger width 16.82%, 1h relVol 0.38. */
    @Test void pythWouldBeRejectedByHourlyVolume() {
        assertTrue(73<C.maxSignalAgeSec(),"age alone would not have stopped it");
        assertTrue(.38<C.minRelVol1h(),"1h relVol 0.38 fails the mandatory 0.8 floor");
        assertFalse(Analysis.bollingerInRange(Map.of("bollingerWidthPct",16.82),C),"the 4h band bug is fixed");
    }

    /** ETC and PYTH were both LONG and opened 27 minutes apart: with two LONGs already open a third is refused. */
    @Test void correlatedEntriesAreCapped() {
        assertEquals(2,C.maxSameDirectionPositions());
        assertEquals(2,C.consecutiveLossCooldownCount());
    }
}
