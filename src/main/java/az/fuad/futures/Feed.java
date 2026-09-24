package az.fuad.futures;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import java.util.Map;

/**
 * Market-data delivery. With the stream on, a closed 15m candle is pushed by Binance and evaluated
 * within about a second; the REST scan only runs as a fallback while the stream is down.
 */
@ConfigurationProperties("bot.feed")
public record Feed(
        @DefaultValue("true") boolean streamEnabled,
        /** Binance routes klines and mark prices here… */
        @DefaultValue("wss://fstream.binance.com/market/stream") String marketStreamUrl,
        /** …and the book ticker here. The legacy /stream accepts subscriptions but stays silent. */
        @DefaultValue("wss://fstream.binance.com/public/stream") String publicStreamUrl,
        /** Symbols per WebSocket connection (3 kline streams each; Binance allows 1024 streams). */
        @DefaultValue("100") int symbolsPerConnection,
        /** A connection with no message for this long is considered dead and reconnected. */
        @DefaultValue("30000") long staleMs,
        /** How long a 15m close waits for the matching 1h/4h close before fetching it over REST. */
        @DefaultValue("2500") long htfWaitMs,
        @DefaultValue("4") int evaluationThreads,
        /** A streamed quote older than this is not used; the monitor falls back to REST. */
        @DefaultValue("3000") long quoteMaxAgeMs,
        @DefaultValue("5000") long restMonitorIntervalMs,
        /** Pause REST calls when Binance reports this much used weight in the current minute (limit 2400). */
        @DefaultValue("1800") int maxWeightPerMinute) {
    public Feed {
        if (marketStreamUrl == null || marketStreamUrl.isBlank()) marketStreamUrl = "wss://fstream.binance.com/market/stream";
        if (publicStreamUrl == null || publicStreamUrl.isBlank()) publicStreamUrl = "wss://fstream.binance.com/public/stream";
        if (symbolsPerConnection < 1 || symbolsPerConnection > 300 || staleMs < 5000 || htfWaitMs < 0
                || evaluationThreads < 1 || evaluationThreads > 32 || quoteMaxAgeMs < 500
                || restMonitorIntervalMs < 1000 || maxWeightPerMinute < 100 || maxWeightPerMinute > 2400)
            throw new IllegalArgumentException("Yanlış bot.feed parametrləri");
    }
    public static Feed defaults() { return of(Map.of()); }
    public static Feed of(Map<String, String> overrides) {
        var source = new java.util.HashMap<String, String>();
        overrides.forEach((k, v) -> source.put("bot.feed." + k, v));
        return new Binder(new MapConfigurationPropertySource(source)).bindOrCreate("bot.feed", Feed.class);
    }
}
