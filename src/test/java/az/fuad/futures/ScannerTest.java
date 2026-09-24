package az.fuad.futures;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static az.fuad.futures.Models.*;
import static org.junit.jupiter.api.Assertions.*;

class ScannerTest {
    @TempDir Path directory;
    @Test void scannerNeverRequestsEntryQuoteBelowThresholdAndAcceptsEightyFive() throws Exception {
        for(double score:new double[]{84.999,85}) {
            Settings settings=new Settings(true,"https://fapi.binance.com",directory.resolve("score-"+score).toString(),
                    2000,.07,1,5,85,0,15,.0005,3,350,"BTCUSDT",.01,0,0);
            long time=System.currentTimeMillis()-10000;
            var candle=new Candle(time-899999,99,101,98,100,100,time);
            class Client extends BinanceClient {
                int quotes;
                Client() { super(settings); }
                @Override public List<Contract> contracts() { return new ArrayList<>(List.of(new Contract("BTCUSDT",.001,.001,5))); }
                @Override public Map<String,Double> volumes() { return Map.of("BTCUSDT",100000000.0); }
                @Override public List<Candle> candles(String symbol,String interval) { return List.of(candle); }
                @Override public Quote quote(String symbol) { quotes++; return new Quote(100,99.99,100.01,System.currentTimeMillis(),0); }
            }
            Analysis analysis=new Analysis() {
                @Override public Report evaluate(String symbol,List<Candle> fast,List<Candle> hourly,List<Candle> slow) {
                    Signal signal=new Signal(symbol,1,score,time,100,2,4,Map.of(),List.of());
                    return new Report(symbol,1,score,time,100,2,4,Map.of(),List.of(),signal);
                }
            };
            var client=new Client(); var state=new DashboardState(); var broker=new PaperBroker(settings);
            try {
                var scanner=new Scanner(settings,client,analysis,broker,state);
                scanner.scan();
                int expected=score>=85?1:0;
                assertEquals(expected,client.quotes);
                assertEquals(expected,broker.snapshot().positions.size());
                assertEquals(score>=85?"OPENED":"LOW_SCORE",state.markets().get(0).decision());
                scanner.scan();
                assertEquals(expected,client.quotes,"Same closed candle must not be traded twice");
            } finally { broker.shutdown(); }
        }
    }
    @Test void altcoinLongIsJournalledAsRejectedWhenBitcoinTrendsDown() throws Exception {
        Settings settings=new Settings(true,"https://fapi.binance.com",directory.resolve("btc").toString(),
                2000,.07,1,5,85,0,15,.0005,3,350,"ETHUSDT",.01,0,0);
        long time=System.currentTimeMillis()-10000;
        List<Candle> falling=new ArrayList<>();
        for(int i=0;i<250;i++) { double p=200-i*.1; falling.add(new Candle(time-(250L-i)*3600000,p,p+.5,p-.5,p,10,time-(249L-i)*3600000)); }
        class Client extends BinanceClient {
            int quotes;
            Client() { super(settings); }
            @Override public List<Contract> contracts() { return new ArrayList<>(List.of(new Contract("ETHUSDT",.001,.001,5))); }
            @Override public Map<String,Double> volumes() { return Map.of("ETHUSDT",100000000.0); }
            @Override public List<Candle> candles(String symbol,String interval) {
                return symbol.equals("BTCUSDT")?falling:List.of(new Candle(time-899999,99,101,98,100,100,time));
            }
            @Override public Quote quote(String symbol) { quotes++; return new Quote(100,99.99,100.01,System.currentTimeMillis(),0); }
        }
        Analysis analysis=new Analysis() {
            @Override public Report evaluate(String symbol,List<Candle> fast,List<Candle> hourly,List<Candle> slow) {
                Signal signal=new Signal(symbol,1,100,time,100,2,4,Map.of(),List.of());
                return new Report(symbol,1,100,time,100,2,4,Map.of(),List.of(),signal);
            }
        };
        var client=new Client(); var state=new DashboardState(); var broker=new PaperBroker(settings);
        try {
            new Scanner(settings,client,analysis,broker,state).scan();
            assertEquals(0,client.quotes,"A BTC-blocked signal must not even request an entry quote");
            assertTrue(broker.snapshot().positions.isEmpty());
            assertEquals("BLOCKED",state.markets().get(0).decision());
            String journal=java.nio.file.Files.readString(directory.resolve("btc").resolve("order_history.txt"));
            assertTrue(journal.contains("\"type\":\"REJECTED\""));
            assertTrue(journal.contains("BTC filtri"));
        } finally { broker.shutdown(); }
    }
}
