package az.fuad.futures;

import org.springframework.stereotype.Component;
import java.util.*;
import static az.fuad.futures.Models.*;

/** The UI reads snapshots only; refreshing the page never calls Binance or opens an order. */
@Component
public class DashboardState {
    public record Market(Report report, List<Candle> candles, long checkedAt, String decision, String detail) {
        public boolean stale() { return System.currentTimeMillis() - report.candleTime() > 930000; }
    }
    public record ScanStatus(boolean running, long startedAt, long finishedAt, int checked, int signals,
                             int filtered, int errors, String lastError) {}
    private final Map<String, Market> markets = new LinkedHashMap<>();
    private boolean running;
    private long startedAt, finishedAt;
    private int checked, signals, filtered, errors;
    private String lastError = "";

    public synchronized void begin() {
        running = true; startedAt = System.currentTimeMillis(); checked = 0; signals = 0; filtered = 0; errors = 0; lastError = "";
    }
    public synchronized void filtered() { filtered++; }
    public synchronized void record(Report report, List<Candle> candles, String decision, String detail) {
        checked++;
        if (decision.equals("OPENED") || decision.equals("EXECUTION_REJECTED")) signals++;
        markets.remove(report.symbol());
        markets.put(report.symbol(), new Market(report, List.copyOf(candles.subList(Math.max(0, candles.size()-80), candles.size())),
                System.currentTimeMillis(), decision, detail));
        while (markets.size() > 500) markets.remove(markets.keySet().iterator().next());
    }
    public synchronized void error(String message) { errors++; lastError = message; }
    public synchronized void finish() { running = false; finishedAt = System.currentTimeMillis(); }
    public synchronized ScanStatus status() { return new ScanStatus(running, startedAt, finishedAt, checked, signals, filtered, errors, lastError); }
    public synchronized List<Market> markets() {
        return markets.values().stream().sorted(Comparator.comparing(Market::stale)
                .thenComparing(Comparator.comparingDouble((Market m) -> m.report().score()).reversed())
                .thenComparing(m -> m.report().symbol())).toList();
    }
}
