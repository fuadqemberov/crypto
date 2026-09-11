package az.fuad.futures;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static az.fuad.futures.Models.*;

class AnalysisTest {
    List<Candle> series(double step) {
        List<Candle> c=new ArrayList<>();
        for(int i=0;i<300;i++) { double p=100+i*step; c.add(new Candle(i*900000L,p,p+1,p-1,p,100,(i+1)*900000L-1)); }
        return c;
    }
    @Test void flatMarketIsNeutralAndFinite() {
        var m=Analysis.indicators(series(0)); assertEquals(50,m.get("rsi14")); assertEquals(0,m.get("adx14")); assertEquals(2,m.get("atr14"),1e-8);
        assertTrue(m.values().stream().allMatch(Double::isFinite)); assertNull(new Analysis().analyze("FLAT",series(0),series(0),series(0)));
    }
    @Test void directionalIndicatorsTrackKnownSeries() {
        var up=Analysis.indicators(series(.2)); var down=Analysis.indicators(series(-.2));
        assertEquals(100,up.get("rsi14")); assertEquals(0,down.get("rsi14")); assertTrue(up.get("adx14")>99);
        assertTrue(up.get("ema20")>up.get("ema50")); assertTrue(down.get("ema20")<down.get("ema50"));
    }
    @Test void scoreBoundedAndOpposingHigherTimeframeRejected() {
        var a=new Analysis(); var s=a.evaluate("UP",series(.2),series(.2),series(.2));
        assertTrue(s.score()>=0 && s.score()<=100); assertEquals(1,s.direction());
        assertEquals(100,s.checks().stream().mapToInt(Check::maximum).sum());
        assertNull(s.signal(),"RSI=100 must now fail the exhaustion gate even in a strong trend");
        assertTrue(s.checks().stream().anyMatch(c->c.mandatory() && !c.passed()));
        assertNull(a.analyze("MIX",series(.2),series(.2),series(-.2)));
    }
    @Test void malformedCandlesAndNonFiniteValuesAreRejected() {
        var candles=series(.1);
        candles.set(299,new Candle(0,100,101,99,Double.NaN,100,1));
        assertThrows(IllegalArgumentException.class,()->Analysis.indicators(candles));
        assertThrows(IllegalArgumentException.class,()->Analysis.ema(new double[0],20));
    }
    @Test void rejectedMarketsStillExposeDetailedDiagnostics() {
        var report=new Analysis().evaluate("FLAT",series(0),series(0),series(0));
        assertFalse(report.qualified()); assertEquals("NEUTRAL",report.side());
        assertTrue(report.indicators().containsKey("4h.ema50SlopeAtr"));
        assertTrue(report.indicators().containsKey("15m.bollingerWidthPct"));
        assertTrue(report.checks().size()>15);
    }
}
