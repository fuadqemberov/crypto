package az.fuad.futures;

import org.slf4j.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class Scanner {
    private static final Logger log=LoggerFactory.getLogger(Scanner.class);
    private final Settings settings;
    private final BinanceClient client;
    private final Analysis analysis;
    private final PaperBroker broker;
    private final Map<String,Long> analyzed=new HashMap<>();
    public Scanner(Settings settings,BinanceClient client,Analysis analysis,PaperBroker broker) {
        this.settings=settings; this.client=client; this.analysis=analysis; this.broker=broker;
    }
    @Scheduled(initialDelay=3000,fixedDelayString="${bot.scan-delay-ms:60000}")
    public void scan() {
        if(!settings.enabled()) return;
        int checked=0,signals=0,filtered=0,errors=0;
        try {
            var contracts=client.contracts(); var volumes=client.volumes();
            Set<String> allowed=new HashSet<>(Arrays.asList(settings.symbols().toUpperCase(Locale.ROOT).split(",")));
            contracts.sort(Comparator.comparingDouble((Models.Contract c)->volumes.getOrDefault(c.symbol(),0.0)).reversed());
            log.info("SCAN start: {} active USD-M USDT/USDC perpetual contracts",contracts.size());
            for(var contract:contracts) {
                if(Thread.currentThread().isInterrupted()) return;
                String symbol=contract.symbol();
                if((!settings.symbols().isBlank() && !allowed.contains(symbol)) || volumes.getOrDefault(symbol,0.0)<settings.minQuoteVolume()) { filtered++; continue; }
                try {
                    var fast=client.candles(symbol,"15m"); long time=fast.get(fast.size()-1).closeTime();
                    if(analyzed.getOrDefault(symbol,0L)>=time) continue;
                    var hourly=client.candles(symbol,"1h"); var slow=client.candles(symbol,"4h");
                    var signal=analysis.analyze(symbol,fast,hourly,slow); checked++; analyzed.put(symbol,time);
                    if(signal!=null && signal.score()>=settings.threshold()) {
                        signals++;
                        broker.recordSignal(signal);
                        log.info("SIGNAL {} {} score={}/100 (NOT win probability) candle={} reference={} ATR={} SL-distance={}\nChecks: {}\nIndicators: {}",
                                symbol,signal.direction()==1?"LONG":"SHORT",signal.score(),time,signal.reference(),signal.atr(),signal.stopDistance(),signal.reasons(),signal.indicators());
                        if(!broker.open(signal,contract,client.quote(symbol))) log.info("SIGNAL {} paper entry skipped by execution/account risk filters",symbol);
                    }
                } catch(InterruptedException e) { Thread.currentThread().interrupt(); return; }
                catch(Exception e) { errors++; log.warn("SCAN {}: {}",symbol,e.toString()); if(e.getMessage()!=null && (e.getMessage().contains("cooldown") || e.getMessage().contains("HTTP 418") || e.getMessage().contains("HTTP 429"))) break; }
            }
            var a=broker.snapshot();
            log.info("SCAN finished: analyzed={} filtered={} signals={} errors={} cash={} open={} closed={} wins={} realized={} fees={}",checked,filtered,signals,errors,a.cash,a.positions.size(),a.closed,a.wins,a.realized,a.fees);
        } catch(Exception e) { log.error("SCAN failed: {}",e.toString()); }
    }
    @Scheduled(initialDelay=1000,fixedDelayString="${bot.monitor-delay-ms:5000}")
    public void monitor() {
        if(!settings.enabled()) return;
        for(String symbol:broker.symbols()) {
            try { broker.mark(symbol,client.quote(symbol)); }
            catch(Exception e) { log.error("MONITOR {}: {}. Virtual exits delayed until connection recovers.",symbol,e.toString()); }
        }
    }
}
