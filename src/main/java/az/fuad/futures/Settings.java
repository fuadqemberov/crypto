package az.fuad.futures;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("bot")
public record Settings(boolean enabled, String baseUrl, String dataDir, double initialBalance,
        double allocation, int leverage, int maxPositions, double threshold, double minQuoteVolume,
        double maxSpreadBps, double feeRate, double slippageBps, double stopProximity,
        long requestSpacingMs, String symbols) {
    public Settings {
        if (java.util.stream.DoubleStream.of(initialBalance, allocation, threshold, minQuoteVolume,
                maxSpreadBps, feeRate, slippageBps, stopProximity).anyMatch(v -> !Double.isFinite(v))
                || initialBalance <= 0 || allocation <= 0 || allocation > .07 || leverage < 1 || leverage > 3
                || maxPositions < 1 || maxPositions > 10 || threshold < 85 || threshold > 100
                || feeRate < 0 || feeRate > .01 || slippageBps < 0 || slippageBps > 100
                || stopProximity < 0 || stopProximity >= .5 || requestSpacingMs < 250
                || minQuoteVolume < 0 || maxSpreadBps <= 0)
            throw new IllegalArgumentException("Yanlış bot parametrləri: allocation <= 7%, threshold 85..100, leverage 1..3 olmalıdır");
    }
}
