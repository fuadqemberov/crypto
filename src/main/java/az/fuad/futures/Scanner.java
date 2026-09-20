package az.fuad.futures;

import org.slf4j.*;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class Scanner {
    private static final Logger log=LoggerFactory.getLogger(Scanner.class);
    private final Settings settings;
    private final BinanceClient client;
    private final Analysis analysis;
    private final PaperBroker broker;
    private final DashboardState dashboard;
    private final Map<String,Long> analyzed=new HashMap<>();
    private final Map<String,Long> lastMark=new HashMap<>();
    private final AtomicBoolean recovered=new AtomicBoolean();
    public Scanner(Settings settings,BinanceClient client,Analysis analysis,PaperBroker broker,DashboardState dashboard) {
        this.settings=settings; this.client=client; this.analysis=analysis; this.broker=broker;
        this.dashboard=dashboard;
    }
    /**
     * Books the exits a restored position hit while this process was down, before the live monitor
     * gets a chance to close it at the price that happens to be on the screen after a restart.
     * The monitor stays idle until this has run, so the two can never race over the same position.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverOfflineFills() {
        try {
            if(!settings.enabled()) return;
            var open=broker.snapshot().positions;
            if(open.isEmpty()) { log.info("RECOVERY: no restored position"); return; }
            for(var position:open) {
                long offlineMinutes=(System.currentTimeMillis()-position.openedAt)/60000;
                try {
                    var candles=client.closedCandlesSince(position.symbol,"1m",position.openedAt,12);
                    int fills=broker.replay(position.symbol,candles);
                    log.info("RECOVERY {} open for {} min: {} 1m candles replayed, {} offline fill(s)",
                            position.symbol,offlineMinutes,candles.size(),fills);
                    if(fills>0) dashboard.error(position.symbol+": offline dövrdə "+fills+" çıxış bərpa edildi");
                } catch(InterruptedException e) { Thread.currentThread().interrupt(); return; }
                catch(Exception e) {
                    log.error("RECOVERY {}: {}. Position stays open and is managed live from now on.",position.symbol,e.toString());
                    dashboard.error("Bərpa "+position.symbol+": "+e.getMessage());
                }
            }
        } finally { recovered.set(true); }
    }
    @Scheduled(initialDelay=3000,fixedDelayString="${bot.scan-delay-ms:60000}")
    public void scan() {
        if(!settings.enabled()) return;
        dashboard.begin();
        int checked=0,signals=0,filtered=0,errors=0;
        try {
            var contracts=client.contracts(); var volumes=client.volumes();
            Set<String> allowed=new HashSet<>(Arrays.asList(settings.symbols().toUpperCase(Locale.ROOT).split(",")));
            contracts.sort(Comparator.comparingDouble((Models.Contract c)->volumes.getOrDefault(c.symbol(),0.0)).reversed());
            log.info("SCAN start: {} active USD-M USDT/USDC perpetual contracts",contracts.size());
            for(var contract:contracts) {
                if(Thread.currentThread().isInterrupted()) return;
                String symbol=contract.symbol();
                if((!settings.symbols().isBlank() && !allowed.contains(symbol)) || volumes.getOrDefault(symbol,0.0)<settings.minQuoteVolume()) { filtered++; dashboard.filtered(); continue; }
                try {
                    var fast=client.candles(symbol,"15m"); long time=fast.get(fast.size()-1).closeTime();
                    if(analyzed.getOrDefault(symbol,0L)>=time) continue;
                    var hourly=client.candles(symbol,"1h"); var slow=client.candles(symbol,"4h");
                    var report=analysis.evaluate(symbol,fast,hourly,slow);
                    var signal=report.signal(); checked++; analyzed.put(symbol,time);
                    String decision=report.qualified()?"LOW_SCORE":"BLOCKED";
                    String detail=report.qualified()?"Bal minimum "+settings.threshold()+" həddinə çatmayıb":"Məcburi keyfiyyət filtri keçilməyib";
                    if(signal!=null && signal.score()>=Math.max(85,settings.threshold())) {
                        var entryQuote=client.quote(symbol);
                        report=analysis.withFunding(report,entryQuote);
                        signal=report.signal();
                        if(signal==null) {
                            dashboard.record(report,fast,"BLOCKED","Funding filtri keçilməyib");
                            continue;
                        }
                        signals++;
                        broker.recordSignal(signal);
                        log.info("SIGNAL {} {} score={}/100 (NOT win probability) candle={} reference={} ATR={} SL-distance={}\nChecks: {}\nIndicators: {}",
                                symbol,signal.direction()==1?"LONG":"SHORT",signal.score(),time,signal.reference(),signal.atr(),signal.stopDistance(),signal.reasons(),signal.indicators());
                        try {
                            var result=broker.tryOpen(signal,contract,entryQuote);
                            decision=result.opened()?"OPENED":"EXECUTION_REJECTED"; detail=result.reason();
                            if(!result.opened()) log.info("SIGNAL {} entry skipped: {}",symbol,result.reason());
                        } catch(Exception e) {
                            dashboard.record(report,fast,"EXECUTION_REJECTED","Order açıla bilmədi: "+e.getMessage());
                            throw e;
                        }
                    }
                    dashboard.record(report,fast,decision,detail);
                } catch(InterruptedException e) { Thread.currentThread().interrupt(); return; }
                catch(Exception e) { errors++; dashboard.error(symbol+": "+e.getMessage()); log.warn("SCAN {}: {}",symbol,e.toString()); if(e.getMessage()!=null && (e.getMessage().contains("cooldown") || e.getMessage().contains("HTTP 418") || e.getMessage().contains("HTTP 429"))) break; }
            }
            var a=broker.snapshot();
            log.info("SCAN finished: analyzed={} filtered={} signals={} errors={} cash={} open={} closed={} wins={} realized={} fees={}",checked,filtered,signals,errors,a.cash,a.positions.size(),a.closed,a.wins,a.realized,a.fees);
        } catch(InterruptedException e) { Thread.currentThread().interrupt(); dashboard.error("Skan dayandırıldı"); }
        catch(Exception e) { dashboard.error(e.getMessage()); log.error("SCAN failed: {}",e.toString()); }
        finally { dashboard.finish(); }
    }
    @Scheduled(initialDelay=1000,fixedDelayString="${bot.monitor-delay-ms:5000}")
    public void monitor() {
        if(!settings.enabled() || !recovered.get()) return;
        List<String> open=broker.symbols();
        lastMark.keySet().retainAll(new HashSet<>(open));
        for(String symbol:open) {
            lastMark.putIfAbsent(symbol,System.currentTimeMillis());
            try { broker.mark(symbol,client.quote(symbol)); lastMark.put(symbol,System.currentTimeMillis()); }
            catch(InterruptedException e) { Thread.currentThread().interrupt(); return; }
            catch(Exception e) { dashboard.error("Qiymət monitoru "+symbol+": "+e.getMessage()); log.error("MONITOR {}: {}. Virtual exits delayed until connection recovers.",symbol,e.toString()); }
            // Silence here used to look identical to a healthy position: an unmanaged stop is the
            // single most expensive failure this bot has had, so it is surfaced loudly.
            long silence=System.currentTimeMillis()-lastMark.getOrDefault(symbol,System.currentTimeMillis());
            if(settings.staleMarkMs()>0 && silence>settings.staleMarkMs()) {
                String message=symbol+": "+silence/1000+" saniyədir təzə qiymət yoxdur — mövqe İDARƏ OLUNMUR, TP/SL icra edilmir";
                dashboard.error(message); log.error("UNMANAGED {}",message);
            }
        }
    }
}
