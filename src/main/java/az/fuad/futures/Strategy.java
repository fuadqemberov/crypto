package az.fuad.futures;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import java.util.Map;

/**
 * Every strategy threshold in one place. Gate logic and the reason texts written to the journal both
 * read from here, so a text can never again claim a limit the code does not apply.
 * A SHORT always uses the mirror of a LONG limit at 50 (RSI) or 100 (stochastic).
 */
@ConfigurationProperties("bot.strategy")
public record Strategy(
        // 1. Entry guard: the fill must still be the setup the candle described.
        @DefaultValue("0.25") double maxEntryDeviationAtr,
        @DefaultValue("90") long maxSignalAgeSec,
        // 2. Breakout confirmation and optional pullback-limit entry.
        @DefaultValue("0.2") double minBreakoutAtr,
        @DefaultValue("MARKET") EntryMode entryMode,
        @DefaultValue("0.5") double limitOffsetAtr,
        @DefaultValue("3") int limitTimeoutCandles,
        // 3. Over-extension.
        @DefaultValue("1.5") double maxEmaDistanceAtr15m,
        @DefaultValue("4") double maxEmaDistanceAtr4h,
        @DefaultValue("90") double htfStochExtreme,
        @DefaultValue("5") double maxRelVol4h,
        // 4. Structural stop.
        @DefaultValue("true") boolean structuralStop,
        @DefaultValue("0.3") double stopBufferAtr,
        @DefaultValue("1.0") double minStopAtr,
        @DefaultValue("3.0") double maxStopAtr,
        @DefaultValue("2.0") double atrStopMultiple,
        // 5. Volume.
        @DefaultValue("1.2") double minRelVol15m,
        @DefaultValue("0.8") double minRelVol1h,
        // 6. RSI: one source for the scored band, the regime gate and the extreme filters.
        @DefaultValue("70") double rsiScoredMax15m,
        @DefaultValue("76") double rsiGateMax15m,
        @DefaultValue("72") double rsiMax1h,
        @DefaultValue("78") double rsiExtreme15m,
        @DefaultValue("85") double rsiMax4h,
        // Trend, momentum, candle, band and volatility limits.
        @DefaultValue("25") double minAdx,
        @DefaultValue("0.05") double minEmaSlopeAtr,
        @DefaultValue("0.65") double impulseBodyRatio,
        @DefaultValue("0.4") double minBollingerWidthPct,
        @DefaultValue("15") double maxBollingerWidthPct,
        @DefaultValue("0.1") double minAtrPct,
        @DefaultValue("5") double maxAtrPct,
        // Target room and cost-adjusted reward.
        @DefaultValue("2") double targetRoomR,
        @DefaultValue("0.25") double targetRoomBufferAtr,
        @DefaultValue("1.5") double minNetRewardRisk,
        // Funding, as a fraction (0.0003 = 0.03%).
        @DefaultValue("0.0003") double maxFundingPaid,
        @DefaultValue("0.001") double maxFundingAbs,
        // 7. Early exit ahead of the stop.
        @DefaultValue("true") boolean slProximityEnabled,
        @DefaultValue("0.05") double slProximityAtr,
        // 8. Portfolio.
        @DefaultValue("true") boolean btcFilterEnabled,
        @DefaultValue("2") int maxSameDirectionPositions,
        @DefaultValue("2") int consecutiveLossCooldownCount,
        @DefaultValue("240") long cooldownMinutes,
        // 9. Weighted quality score (diagnostic only, never gates).
        @DefaultValue("25") double qualityWeightExtension,
        @DefaultValue("20") double qualityWeightBreakout,
        @DefaultValue("20") double qualityWeightHtfVolume,
        @DefaultValue("15") double qualityWeightHtfRoom,
        @DefaultValue("10") double qualityWeightStop,
        @DefaultValue("10") double qualityWeightVolume,
        // 10. Measurement.
        @DefaultValue("true") boolean logRejected) {

    public enum EntryMode { MARKET, LIMIT }

    public Strategy {
        if (entryMode == null) entryMode = EntryMode.MARKET;
        boolean invalid = java.util.stream.DoubleStream.of(maxEntryDeviationAtr, minBreakoutAtr, limitOffsetAtr,
                maxEmaDistanceAtr15m, maxEmaDistanceAtr4h, htfStochExtreme, maxRelVol4h, stopBufferAtr, minStopAtr,
                maxStopAtr, atrStopMultiple, minRelVol15m, minRelVol1h, rsiScoredMax15m, rsiGateMax15m, rsiMax1h,
                rsiExtreme15m, rsiMax4h, minAdx, minEmaSlopeAtr, impulseBodyRatio, minBollingerWidthPct,
                maxBollingerWidthPct, minAtrPct, maxAtrPct, targetRoomR, targetRoomBufferAtr, minNetRewardRisk,
                maxFundingPaid, maxFundingAbs, slProximityAtr, qualityWeightExtension, qualityWeightBreakout,
                qualityWeightHtfVolume, qualityWeightHtfRoom, qualityWeightStop, qualityWeightVolume)
                .anyMatch(v -> !Double.isFinite(v) || v < 0);
        if (invalid || maxSignalAgeSec < 1 || limitTimeoutCandles < 1 || maxSameDirectionPositions < 1
                || consecutiveLossCooldownCount < 1 || cooldownMinutes < 0
                || minStopAtr > maxStopAtr || maxStopAtr <= 0 || atrStopMultiple <= 0
                || htfStochExtreme < 50 || htfStochExtreme > 100
                || Math.min(Math.min(rsiScoredMax15m, rsiGateMax15m), Math.min(rsiMax1h, Math.min(rsiExtreme15m, rsiMax4h))) < 50
                || Math.max(Math.max(rsiScoredMax15m, rsiGateMax15m), Math.max(rsiMax1h, Math.max(rsiExtreme15m, rsiMax4h))) > 100
                || minBollingerWidthPct > maxBollingerWidthPct || minAtrPct > maxAtrPct
                || slProximityAtr >= minStopAtr && slProximityEnabled && minStopAtr > 0)
            throw new IllegalArgumentException("Yanlış bot.strategy parametrləri: dəyərlər sonlu və mənfi olmayan, "
                    + "RSI hədləri 50..100, min-stop-atr <= max-stop-atr, sl-proximity-atr < min-stop-atr olmalıdır");
    }

    /** The defaults exactly as Spring binds them with no properties set: one source, no copies. */
    public static Strategy defaults() { return of(Map.of()); }

    /** Defaults plus overrides in property form, e.g. {@code Map.of("max-stop-atr","2.5")}. For tests and tools. */
    public static Strategy of(Map<String, String> overrides) {
        var source = new java.util.HashMap<String, String>();
        overrides.forEach((k, v) -> source.put("bot.strategy." + k, v));
        return new Binder(new MapConfigurationPropertySource(source)).bindOrCreate("bot.strategy", Strategy.class);
    }

    /** The SHORT mirror of a LONG RSI/stochastic ceiling. */
    public static double mirror(double longLimit) { return 100 - longLimit; }
}
