package az.fuad.futures;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("bot")
public record Settings(boolean enabled, String baseUrl, String dataDir, double initialBalance,
        double allocation, int leverage, int maxPositions, double threshold, double minQuoteVolume,
        double maxSpreadBps, double feeRate, double slippageBps,
        long requestSpacingMs, String symbols, double riskPerTrade, long maxHoldMs, long staleMarkMs) {
    public Settings {
        if (java.util.stream.DoubleStream.of(initialBalance, allocation, threshold, minQuoteVolume,
                maxSpreadBps, feeRate, slippageBps, riskPerTrade).anyMatch(v -> !Double.isFinite(v))
                || initialBalance <= 0 || allocation <= 0 || allocation > .07 || leverage < 1 || leverage > 3
                || maxPositions < 1 || maxPositions > 10 || threshold < 85 || threshold > 100
                || feeRate < 0 || feeRate > .01 || slippageBps < 0 || slippageBps > 100
                || requestSpacingMs < 20
                || minQuoteVolume < 0 || maxSpreadBps <= 0
                || riskPerTrade <= 0 || riskPerTrade > .01
                || maxHoldMs < 0 || (maxHoldMs > 0 && maxHoldMs < 900000)
                || staleMarkMs < 0 || (staleMarkMs > 0 && staleMarkMs < 30000))
            throw new IllegalArgumentException("Yanlış bot parametrləri: allocation <= 7%, threshold 85..100, "
                    + "leverage 1..3, risk-per-trade 0..1%, max-hold-ms 0 və ya >= 15 dəqiqə olmalıdır");
    }
}
