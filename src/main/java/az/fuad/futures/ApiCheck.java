package az.fuad.futures;

import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/** Explicit connectivity diagnostic: run with --bot.check-api=true --bot.enabled=false. */
@Component
@ConditionalOnProperty(name="bot.check-api",havingValue="true")
public class ApiCheck implements ApplicationRunner {
    private final BinanceClient client;
    private final ConfigurableApplicationContext context;
    public ApiCheck(BinanceClient client,ConfigurableApplicationContext context) { this.client=client; this.context=context; }
    @Override public void run(ApplicationArguments args) throws Exception {
        try {
            var contracts=client.contracts();
            var candles=client.candles("BTCUSDT","15m");
            var quote=client.quote("BTCUSDT");
            if(!quote.fresh()) throw new IllegalStateException("Quote is stale");
            System.out.println("API CHECK OK: contracts="+contracts.size()+" closedCandles="+candles.size()+" BTCUSDT="+quote);
        } finally { context.close(); }
    }
}
