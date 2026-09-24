package az.fuad.futures;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static az.fuad.futures.Models.*;

/**
 * In-memory closed candles per symbol and interval, seeded over REST once and then extended by
 * streamed closes. A candle that does not continue the series exactly is never spliced in: the
 * caller is told to resync, because an indicator computed over a hole is silently wrong.
 */
public final class CandleStore {
    public static final int CAPACITY=300;
    public enum Append { APPENDED, DUPLICATE, GAP, UNKNOWN }
    private final Map<String,List<Candle>> series=new ConcurrentHashMap<>();
    private static String key(String symbol,String interval) { return symbol+"|"+interval; }
    public static long duration(String interval) {
        return switch(interval) {
            case "1m" -> 60000L; case "5m" -> 300000L; case "15m" -> 900000L; case "1h" -> 3600000L; case "4h" -> 14400000L;
            default -> throw new IllegalArgumentException(interval);
        };
    }
    /**
     * Close time of the last candle of {@code interval} that is closed at {@code time}.
     * A 15m candle closing at 10:59:59.999 makes the 10:00 1h candle the current one.
     */
    public static long lastClosedAt(long time,String interval) {
        long d=duration(interval);
        return Math.floorDiv(time+1,d)*d-1;
    }
    public void put(String symbol,String interval,List<Candle> candles) {
        var copy=new ArrayList<>(candles.subList(Math.max(0,candles.size()-CAPACITY),candles.size()));
        series.put(key(symbol,interval),Collections.synchronizedList(copy));
    }
    public Append append(String symbol,String interval,Candle candle) {
        var list=series.get(key(symbol,interval));
        if(list==null) return Append.UNKNOWN;
        synchronized(list) {
            if(list.isEmpty()) return Append.GAP;
            Candle last=list.get(list.size()-1);
            if(candle.openTime()<=last.openTime()) return Append.DUPLICATE;
            if(candle.openTime()!=last.openTime()+duration(interval)) return Append.GAP;
            list.add(candle);
            if(list.size()>CAPACITY) list.remove(0);
            return Append.APPENDED;
        }
    }
    /** A snapshot copy, or null when the series was never seeded. */
    public List<Candle> get(String symbol,String interval) {
        var list=series.get(key(symbol,interval));
        if(list==null) return null;
        synchronized(list) { return List.copyOf(list); }
    }
    public long lastClose(String symbol,String interval) {
        var list=series.get(key(symbol,interval));
        if(list==null) return 0;
        synchronized(list) { return list.isEmpty()?0:list.get(list.size()-1).closeTime(); }
    }
    public void remove(String symbol) {
        for(String interval:List.of("15m","1h","4h")) series.remove(key(symbol,interval));
    }
}
