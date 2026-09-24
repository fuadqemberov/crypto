package az.fuad.futures;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static az.fuad.futures.Models.*;
import static org.junit.jupiter.api.Assertions.*;

/** Broker-side rules: structural stop at fill, SL proximity, portfolio limits, cooldown, limit entries, journal fields. */
class PortfolioAndJournalTest {
    @TempDir Path dir;
    final ObjectMapper json=new ObjectMapper();
    Settings settings() { return new Settings(false,"https://fapi.binance.com",dir.toString(),2000,.07,3,5,85,0,15,.0005,3,350,"",.005,0,0); }
    Signal signal(String symbol,int direction) { return signal(symbol,direction,Map.of()); }
    Signal signal(String symbol,int direction,Map<String,Double> indicators) {
        return new Signal(symbol,direction,95,System.currentTimeMillis()-10000,100,2,4,indicators,List.of());
    }
    Quote quote(double p) { return new Quote(p,p-.01,p+.01,System.currentTimeMillis(),.0001); }
    Contract contract(String s) { return new Contract(s,.001,.001,5); }
    PaperBroker broker(Map<String,String> overrides) throws Exception { return new PaperBroker(settings(),Strategy.of(overrides)); }
    List<JsonNode> events() throws Exception {
        List<JsonNode> result=new ArrayList<>();
        for(String line:Files.readAllLines(dir.resolve("order_history.txt"))) result.add(json.readTree(line));
        return result;
    }

