package az.fuad.futures;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import java.io.InputStream;
import java.util.*;
import static az.fuad.futures.Models.*;
import static org.junit.jupiter.api.Assertions.*;

/** Analysis-side rules: config source, entry guard, breakout, over-extension, structural stop, BTC filter, quality. */
class SignalQualityTest {
    static final Strategy C=Strategy.defaults();
    List<Candle> series(double step) {
        List<Candle> c=new ArrayList<>();
        for(int i=0;i<300;i++) { double p=100+i*step; c.add(new Candle(i*900000L,p,p+1,p-1,p,100,(i+1)*900000L-1)); }
        return c;
    }
    static Signal signal(int d,long candleTime,double reference,double atr,Map<String,Double> indicators) {
        return new Signal("XUSDT",d,100,candleTime,reference,atr,2*atr,indicators,List.of());
    }

    @Test void defaultsMatchTheSpecAndTheShippedPropertiesFile() throws Exception {
        assertEquals(.25,C.maxEntryDeviationAtr()); assertEquals(90,C.maxSignalAgeSec());
        assertEquals(.2,C.minBreakoutAtr()); assertEquals(Strategy.EntryMode.MARKET,C.entryMode());
        assertEquals(.5,C.limitOffsetAtr()); assertEquals(3,C.limitTimeoutCandles());
        assertEquals(1.5,C.maxEmaDistanceAtr15m()); assertEquals(90,C.htfStochExtreme());
        assertEquals(.3,C.stopBufferAtr()); assertEquals(3.0,C.maxStopAtr()); assertTrue(C.structuralStop());
        assertEquals(.8,C.minRelVol1h()); assertEquals(.05,C.slProximityAtr()); assertTrue(C.slProximityEnabled());
        assertTrue(C.btcFilterEnabled()); assertEquals(2,C.maxSameDirectionPositions());
        assertEquals(2,C.consecutiveLossCooldownCount()); assertEquals(240,C.cooldownMinutes());
        // application.properties must not silently diverge from the code defaults.
        var properties=new Properties();
        try(InputStream in=getClass().getResourceAsStream("/application.properties")) { properties.load(in); }
        Map<String,String> map=new HashMap<>();
        properties.forEach((k,v)->map.put(k.toString(),v.toString()));
        var bound=new Binder(new MapConfigurationPropertySource(map)).bindOrCreate("bot.strategy",Strategy.class);
        assertEquals(C,bound);
    }
    @Test void invalidStrategyValuesFailAtStartup() {
        assertThrows(Exception.class,()->Strategy.of(Map.of("min-stop-atr","4","max-stop-atr","3")));
        assertThrows(Exception.class,()->Strategy.of(Map.of("rsi-gate-max15m","40")));
        assertThrows(Exception.class,()->Strategy.of(Map.of("max-entry-deviation-atr","-1")));
    }
    @Test void entryGuardRejectsOldSignalsFarFillsLostBreakoutsAndStretchedFills() {
        long now=System.currentTimeMillis();
        var fresh=signal(1,now-30000,100,2,Map.of());
        assertNull(PaperBroker.entryGuard(fresh,100.4,now,C));
        assertTrue(PaperBroker.entryGuard(signal(1,now-91000,100,2,Map.of()),100,now,C).contains("köhnədir"));
        assertTrue(PaperBroker.entryGuard(fresh,100.6,now,C).contains("reference"));
        assertTrue(PaperBroker.entryGuard(fresh,99.4,now,C).contains("reference"),"Deviation is symmetric");
        var breakout=signal(1,now-30000,100,2,Map.of("entry.breakoutLevel",99.9));
        assertTrue(PaperBroker.entryGuard(breakout,99.85,now,C).contains("Breakout"));
        assertNull(PaperBroker.entryGuard(breakout,100.1,now,C));
        var shortBreakout=signal(-1,now-30000,100,2,Map.of("entry.breakoutLevel",100.1));
        assertTrue(PaperBroker.entryGuard(shortBreakout,100.2,now,C).contains("Breakout"));
        var stretched=signal(1,now-30000,100,2,Map.of("15m.ema20",97.2));
        assertNull(PaperBroker.entryGuard(stretched,100.2,now,C),"(100.2-97.2)/2 = 1.5 is exactly the limit");
        assertTrue(PaperBroker.entryGuard(stretched,100.3,now,C).contains("EMA20"),"1.55 ATR is past it");
        assertNull(PaperBroker.entryGuard(signal(1,now-30000,100,2,Map.of("15m.ema20",97.1)),100.0,now,C));
    }
    @Test void breakoutStrengthIsMeasuredInAtrBeyondTheLevel() {
        assertEquals(.05,Analysis.breakoutAtr(1,.6943,.694,0,.006),1e-9);
        assertEquals(.5,Analysis.breakoutAtr(-1,99,0,100,2),1e-9);
        assertTrue(Analysis.breakoutAtr(1,99,100,0,2)<0);
    }
    @Test void higherTimeframeOverExtensionNeedsBothBandAndStochastic() {
        Map<String,Double> blowOff=Map.of("close",1.0,"bollingerUpper",.99,"bollingerLower",.9,"stochasticK",96.0);
        assertTrue(Analysis.htfOverextended(1,blowOff,90));
        assertFalse(Analysis.htfOverextended(1,Map.of("close",1.0,"bollingerUpper",.99,"bollingerLower",.9,"stochasticK",85.0),90));
        assertFalse(Analysis.htfOverextended(1,Map.of("close",.98,"bollingerUpper",.99,"bollingerLower",.9,"stochasticK",99.0),90));
        assertTrue(Analysis.htfOverextended(-1,Map.of("close",.89,"bollingerUpper",.99,"bollingerLower",.9,"stochasticK",4.0),90));
        assertFalse(Analysis.htfOverextended(-1,Map.of("close",.89,"bollingerUpper",.99,"bollingerLower",.9,"stochasticK",15.0),90));
    }
    @Test void bollingerWidthIsCheckedOnFourHoursToo() {
        assertFalse(Analysis.bollingerInRange(Map.of("bollingerWidthPct",16.82),C));
        assertTrue(Analysis.bollingerInRange(Map.of("bollingerWidthPct",14.99),C));
        assertFalse(Analysis.bollingerInRange(Map.of("bollingerWidthPct",.3),C));
    }
    @Test void structuralStopSitsBelowSupportAndEmaWithBufferAndFloor() {
        // LONG: lower of support (95) and EMA (97) minus 0.3 ATR.
        assertEquals(95-.6,Analysis.stopPrice(1,100,95.0,null,97,2,C),1e-9);
        assertEquals(97-.6,Analysis.stopPrice(1,100,98.0,null,97,2,C),1e-9);
        assertEquals(97-.6,Analysis.stopPrice(1,100,null,null,97,2,C),1e-9);
        // Never tighter than min-stop-atr (1 ATR).
        assertEquals(98,Analysis.stopPrice(1,100,99.5,null,99.8,2,C),1e-9);
        // SHORT mirrors.
        assertEquals(105+.6,Analysis.stopPrice(-1,100,null,105.0,103,2,C),1e-9);
        // Legacy mode keeps the fixed ATR multiple.
        assertEquals(96,Analysis.stopPrice(1,100,90.0,null,97,2,Strategy.of(Map.of("structural-stop","false"))),1e-9);
    }
    @Test void reasonTextsCarryTheConfiguredLimitsNotHardcodedOnes() {
        var custom=Strategy.of(Map.of("max-ema-distance-atr15m","1.7","rsi-gate-max15m","74","rsi-max1h","71","min-rel-vol1h","0.9","min-adx","22"));
        var report=new Analysis(custom).evaluate("UP",series(.2),series(.2),series(.2));
        Map<String,String> detail=new HashMap<>();
        report.checks().forEach(c->detail.put(c.label(),c.detail()));
        assertTrue(detail.get("Gec giriş filtri").contains("limit 1.7"));
        assertTrue(detail.get("RSI rejimi").contains("LONG 50–74 / 50–71, SHORT 26–50 / 29–50"),detail.get("RSI rejimi"));
        assertTrue(detail.get("1h həcm təsdiqi").contains("0.9x"));
        assertTrue(detail.get("ADX və DI").contains("22"));
        assertTrue(detail.get("1h RSI təsdiqi").contains("50–71"));
        assertTrue(report.checks().stream().noneMatch(c->c.detail().contains("50–76")));
    }
    @Test void newGatesAreMandatoryAndReportedByLabel() {
        var report=new Analysis().evaluate("UP",series(.2),series(.2),series(.2));
        var labels=report.checks().stream().filter(Check::mandatory).map(Check::label).toList();
        assertTrue(labels.containsAll(List.of("HTF uzanma filtri","Breakout təsdiqi","1h həcm təsdiqi","Stop məsafəsi")));
        assertTrue(Analysis.failedGates(report).contains("Həcm təsdiqi"));
        assertFalse(Analysis.failedGates(report).contains("1h həcm təsdiqi"),"Flat volume is 1.0x, above the 0.8x 1h floor");
        assertTrue(report.indicators().containsKey("stop.price"));
        assertTrue(report.indicators().containsKey("quality.score"));
        assertEquals(100,report.checks().stream().mapToInt(Check::maximum).sum());
    }
    @Test void btcFilterBlocksAltcoinsAgainstBitcoinAndFailsClosed() {
        var analysis=new Analysis();
        long now=System.currentTimeMillis();
        var signal=new Signal("ETHUSDT",1,100,now,100,2,4,Map.of(),List.of());
        var report=new Report("ETHUSDT",1,100,now,100,2,4,Map.of(),List.of(),signal);
        assertNotNull(analysis.withBtcFilter(report,1).signal());
        assertNull(analysis.withBtcFilter(report,-1).signal());
        assertNull(analysis.withBtcFilter(report,null).signal(),"Unknown BTC state must not open trades");
        var btc=new Report("BTCUSDT",1,100,now,100,2,4,Map.of(),List.of(),new Signal("BTCUSDT",1,100,now,100,2,4,Map.of(),List.of()));
        assertNotNull(analysis.withBtcFilter(btc,-1).signal(),"BTC itself is not filtered by BTC");
        var shortSignal=new Report("ETHUSDT",-1,100,now,100,2,4,Map.of(),List.of(),new Signal("ETHUSDT",-1,100,now,100,2,4,Map.of(),List.of()));
        assertNotNull(analysis.withBtcFilter(shortSignal,-1).signal());
        assertNotNull(new Analysis(Strategy.of(Map.of("btc-filter-enabled","false"))).withBtcFilter(report,-1).signal());
    }
    @Test void qualityScoreSeparatesAStretchedBreakoutFromACleanPullback() {
        Map<String,Double> clean15=Map.of("emaDistanceAtr",.3,"relativeVolume",2.4);
        Map<String,Double> stretched15=Map.of("emaDistanceAtr",1.45,"relativeVolume",1.25);
        Map<String,Double> strongHtf=Map.of("relativeVolume",1.6,"rsi14",60.0);
        Map<String,Double> weakHtf=Map.of("relativeVolume",.4,"rsi14",80.0);
        double clean=Analysis.quality(C,1,clean15,strongHtf,strongHtf,true,0,1.2).get("quality.score");
        double stretched=Analysis.quality(C,1,stretched15,weakHtf,weakHtf,false,.21,2.9).get("quality.score");
        assertTrue(clean>80,"clean="+clean);
        assertTrue(stretched<35,"stretched="+stretched);
        assertEquals(0,Analysis.quality(C,0,clean15,strongHtf,strongHtf,true,0,1).get("quality.score"));
    }
}
