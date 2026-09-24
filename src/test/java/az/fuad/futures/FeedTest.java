package az.fuad.futures;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static az.fuad.futures.Models.*;
import static org.junit.jupiter.api.Assertions.*;

class FeedTest {
    @TempDir Path dir;
    final ObjectMapper json=new ObjectMapper();
    static List<Candle> series(String interval,int n,long lastClose) {
        long d=CandleStore.duration(interval);
        List<Candle> c=new ArrayList<>();
        for(int i=0;i<n;i++) { long close=lastClose-(n-1-i)*d; c.add(new Candle(close-d+1,100,101,99,100,10,close)); }
        return c;
    }

    @Test void storeAppendsOnlyAContiguousNextCandle() {
        var store=new CandleStore();
        long close=CandleStore.lastClosedAt(System.currentTimeMillis(),"15m");
        assertEquals(CandleStore.Append.UNKNOWN,store.append("X","15m",series("15m",1,close).get(0)));
        store.put("X","15m",series("15m",300,close));
        var next=new Candle(close+1,100,101,99,100,10,close+900000);
        assertEquals(CandleStore.Append.APPENDED,store.append("X","15m",next));
        assertEquals(CandleStore.Append.DUPLICATE,store.append("X","15m",next));
        assertEquals(300,store.get("X","15m").size(),"capacity is kept");
        assertEquals(close+900000,store.lastClose("X","15m"));
        var skipped=new Candle(close+1800001,100,101,99,100,10,close+2700000);
        assertEquals(CandleStore.Append.GAP,store.append("X","15m",skipped),"a hole is never spliced over");
    }
    @Test void higherTimeframeBoundaryIsDerivedFromTheFastClose() {
        long hour=3600000L*500000;
        assertEquals(hour-1,CandleStore.lastClosedAt(hour-1,"1h"),"the 15m close on the hour closes the 1h candle too");
        assertEquals(hour-1,CandleStore.lastClosedAt(hour+900000-1,"1h"));
        assertEquals(hour+900000-1,CandleStore.lastClosedAt(hour+900000-1,"15m"));
    }
    @Test void parsesBinanceCombinedStreamMessages() throws Exception {
        var kline=(MarketStream.KlineEvent)MarketStream.parse(json,"""
            {"stream":"btcusdt@kline_15m","data":{"e":"kline","E":1790000000100,"s":"BTCUSDT","k":{"t":1789999100000,"T":1789999999999,
             "s":"BTCUSDT","i":"15m","o":"100.5","c":"101.25","h":"102","l":"99.5","v":"1234.5","x":true}}}""");
        assertEquals("BTCUSDT",kline.symbol()); assertEquals("15m",kline.interval()); assertTrue(kline.closed());
        assertEquals(new Candle(1789999100000L,100.5,102,99.5,101.25,1234.5,1789999999999L),kline.candle());
        var book=(MarketStream.BookEvent)MarketStream.parse(json,"""
            {"stream":"ethusdt@bookTicker","data":{"e":"bookTicker","u":1,"s":"ETHUSDT","b":"2500.1","B":"3","a":"2500.2","A":"4","T":1790000000001,"E":1790000000002}}""");
        assertEquals(2500.1,book.bid()); assertEquals(2500.2,book.ask()); assertEquals(1790000000002L,book.time());
        var mark=(MarketStream.MarkEvent)MarketStream.parse(json,"""
            {"stream":"ethusdt@markPrice@1s","data":{"e":"markPriceUpdate","E":1790000000003,"s":"ETHUSDT","p":"2500.15","i":"2500","P":"2500","r":"0.00010000","T":1790006400000}}""");
        assertEquals(2500.15,mark.mark()); assertEquals(.0001,mark.funding(),1e-12);
        assertNull(MarketStream.parse(json,"{\"result\":null,\"id\":1}"),"subscription acks are ignored");
        assertEquals("btcusdt@kline_1h",MarketStream.klineStream("BTCUSDT","1h"));
        assertEquals("btcusdt@bookTicker",MarketStream.bookStream("BTCUSDT"));
        assertEquals("btcusdt@markPrice@1s",MarketStream.markStream("BTCUSDT"));
        assertEquals("wss://fstream.binance.com/market/stream",Feed.defaults().marketStreamUrl());
    }
    @Test void feedDefaultsAndValidation() {
        var f=Feed.defaults();
        assertTrue(f.streamEnabled()); assertEquals(1800,f.maxWeightPerMinute()); assertEquals(2500,f.htfWaitMs());
        assertThrows(Exception.class,()->Feed.of(Map.of("max-weight-per-minute","5000")));
    }
    /** A streamed close is evaluated once, and a later REST scan of the same candle is a no-op. */
    @Test void aStreamedCloseIsEvaluatedOnceAcrossBothPaths() throws Exception {
        Settings settings=new Settings(true,"https://fapi.binance.com",dir.toString(),2000,.07,1,5,85,0,15,.0005,3,350,"BTCUSDT",.01,0,0);
        long close=CandleStore.lastClosedAt(System.currentTimeMillis(),"15m");
        var fast=series("15m",300,close); var hourly=series("1h",300,CandleStore.lastClosedAt(close,"1h")); var slow=series("4h",300,CandleStore.lastClosedAt(close,"4h"));
        int[] evaluations={0};
        class Client extends BinanceClient {
            int candleCalls;
            Client() { super(settings); }
            @Override public List<Contract> contracts() { return new ArrayList<>(List.of(new Contract("BTCUSDT",.001,.001,5))); }
            @Override public Map<String,Double> volumes() { return Map.of("BTCUSDT",1e9); }
            @Override public List<Candle> candles(String symbol,String interval) { candleCalls++; return interval.equals("15m")?fast:interval.equals("1h")?hourly:slow; }
            @Override public Quote quote(String symbol) { return new Quote(100,99.99,100.01,System.currentTimeMillis(),0); }
        }
        Analysis analysis=new Analysis() {
            @Override public Report evaluate(String symbol,List<Candle> f,List<Candle> h,List<Candle> s) {
                evaluations[0]++;
                assertEquals(close,f.get(f.size()-1).closeTime());
                return new Report(symbol,0,0,close,100,2,4,Map.of(),List.of(),null);
            }
        };
        var client=new Client(); var broker=new PaperBroker(settings);
        try {
            var scanner=new Scanner(settings,Feed.of(Map.of("htf-wait-ms","0")),client,analysis,broker,new DashboardState());
            // Seed through the private path the stream uses: evaluate straight from REST-seeded data.
            var storeField=Scanner.class.getDeclaredField("store"); storeField.setAccessible(true);
            var universeField=Scanner.class.getDeclaredField("universe"); universeField.setAccessible(true);
            var store=(CandleStore)storeField.get(scanner);
            store.put("BTCUSDT","15m",fast); store.put("BTCUSDT","1h",hourly); store.put("BTCUSDT","4h",slow);
            @SuppressWarnings("unchecked") var universe=(Map<String,Contract>)universeField.get(scanner);
            universe.put("BTCUSDT",new Contract("BTCUSDT",.001,.001,5));
            scanner.evaluateFromStore("BTCUSDT",close);
            scanner.evaluateFromStore("BTCUSDT",close);
            assertEquals(1,evaluations[0]);
            assertEquals(0,client.candleCalls,"current 1h/4h in the store means no REST call");
            scanner.scan();
            assertEquals(1,evaluations[0],"the REST fallback must not re-trade a candle the stream handled");
            scanner.stop();
        } finally { broker.shutdown(); }
    }
    @Test void streamQuoteNeedsBothHalvesAndAFreshMark() throws Exception {
        Settings settings=new Settings(false,"https://fapi.binance.com",dir.toString(),2000,.07,1,5,85,0,15,.0005,3,350,"",.01,0,0);
        var broker=new PaperBroker(settings);
        try {
            var scanner=new Scanner(settings,new BinanceClient(settings),new Analysis(),broker,new DashboardState());
            var handler=Scanner.class.getDeclaredClasses();
            long now=System.currentTimeMillis();
            assertNull(scanner.streamQuote("ETHUSDT",now));
            var books=Scanner.class.getDeclaredField("books"); books.setAccessible(true);
            var marks=Scanner.class.getDeclaredField("markState"); marks.setAccessible(true);
            @SuppressWarnings("unchecked") var b=(Map<String,double[]>)books.get(scanner);
            @SuppressWarnings("unchecked") var m=(Map<String,double[]>)marks.get(scanner);
            b.put("ETHUSDT",new double[]{99.9,100.1,now-60000});
            assertNull(scanner.streamQuote("ETHUSDT",now),"no mark yet");
            m.put("ETHUSDT",new double[]{100,.0001,now-500});
            var q=scanner.streamQuote("ETHUSDT",now);
            assertEquals(new Quote(100,99.9,100.1,now-500,.0001),q);
            assertTrue(q.fresh(now));
            m.put("ETHUSDT",new double[]{100,.0001,now-5000});
            assertNull(scanner.streamQuote("ETHUSDT",now),"a mark older than quote-max-age falls back to REST");
            assertNotNull(handler);
            scanner.stop();
        } finally { broker.shutdown(); }
    }
}