    @Test void structuralStopLevelFromTheSignalIsKeptAndDollarRiskStaysFixed() throws Exception {
        var b=broker(Map.of());
        try {
            // Stop 2.8 ATR below the reference: the fill must use that level, and the lot must shrink so risk stays 0.5%.
            assertTrue(b.open(signal("BTCUSDT",1,Map.of("stop.price",94.4)),contract("BTCUSDT"),quote(100)));
            var p=b.snapshot().positions.get(0);
            assertEquals(94.4,p.stop,1e-9);
            assertEquals(p.entry-94.4,p.risk,1e-9);
            assertEquals(p.entry+p.risk,p.tp1,1e-9); assertEquals(p.entry+3*p.risk,p.tp3,1e-9);
            assertEquals(2000*.005,p.risk*p.quantity,.05);
        } finally { b.shutdown(); }
    }
    @Test void aStopWiderThanTheLimitAtTheFillIsSkipped() throws Exception {
        var b=broker(Map.of());
        try {
            var decision=b.tryOpen(signal("BTCUSDT",1,Map.of("stop.price",93.9)),contract("BTCUSDT"),quote(100));
            assertFalse(decision.opened()); assertTrue(decision.reason().contains("ATR; limit 3"),decision.reason());
            assertEquals(2000,b.snapshot().cash);
        } finally { b.shutdown(); }
    }
    @Test void slProximityCanBeDisabledSoOnlyTheRealStopCloses() throws Exception {
        var b=broker(Map.of("sl-proximity-enabled","false"));
        try {
            b.open(signal("BTCUSDT",1),contract("BTCUSDT"),quote(100));
            b.mark("BTCUSDT",quote(96.01));
            assertEquals(1,b.snapshot().positions.size(),"0.01 above the stop is not a stop when proximity is off");
            b.mark("BTCUSDT",quote(95.99));
            assertEquals("STOP_LOSS",b.view().trades().get(0).reason());
        } finally { b.shutdown(); }
    }
    @Test void sameDirectionPositionsAreCapped() throws Exception {
        var b=broker(Map.of());
        try {
            assertTrue(b.open(signal("BTCUSDT",1),contract("BTCUSDT"),quote(100)));
            assertTrue(b.open(signal("ETHUSDT",1),contract("ETHUSDT"),quote(100)));
            var third=b.tryOpen(signal("SOLUSDT",1),contract("SOLUSDT"),quote(100));
            assertFalse(third.opened()); assertTrue(third.reason().contains("Eyni istiqamətdə"));
            assertTrue(b.open(signal("XRPUSDT",-1),contract("XRPUSDT"),quote(100)),"The opposite side is not capped by it");
        } finally { b.shutdown(); }
    }
    @Test void consecutiveLossesStartACooldownThatSurvivesRestart() throws Exception {
        var b=broker(Map.of());
        try {
            b.open(signal("BTCUSDT",1),contract("BTCUSDT"),quote(100)); b.mark("BTCUSDT",quote(95));
            assertEquals(1,b.snapshot().lossStreak);
            b.open(signal("ETHUSDT",1),contract("ETHUSDT"),quote(100)); b.mark("ETHUSDT",quote(95));
            assertTrue(b.snapshot().cooldownUntil>System.currentTimeMillis()+239*60000L);
            var blocked=b.tryOpen(signal("SOLUSDT",-1),contract("SOLUSDT"),quote(100));
            assertFalse(blocked.opened()); assertTrue(blocked.reason().contains("fasilə"));
        } finally { b.shutdown(); }
        var restarted=broker(Map.of());
        try { assertFalse(restarted.open(signal("ADAUSDT",1),contract("ADAUSDT"),quote(100))); }
        finally { restarted.shutdown(); }
    }
    @Test void aWinResetsTheLossStreak() throws Exception {
        var b=broker(Map.of());
        try {
            b.open(signal("BTCUSDT",1),contract("BTCUSDT"),quote(100)); b.mark("BTCUSDT",quote(95));
            b.open(signal("ETHUSDT",1),contract("ETHUSDT"),quote(100));
            b.mark("ETHUSDT",quote(105)); b.mark("ETHUSDT",quote(109)); b.mark("ETHUSDT",quote(113));
            assertEquals(0,b.snapshot().lossStreak); assertEquals(0,b.snapshot().cooldownUntil);
        } finally { b.shutdown(); }
    }
    @Test void closedTradesCarryMfeMaeAndHoldingTimeInTheJournal() throws Exception {
        var b=broker(Map.of());
        try {
            b.open(signal("BTCUSDT",1),contract("BTCUSDT"),quote(100));
            double entry=b.snapshot().positions.get(0).entry,risk=b.snapshot().positions.get(0).risk;
            b.mark("BTCUSDT",quote(102)); b.mark("BTCUSDT",quote(97)); b.mark("BTCUSDT",quote(95));
            var close=events().get(events().size()-1);
            assertEquals("STOP_LOSS",close.get("type").asText());
            var data=close.get("data");
            assertEquals((102-entry)/risk,data.get("mfeR").asDouble(),1e-9);
            assertEquals((entry-95)/risk,data.get("maeR").asDouble(),1e-9);
            assertTrue(data.get("holdingMinutes").asDouble()>=0);
            assertTrue(data.get("resultR").asDouble()<-1);
            assertTrue(close.get("detail").asText().contains("mfeR="));
            // Lines without structured data keep the exact old shape.
            assertFalse(events().get(0).has("data"));
        } finally { b.shutdown(); }
        var restarted=broker(Map.of());
        try { assertEquals(1,restarted.snapshot().closed); } finally { restarted.shutdown(); }
    }
    @Test void rejectedCandidatesAreJournalledWithFiltersReferenceAndTime() throws Exception {
        var b=broker(Map.of());
        try {
            b.recordRejected("AEROUSDT","LONG",List.of("Breakout təsdiqi","HTF uzanma filtri"),.6943,1790000000000L,"ANALYSIS");
            var event=events().get(events().size()-1);
            assertEquals("REJECTED",event.get("type").asText());
            assertEquals("AEROUSDT",event.get("data").get("symbol").asText());
            assertEquals(2,event.get("data").get("filters").size());
            assertEquals(.6943,event.get("data").get("reference").asDouble());
            assertEquals(1790000000000L,event.get("data").get("candleTime").asLong());
            assertTrue(b.view().activity().stream().noneMatch(a->a.type().equals("REJECTED")),"Rejections must not flood the dashboard feed");
        } finally { b.shutdown(); }
        var off=broker(Map.of("log-rejected","false"));
        try {
            long before=off.snapshot().sequence;
            off.recordRejected("X","LONG",List.of("a"),1,1,"ANALYSIS");
            assertEquals(before,off.snapshot().sequence);
        } finally { off.shutdown(); }
    }
    @Test void limitModeRestsAtEmaPlusOffsetAndFillsOnlyWhenTouched() throws Exception {
        var b=broker(Map.of("entry-mode","LIMIT"));
        try {
            var s=signal("BTCUSDT",1,Map.of("15m.ema20",98.0));
            var decision=b.tryOpen(s,contract("BTCUSDT"),quote(100));
            assertFalse(decision.opened()); assertTrue(decision.pending());
            assertEquals(99,b.snapshot().pending.get(0).limit,1e-9);
            assertEquals(List.of("BTCUSDT"),b.symbols(),"The monitor must keep quoting a resting order");
            b.mark("BTCUSDT",quote(99.5));
            assertTrue(b.snapshot().positions.isEmpty());
            b.mark("BTCUSDT",quote(98.95));
            var p=b.snapshot().positions.get(0);
            assertEquals(98.96,p.entry,1e-9); assertTrue(b.snapshot().pending.isEmpty());
            assertEquals(96,p.stop,1e-9);
        } finally { b.shutdown(); }
    }
    @Test void anUnfilledLimitIsCancelledAfterItsCandles() throws Exception {
        var b=broker(Map.of("entry-mode","LIMIT","limit-timeout-candles","1","max-signal-age-sec","1000"));
        try {
            var s=new Signal("BTCUSDT",1,95,System.currentTimeMillis()-905000,100,2,4,Map.of("15m.ema20",98.0),List.of());
            var decision=b.tryOpen(s,contract("BTCUSDT"),quote(100));
            assertFalse(decision.opened()); assertFalse(decision.pending());
            assertTrue(b.snapshot().pending.isEmpty());
            assertTrue(events().stream().anyMatch(e->e.get("type").asText().equals("LIMIT_CANCELLED")));
        } finally { b.shutdown(); }
    }
}
