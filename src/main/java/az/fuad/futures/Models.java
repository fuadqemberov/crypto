package az.fuad.futures;

import java.util.*;

public final class Models {
    private Models() {}
    public record Candle(long openTime, double open, double high, double low, double close,
                         double volume, long closeTime) {}
    public record Contract(String symbol, double step, double minQty, double minNotional) {}
    public record Quote(double mark, double bid, double ask, long time, double funding) {
        public boolean fresh() {
            return Double.isFinite(mark) && Double.isFinite(bid) && Double.isFinite(ask)
                    && Double.isFinite(funding) && mark > 0 && bid > 0 && ask >= bid
                    && time > System.currentTimeMillis() - 30000 && time <= System.currentTimeMillis() + 2000;
        }
        public double spreadBps() { return (ask-bid)/((bid+ask)/2)*10000; }
    }
    public record Signal(String symbol, int direction, double score, long candleTime, double reference,
                         double atr, double stopDistance, Map<String, Double> indicators, List<String> reasons) {}
    public record Check(String label, boolean passed, int points, int maximum, boolean mandatory, String detail) {}
    public record Report(String symbol, int direction, double score, long candleTime, double reference,
                         double atr, double stopDistance, Map<String, Double> indicators,
                         List<Check> checks, Signal signal) {
        public boolean qualified() { return signal != null; }
        public String side() { return direction == 1 ? "LONG" : direction == -1 ? "SHORT" : "NEUTRAL"; }
    }
    public record OpenDecision(boolean opened, String reason) {}
    public record ClosedTrade(String id, String symbol, int direction, long openedAt, long closedAt,
                              double entry, Double exit, double quantity, double netPnl, double score, String reason, double initialMargin) {
        public String outcome() { return netPnl > 0 ? "WON" : netPnl < 0 ? "LOST" : "FLAT"; }
        public String side() { return direction == 1 ? "LONG" : "SHORT"; }
    }
    public record WalletPoint(long time, double balance) {}
    public record Activity(long time, String type, String detail) {}
    public static class Position {
        public String id, symbol;
        public int direction, stage;
        public long openedAt;
        public double entry, quantity, initialQuantity, margin, stop, risk, tp1, tp2, tp3, realized;
        public Signal signal;
        public Position() {}
    }
    public static class Account {
        public double cash, fees, realized;
        public long sequence, closed, wins;
        public List<Position> positions = new ArrayList<>();
        public Map<String, Long> lastSignals = new HashMap<>();
        public Account() {}
    }
    public record Event(long sequence, long timestamp, String type, String detail, Account account) {}
}
