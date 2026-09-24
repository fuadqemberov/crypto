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
    @Test void confirmedPivotsAndTargetSpaceAreDirectional() {
        List<Candle> candles=new ArrayList<>();
        for(int i=0;i<9;i++) candles.add(new Candle(i,100,101,99,100,100,i+1));
        candles.set(2,new Candle(2,100,110,99,100,100,3));
        candles.set(5,new Candle(5,100,101,90,100,100,6));
        // This last high has no right-side confirmation and must not be used.
        candles.set(8,new Candle(8,100,120,99,100,100,9));
        var levels=Analysis.levels(candles,100);
        assertEquals(90,levels.support(),1e-9);
        assertEquals(110,levels.resistance(),1e-9);
        assertTrue(Analysis.hasTargetRoom(1,100,4,2,levels));
        assertFalse(Analysis.hasTargetRoom(1,102,4,2,levels));
        assertTrue(Analysis.hasTargetRoom(-1,100,4,2,levels));
        assertFalse(Analysis.hasTargetRoom(-1,98,4,2,levels));
        assertNull(Analysis.levels(candles,121).resistance());
    }
    @Test void momentumDiagnosticsMatchTheirDefinitions() {
        var candles=series(.1);
        var current=Analysis.indicators(candles);
        var previous=Analysis.indicators(candles.subList(0,candles.size()-1));
        assertEquals(current.get("macdLine")-current.get("macdSignal"),current.get("macdHistogram"),1e-12);
        assertEquals(current.get("rsi14")-previous.get("rsi14"),current.get("rsiChange"),1e-12);
        assertEquals(current.get("macdHistogram")-previous.get("macdHistogram"),current.get("macdHistogramChange"),1e-12);
        var report=new Analysis().evaluate("UP",candles,candles,candles);
        assertTrue(report.checks().stream().anyMatch(c->c.label().equals("RSI rejimi") && c.mandatory() && !c.passed()));
    }
    @Test void higherTimeframeExhaustionIsGatedAndMirroredForShorts() {
        // Values taken from the journal: ZAMAUSDT lost 22% of its margin out of a 4h blow-off that
        // every 15m gate waved through, while AKEUSDT and BCHUSDT must still pass.
        Map<String,Double> zamaSlow=Map.of("rsi14",93.75,"stochasticK",95.18,"emaDistanceAtr",5.41,"relativeVolume",7.11);
        Map<String,Double> akeSlow=Map.of("rsi14",70.48,"stochasticK",87.90,"emaDistanceAtr",2.00,"relativeVolume",3.06);
        assertFalse(Analysis.exhaustionAllowed(1,zamaSlow));
        assertTrue(Analysis.exhaustionAllowed(1,akeSlow));
        assertTrue(zamaSlow.get("emaDistanceAtr")>Strategy.defaults().maxEmaDistanceAtr4h());
        assertTrue(zamaSlow.get("relativeVolume")>Strategy.defaults().maxRelVol4h());
        assertTrue(akeSlow.get("emaDistanceAtr")<=Strategy.defaults().maxEmaDistanceAtr4h());
        assertTrue(akeSlow.get("relativeVolume")<=Strategy.defaults().maxRelVol4h());
        // The short side mirrors at 100: BCHUSDT passes, a capitulation low does not.
        assertTrue(Analysis.exhaustionAllowed(-1,Map.of("rsi14",39.44,"stochasticK",35.77)));
        assertFalse(Analysis.exhaustionAllowed(-1,Map.of("rsi14",6.2,"stochasticK",4.8)));
        // A saturated stochastic must not veto on its own: TRUMPUSDT's 1h stochastic was 1.8 in a
        // clean downtrend, and its only real problem was an offline stop.
        assertTrue(Analysis.exhaustionAllowed(-1,Map.of("rsi14",32.07,"stochasticK",16.13)));
        assertFalse(Analysis.exhaustionAllowed(0,akeSlow));
    }
    @Test void fundingIsDirectionalAndNeverAddsProbabilityPoints() {
        assertFalse(Analysis.fundingAllowed(1,.0004));
        assertTrue(Analysis.fundingAllowed(-1,.0004));
        assertFalse(Analysis.fundingAllowed(-1,-.0004));
        assertTrue(Analysis.fundingAllowed(1,-.0004));
        assertFalse(Analysis.fundingAllowed(1,-.002));
        assertFalse(Analysis.fundingAllowed(1,Double.NaN));
        long now=System.currentTimeMillis();
        var signal=new Signal("BTCUSDT",1,90,now,100,2,4,Map.of(),List.of());
        var report=new Report("BTCUSDT",1,90,now,100,2,4,Map.of(),List.of(),signal);
        var result=new Analysis().withFunding(report,new Quote(100,99.99,100.01,now,.0004));
        assertNull(result.signal()); assertEquals(90,result.score());
        assertTrue(result.checks().get(0).mandatory());
    }
    @Test void flatMarketIsNeutralAndFinite() {
        var m=Analysis.indicators(series(0)); assertEquals(50,m.get("rsi14")); assertEquals(0,m.get("adx14")); assertEquals(2,m.get("atr14"),1e-8);
        assertTrue(m.values().stream().allMatch(Double::isFinite)); assertNull(new Analysis().analyze("FLAT",series(0),series(0),series(0)));
    }
    @Test void weakVolumeAndMissingCandleStructureCannotBeCompensatedByScore() {
        var report=new Analysis().evaluate("UP",series(.2),series(.2),series(.2));
        for(String label:List.of("Həcm təsdiqi","Giriş strukturu")) {
            var check=report.checks().stream().filter(c->c.label().equals(label)).findFirst().orElseThrow();
            assertTrue(check.mandatory()); assertFalse(check.passed());
        }
        assertNull(report.signal());
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
