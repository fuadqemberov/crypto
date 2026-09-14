package az.fuad.futures;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static az.fuad.futures.Models.*;

class PaperBrokerTest {
    @TempDir Path dir;
    Settings settings() { return new Settings(false,"https://fapi.binance.com",dir.toString(),2000,.07,1,5,85,0,15,.0005,3,.15,350,""); }
    Signal signal(String symbol,int direction) { return new Signal(symbol,direction,95,System.currentTimeMillis()-10000,100,2,4,Map.of("rsi",55.0),List.of("test")); }
    Quote quote(double p) { return new Quote(p,p-.01,p+.01,System.currentTimeMillis(),.0001); }
    Contract contract(String s) { return new Contract(s,.001,.001,5); }
    @Test void nearbyResistancePreventsLongEntryWithoutDebitingCash() throws Exception {
        try(varBroker b=new varBroker(settings())) {
            var s=new Signal("BTCUSDT",1,95,System.currentTimeMillis()-10000,100,2,4,
                    Map.of("nearestResistance",103.0),List.of());
            var decision=b.bot.tryOpen(s,contract("BTCUSDT"),quote(100));
            assertFalse(decision.opened());
            assertTrue(decision.reason().contains("dəstək/müqavimət"));
            assertEquals(2000,b.bot.snapshot().cash);
        }
    }
    @Test void allocationFeesAndRestart() throws Exception {
        double cash;
        try(varBroker b=new varBroker(settings())) {
            assertTrue(b.bot.open(signal("BTCUSDT",1),contract("BTCUSDT"),quote(100)));
            Account a=b.bot.snapshot(); cash=a.cash;
            assertTrue(a.positions.get(0).margin<=140.00000001); assertTrue(a.positions.get(0).margin>139.9); assertEquals(1,a.positions.size());
            assertEquals(2000,a.cash+a.positions.get(0).margin+a.fees,1e-9);
            assertFalse(b.bot.open(signal("BTCUSDT",1),contract("BTCUSDT"),quote(100)));
        }
        try(varBroker b=new varBroker(settings())) { assertEquals(cash,b.bot.snapshot().cash); assertEquals(1,b.bot.snapshot().positions.size()); }
        assertTrue(Files.readString(dir.resolve("order_history.txt")).contains("OPEN"));
    }
    @Test void threeTimesLeverageAllocatesSevenPercentMarginAndPersistsIt() throws Exception {
        Settings s=new Settings(false,"https://fapi.binance.com",dir.toString(),2000,.07,3,5,85,0,15,.0005,3,.15,350,"");
        double margin;
        try(varBroker b=new varBroker(s)) {
            assertTrue(b.bot.open(signal("BTCUSDT",1),contract("BTCUSDT"),quote(100)));
            var p=b.bot.snapshot().positions.get(0); margin=p.margin;
            assertEquals(140,margin,.04);
            assertEquals(1859.79,b.bot.snapshot().cash,.04);
            assertEquals(margin*3,p.entry*p.quantity,1e-9);
            assertEquals(2000-margin-p.entry*p.quantity*.0005,b.bot.snapshot().cash,1e-9);
            b.bot.mark("BTCUSDT",quote(105));
            b.bot.mark("BTCUSDT",quote(113));
            assertEquals(margin,b.bot.view().trades().get(0).initialMargin(),1e-9);
        }
        try(varBroker b=new varBroker(s)) { assertEquals(margin,b.bot.view().trades().get(0).initialMargin(),1e-9); }
    }
    @Test void takeProfitsAndAccounting() throws Exception {
        try(varBroker b=new varBroker(settings())) {
            b.bot.open(signal("BTCUSDT",1),contract("BTCUSDT"),quote(100));
            b.bot.mark("BTCUSDT",quote(105));
            var p=b.bot.snapshot().positions.get(0); assertEquals(1,p.stage); assertEquals(p.entry,p.stop);
            assertEquals(p.initialQuantity*2/3,p.quantity,1e-9);
            b.bot.mark("BTCUSDT",quote(109)); assertEquals(2,b.bot.snapshot().positions.get(0).stage);
            b.bot.mark("BTCUSDT",quote(113));
            var a=b.bot.snapshot(); assertTrue(a.positions.isEmpty()); assertEquals(1,a.wins); assertEquals(2000+a.realized,a.cash,1e-8);
        }
    }
    @Test void shortAndProximityExit() throws Exception {
        try(varBroker b=new varBroker(settings())) {
            b.bot.open(signal("BTCUSDT",-1),contract("BTCUSDT"),quote(100));
            b.bot.mark("BTCUSDT",quote(103.5));
            assertTrue(b.bot.snapshot().positions.isEmpty()); assertTrue(b.bot.snapshot().cash<2000);
            assertTrue(Files.readString(dir.resolve("order_history.txt")).contains("SL_PROXIMITY"));
        }
    }
    @Test void shortTargetsAndGapStops() throws Exception {
        try(varBroker b=new varBroker(settings())) {
            b.bot.open(signal("BTCUSDT",-1),contract("BTCUSDT"),quote(100));
            b.bot.mark("BTCUSDT",quote(87)); assertTrue(b.bot.snapshot().positions.isEmpty()); assertEquals(1,b.bot.snapshot().wins);
            b.bot.open(signal("ETHUSDT",1),contract("ETHUSDT"),quote(100));
            b.bot.mark("ETHUSDT",quote(90)); assertTrue(b.bot.snapshot().positions.isEmpty());
            assertTrue(Files.readString(dir.resolve("order_history.txt")).contains("STOP_LOSS"));
        }
    }
    @Test void rejectsStaleQuotesAndDuplicateCandles() throws Exception {
        try(varBroker b=new varBroker(settings())) {
            Signal s=signal("BTCUSDT",1);
            assertFalse(b.bot.open(s,contract("BTCUSDT"),new Quote(100,100,100,1,0)));
            assertTrue(b.bot.open(s,contract("BTCUSDT"),quote(100)));
            b.bot.mark("BTCUSDT",new Quote(90,90,90,1,0)); assertEquals(1,b.bot.snapshot().positions.size());
            b.bot.mark("BTCUSDT",quote(90)); assertFalse(b.bot.open(s,contract("BTCUSDT"),quote(100)));
        }
    }
    @Test void crashTornTailRecoversButCompleteCorruptionFails() throws Exception {
        try(varBroker b=new varBroker(settings())) { b.bot.open(signal("BTCUSDT",1),contract("BTCUSDT"),quote(100)); }
        Files.writeString(dir.resolve("order_history.txt"),"{broken",StandardOpenOption.APPEND);
        try(varBroker b=new varBroker(settings())) { assertEquals(1,b.bot.snapshot().positions.size()); }
        Files.writeString(dir.resolve("order_history.txt"),"bad-line\n",StandardOpenOption.APPEND);
        assertThrows(Exception.class,()->new PaperBroker(settings()));
    }
    @Test void secondProcessCannotOwnAccount() throws Exception {
        try(varBroker b=new varBroker(settings())) { assertThrows(Exception.class,()->new PaperBroker(settings())); }
    }
    @Test void thresholdCannotBeBypassedAndEightyFiveIsInclusive() throws Exception {
        try(varBroker b=new varBroker(settings())) {
            for(double score:new double[]{84.999,Double.NaN,Double.POSITIVE_INFINITY,101}) {
                Signal s=new Signal("BTCUSDT",1,score,System.currentTimeMillis()-10000,100,2,4,Map.of(),List.of());
                assertFalse(b.bot.open(s,contract("BTCUSDT"),quote(100)),"Must reject score "+score);
            }
            Signal qualified=new Signal("BTCUSDT",1,85,System.currentTimeMillis()-10000,100,2,4,Map.of(),List.of());
            assertTrue(b.bot.open(qualified,contract("BTCUSDT"),quote(100)));
        }
        assertThrows(IllegalArgumentException.class,()->new Settings(false,"https://fapi.binance.com",dir.toString(),2000,.07,1,5,84.99,0,15,.0005,3,.15,350,""));
        assertThrows(IllegalArgumentException.class,()->new Settings(false,"https://fapi.binance.com",dir.toString(),2000,.07,1,5,Double.NaN,0,15,.0005,3,.15,350,""));
    }
    @Test void completedTradeProjectionIncludesEveryFillAndEntryFeeAcrossRestart() throws Exception {
        double net;
        try(varBroker b=new varBroker(settings())) {
            b.bot.open(signal("BTCUSDT",1),contract("BTCUSDT"),quote(100));
            b.bot.mark("BTCUSDT",quote(105));
            assertTrue(b.bot.view().trades().isEmpty(),"Partial TP is not a completed order");
            b.bot.mark("BTCUSDT",quote(113));
            var view=b.bot.view(); var trade=view.trades().get(0);
            net=trade.netPnl();
            assertEquals("WON",trade.outcome());
            assertEquals(view.account().realized,net,1e-9);
            assertEquals(2000+net,view.account().cash,1e-9);
        }
        try(varBroker b=new varBroker(settings())) {
            assertEquals(net,b.bot.view().trades().get(0).netPnl(),1e-9);
            b.bot.open(signal("ETHUSDT",-1),contract("ETHUSDT"),quote(100));
            b.bot.mark("ETHUSDT",quote(110));
            assertEquals("LOST",b.bot.view().trades().get(0).outcome());
            assertEquals(1,b.bot.view().losses());
        }
    }
    @Test void invalidQuotesAndUneconomicTargetsFailClosed() throws Exception {
        try(varBroker b=new varBroker(settings())) {
            assertFalse(b.bot.open(signal("BTCUSDT",1),contract("BTCUSDT"),new Quote(100,99,Double.POSITIVE_INFINITY,System.currentTimeMillis(),0)));
            assertFalse(b.bot.open(signal("BTCUSDT",1),contract("BTCUSDT"),new Quote(100,99,100,System.currentTimeMillis(),Double.NaN)));
            var signal=new Signal("BTCUSDT",1,95,System.currentTimeMillis()-10000,100,.01,.02,Map.of(),List.of());
            var decision=b.bot.tryOpen(signal,contract("BTCUSDT"),quote(100));
            assertFalse(decision.opened()); assertTrue(decision.reason().contains("risk/gəlir"));
            assertEquals(2000,b.bot.snapshot().cash);
        }
    }
    private static class varBroker implements AutoCloseable {
        final PaperBroker bot;
        varBroker(Settings s) throws Exception { bot=new PaperBroker(s); }
        public void close() throws Exception { bot.shutdown(); }
    }
}
