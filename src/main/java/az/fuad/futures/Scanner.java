package az.fuad.futures;

import jakarta.annotation.PreDestroy;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static az.fuad.futures.Models.*;

/**
 * Two ways in, one decision path ({@link #process}).
 * <ul>
 *   <li><b>Stream</b> (default): Binance pushes each closed 15m candle; the symbol is evaluated from
 *   the in-memory {@link CandleStore} about a second after the close. Open positions are managed
 *   from streamed book/mark updates every {@code bot.monitor-delay-ms}.</li>
 *   <li><b>REST scan</b>: the original sequential poll. Runs only while the stream is off or down.</li>
 * </ul>
 */
@Component
public class Scanner {
    private static final Logger log=LoggerFactory.getLogger(Scanner.class);
    private static final List<String> INTERVALS=List.of("15m","1h","4h");
    private final Settings settings;
    private final Feed feed;
    private final BinanceClient client;
    private final Analysis analysis;
    private final PaperBroker broker;
    private final DashboardState dashboard;
    private final CandleStore store=new CandleStore();
    private final Map<String,Long> analyzed=new ConcurrentHashMap<>();
    private final Map<String,Long> lastMark=new ConcurrentHashMap<>();
    private final Map<String,Long> lastRestQuote=new ConcurrentHashMap<>();
    private final Map<String,Contract> universe=new ConcurrentHashMap<>();
    private final Map<String,double[]> books=new ConcurrentHashMap<>(); // bid, ask, time
    private final Map<String,double[]> markState=new ConcurrentHashMap<>(); // mark, funding, time
    private final Set<String> resyncing=ConcurrentHashMap.newKeySet();
    private final Set<String> quoteSubscriptions=ConcurrentHashMap.newKeySet();
    private final AtomicBoolean recovered=new AtomicBoolean();
    private final AtomicBoolean streamStarting=new AtomicBoolean();
    private volatile MarketStream stream;
    private volatile MarketStream.Connection markConnection,bookConnection;
    private final List<MarketStream.Connection> klineConnections=new CopyOnWriteArrayList<>();
    private final ExecutorService evaluator;
    private final ExecutorService rest=Executors.newSingleThreadExecutor(r->{ Thread t=new Thread(r,"feed-rest"); t.setDaemon(true); return t; });
    private Integer btcBias;
    private long btcValidUntil;

    public Scanner(Settings settings,BinanceClient client,Analysis analysis,PaperBroker broker,DashboardState dashboard) {
        this(settings,Feed.defaults(),client,analysis,broker,dashboard);
    }
    @Autowired
    public Scanner(Settings settings,Feed feed,BinanceClient client,Analysis analysis,PaperBroker broker,DashboardState dashboard) {
        this.settings=settings; this.feed=feed; this.client=client; this.analysis=analysis; this.broker=broker;
        this.dashboard=dashboard;
        var counter=new java.util.concurrent.atomic.AtomicInteger();
        evaluator=Executors.newFixedThreadPool(feed.evaluationThreads(),r->{ Thread t=new Thread(r,"evaluate-"+counter.incrementAndGet()); t.setDaemon(true); return t; });
    }
    /**
     * BTCUSDT 1h regime: +1 when EMA20 > EMA50, -1 when below, null when unavailable (the filter then
     * fails closed). Read from the stream store when current, otherwise fetched at most once per 1h close.
     */
    synchronized Integer btcBias() throws InterruptedException {
        long now=System.currentTimeMillis();
        var stored=store.get("BTCUSDT","1h");
        List<Candle> candles=null;
        if(stored!=null && !stored.isEmpty() && stored.get(stored.size()-1).closeTime()==CandleStore.lastClosedAt(now,"1h")) candles=stored;
        else if(now<btcValidUntil) return btcBias;
        try {
            if(candles==null) candles=client.candles("BTCUSDT","1h");
            double[] close=candles.stream().mapToDouble(Candle::close).toArray();
            double e20=Analysis.ema(close,20)[close.length-1],e50=Analysis.ema(close,50)[close.length-1];
            Integer previous=btcBias;
            btcBias=e20>e50?1:e20<e50?-1:0;
            btcValidUntil=candles.get(candles.size()-1).closeTime()+3600000;
            if(!btcBias.equals(previous)) log.info("BTC FILTER: BTCUSDT 1h EMA20={} EMA50={} bias={}",e20,e50,btcBias);
        } catch(InterruptedException e) { throw e; }
        catch(Exception e) { btcBias=null; btcValidUntil=0; log.warn("BTC FILTER unavailable: {}",e.toString()); }
        return btcBias;
    }
    private static String side(int direction) { return direction==1?"LONG":direction==-1?"SHORT":"NEUTRAL"; }
    /** True once for each (symbol, candle): the stream and the REST fallback can never trade one candle twice. */
    private boolean claim(String symbol,long candleTime) {
        Long[] previous=new Long[1];
        analyzed.compute(symbol,(k,v)->{ previous[0]=v; return v==null || v<candleTime?candleTime:v; });
        return previous[0]==null || previous[0]<candleTime;
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

    // ---------------------------------------------------------------- stream path

    @EventListener(ApplicationReadyEvent.class)
    public void startStream() {
        if(!settings.enabled() || !feed.streamEnabled()) return;
        streamStarting.set(true);
        rest.execute(()->{
            try {
                var symbols=loadUniverse();
                stream=new MarketStream(feed.staleMs(),new StreamHandler());
                // Subscribe before seeding: a close that lands during the REST seed is either in the
                // seed or appended after it, never lost.
                subscribeKlines(symbols);
                markConnection=stream.open("marks",feed.marketStreamUrl(),List.of());
                bookConnection=stream.open("books",feed.publicStreamUrl(),List.of());
                long started=System.currentTimeMillis();
                for(String symbol:symbols) {
                    if(Thread.currentThread().isInterrupted()) return;
                    seed(symbol);
                }
                log.info("STREAM ready: {} symbols seeded in {} s; signals now follow each 15m close",symbols.size(),(System.currentTimeMillis()-started)/1000);
                // Anything whose close passed while it was being seeded.
                for(String symbol:symbols) evaluateLatest(symbol);
            } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
            catch(Exception e) { log.error("STREAM start failed: {}. REST scan stays active.",e.toString()); dashboard.error("Stream başlamadı: "+e.getMessage()); }
            finally { streamStarting.set(false); }
        });
    }
    private List<String> loadUniverse() throws Exception {
        var contracts=client.contracts(); var volumes=client.volumes();
        Set<String> allowed=new HashSet<>(Arrays.asList(settings.symbols().toUpperCase(Locale.ROOT).split(",")));
        contracts.sort(Comparator.comparingDouble((Contract c)->volumes.getOrDefault(c.symbol(),0.0)).reversed());
        List<String> symbols=new ArrayList<>();
        for(var c:contracts) {
            if(!settings.symbols().isBlank() && !allowed.contains(c.symbol())) continue;
            if(volumes.getOrDefault(c.symbol(),0.0)<settings.minQuoteVolume()) continue;
            universe.put(c.symbol(),c); symbols.add(c.symbol());
        }
        return symbols;
    }
    private void subscribeKlines(List<String> symbols) {
        for(int i=0;i<symbols.size();i+=feed.symbolsPerConnection()) {
            var chunk=symbols.subList(i,Math.min(symbols.size(),i+feed.symbolsPerConnection()));
            List<String> streams=new ArrayList<>();
            for(String s:chunk) for(String interval:INTERVALS) streams.add(MarketStream.klineStream(s,interval));
            klineConnections.add(stream.open("klines-"+(i/feed.symbolsPerConnection()+1),feed.marketStreamUrl(),streams));
        }
    }
    private void seed(String symbol) throws InterruptedException {
        for(String interval:INTERVALS) {
            try { store.put(symbol,interval,client.candles(symbol,interval)); }
            catch(InterruptedException e) { throw e; }
            catch(Exception e) { log.warn("SEED {} {}: {}",symbol,interval,e.toString()); }
        }
    }
    /** Re-fetches one series after a gap, then evaluates if the gap swallowed a 15m close. */
    private void resync(String symbol,String interval) {
        if(!resyncing.add(symbol+"|"+interval)) return;
        rest.execute(()->{
            try {
                store.put(symbol,interval,client.candles(symbol,interval));
                log.info("RESYNC {} {} after a stream gap",symbol,interval);
                if(interval.equals("15m")) evaluateLatest(symbol);
            } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
            catch(Exception e) { log.warn("RESYNC {} {}: {}",symbol,interval,e.toString()); }
            finally { resyncing.remove(symbol+"|"+interval); }
        });
    }
    private void evaluateLatest(String symbol) {
        long close=store.lastClose(symbol,"15m");
        if(close>0 && analyzed.getOrDefault(symbol,0L)<close && System.currentTimeMillis()-close<60000)
            evaluator.execute(()->evaluateFromStore(symbol,close));
    }
    private final class StreamHandler implements MarketStream.Handler {
        @Override public void onKline(MarketStream.KlineEvent e) {
            if(!e.closed()) return;
            var result=store.append(e.symbol(),e.interval(),e.candle());
            if(result==CandleStore.Append.GAP) { resync(e.symbol(),e.interval()); return; }
            if(result==CandleStore.Append.APPENDED && e.interval().equals("15m")) {
                long close=e.candle().closeTime();
                evaluator.execute(()->evaluateFromStore(e.symbol(),close));
            }
        }
        @Override public void onBook(MarketStream.BookEvent e) { books.put(e.symbol(),new double[]{e.bid(),e.ask(),e.time()}); }
        @Override public void onMark(MarketStream.MarkEvent e) { markState.put(e.symbol(),new double[]{e.mark(),e.funding(),e.time()}); }
        @Override public void onReconnected(String connection) { log.info("STREAM {} reconnected; gaps are repaired on the next close",connection); }
    }
    /**
     * Evaluates a symbol the moment its 15m candle closes. On a 1h/4h boundary the matching higher
     * timeframe close usually arrives a few milliseconds later, so it is awaited briefly and fetched
     * over REST only if it does not come: an evaluation never mixes a new 15m with a stale 1h.
     */
    void evaluateFromStore(String symbol,long closeTime) {
        try {
            if(analyzed.getOrDefault(symbol,0L)>=closeTime) return;
            Contract contract=universe.get(symbol);
            if(contract==null) return;
            long deadline=System.currentTimeMillis()+feed.htfWaitMs();
            for(String interval:List.of("1h","4h")) {
                long expected=CandleStore.lastClosedAt(closeTime,interval);
                while(store.lastClose(symbol,interval)<expected && System.currentTimeMillis()<deadline) Thread.sleep(20);
                if(store.lastClose(symbol,interval)<expected) store.put(symbol,interval,client.candles(symbol,interval));
            }
            var fast=store.get(symbol,"15m"); var hourly=store.get(symbol,"1h"); var slow=store.get(symbol,"4h");
            if(fast==null || hourly==null || slow==null || fast.size()<250 || hourly.size()<250 || slow.size()<250) return;
            if(fast.get(fast.size()-1).closeTime()!=closeTime) return;
            if(!claim(symbol,closeTime)) return;
            process(contract,fast,hourly,slow,"STREAM");
        } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
        catch(Exception e) { dashboard.error(symbol+": "+e.getMessage()); log.warn("EVALUATE {}: {}",symbol,e.toString()); }
    }
    public boolean streamHealthy() { var s=stream; return s!=null && s.healthy(); }

    // ---------------------------------------------------------------- shared decision path

    /**
     * One symbol, one closed candle: analysis, BTC and funding gates, execution, journal.
     *
     * @return true when an actionable signal was produced
     */
    boolean process(Contract contract,List<Candle> fast,List<Candle> hourly,List<Candle> slow,String source) throws Exception {
        String symbol=contract.symbol();
        long time=fast.get(fast.size()-1).closeTime();
        var report=analysis.evaluate(symbol,fast,hourly,slow);
        double threshold=Math.max(85,settings.threshold());
        boolean candidate=report.direction()!=0 && report.score()>=threshold;
        if(candidate) report=analysis.withBtcFilter(report,analysis.strategy().btcFilterEnabled()?btcBias():null);
        var signal=report.signal();
        String decision=report.qualified()?"LOW_SCORE":"BLOCKED";
        String detail=report.qualified()?"Bal minimum "+settings.threshold()+" həddinə çatmayıb":"Məcburi keyfiyyət filtri keçilməyib: "+Analysis.failedGates(report);
        boolean actionable=false;
        if(signal!=null && signal.score()>=threshold) {
            var entryQuote=client.quote(symbol);
            report=analysis.withFunding(report,entryQuote);
            signal=report.signal();
            if(signal==null) {
                broker.recordRejected(symbol,report.side(),Analysis.failedGates(report),report.reference(),report.candleTime(),"ANALYSIS");
                dashboard.record(report,fast,"BLOCKED","Funding filtri keçilməyib");
                return false;
            }
            actionable=true;
            broker.recordSignal(signal);
            var q=signal.indicators();
            log.info("SIGNAL {} {} via {} score={}/100 (NOT win probability) quality={} [extension={} breakout={} htfVolume={} htfRoom={} stop={} volume={}] candle={} latencyMs={} reference={} ATR={} SL={} SL-distance={} ({} ATR)\nChecks: {}\nIndicators: {}",
                    symbol,side(signal.direction()),source,signal.score(),fmt(q.get("quality.score")),fmt(q.get("quality.extension")),fmt(q.get("quality.breakout")),
                    fmt(q.get("quality.htfVolume")),fmt(q.get("quality.htfRoom")),fmt(q.get("quality.stop")),fmt(q.get("quality.volume")),
                    time,System.currentTimeMillis()-time,signal.reference(),signal.atr(),q.get("stop.price"),signal.stopDistance(),fmt(q.get("stop.distanceAtr")),
                    signal.reasons(),signal.indicators());
            try {
                var result=broker.tryOpen(signal,contract,entryQuote);
                decision=result.opened()?"OPENED":result.pending()?"LIMIT_PLACED":"EXECUTION_REJECTED"; detail=result.reason();
                if(!result.opened() && !result.pending()) {
                    log.info("SIGNAL {} entry skipped: {}",symbol,result.reason());
                    broker.recordRejected(symbol,side(signal.direction()),List.of(result.reason()),signal.reference(),signal.candleTime(),"EXECUTION");
                }
            } catch(Exception e) {
                dashboard.record(report,fast,"EXECUTION_REJECTED","Order açıla bilmədi: "+e.getMessage());
                throw e;
            }
        } else if(candidate && signal==null) {
            // A high-score setup stopped by a mandatory gate: the measurable "what did the filters prevent".
            broker.recordRejected(symbol,report.side(),Analysis.failedGates(report),report.reference(),report.candleTime(),"ANALYSIS");
        }
        dashboard.record(report,fast,decision,detail);
        return actionable;
    }

    // ---------------------------------------------------------------- REST fallback

    @Scheduled(initialDelay=3000,fixedDelayString="${bot.scan-delay-ms:60000}")
    public void scan() {
        if(!settings.enabled()) return;
        if(feed.streamEnabled() && (streamStarting.get() || streamHealthy())) return;
        if(feed.streamEnabled() && stream!=null) log.warn("SCAN: stream is down, falling back to the REST scan");
        dashboard.begin();
        int checked=0,signals=0,filtered=0,errors=0;
        try {
            var contracts=client.contracts(); var volumes=client.volumes();
            Set<String> allowed=new HashSet<>(Arrays.asList(settings.symbols().toUpperCase(Locale.ROOT).split(",")));
            contracts.sort(Comparator.comparingDouble((Contract c)->volumes.getOrDefault(c.symbol(),0.0)).reversed());
            log.info("SCAN start: {} active USD-M USDT/USDC perpetual contracts",contracts.size());
            for(var contract:contracts) {
                if(Thread.currentThread().isInterrupted()) return;
                String symbol=contract.symbol();
                if((!settings.symbols().isBlank() && !allowed.contains(symbol)) || volumes.getOrDefault(symbol,0.0)<settings.minQuoteVolume()) { filtered++; dashboard.filtered(); continue; }
                try {
                    var fast=client.candles(symbol,"15m"); long time=fast.get(fast.size()-1).closeTime();
                    if(analyzed.getOrDefault(symbol,0L)>=time) continue;
                    var hourly=client.candles(symbol,"1h"); var slow=client.candles(symbol,"4h");
                    if(!claim(symbol,time)) continue;
                    checked++;
                    if(process(contract,fast,hourly,slow,"REST")) signals++;
                } catch(InterruptedException e) { Thread.currentThread().interrupt(); return; }
                catch(Exception e) { errors++; dashboard.error(symbol+": "+e.getMessage()); log.warn("SCAN {}: {}",symbol,e.toString()); if(e.getMessage()!=null && (e.getMessage().contains("cooldown") || e.getMessage().contains("HTTP 418") || e.getMessage().contains("HTTP 429"))) break; }
            }
            var a=broker.snapshot();
            log.info("SCAN finished: analyzed={} filtered={} signals={} errors={} cash={} open={} closed={} wins={} realized={} fees={}",checked,filtered,signals,errors,a.cash,a.positions.size(),a.closed,a.wins,a.realized,a.fees);
        } catch(InterruptedException e) { Thread.currentThread().interrupt(); dashboard.error("Skan dayandırıldı"); }
        catch(Exception e) { dashboard.error(e.getMessage()); log.error("SCAN failed: {}",e.toString()); }
        finally { dashboard.finish(); }
    }
    private static String fmt(Double v) { return v==null?"n/a":String.format(Locale.ROOT,"%.2f",v); }

    // ---------------------------------------------------------------- position monitor

    /** Latest streamed quote for a symbol, or null when either half is missing or too old. */
    Quote streamQuote(String symbol,long now) {
        double[] book=books.get(symbol),mark=markState.get(symbol);
        if(book==null || mark==null || now-(long)mark[2]>feed.quoteMaxAgeMs()) return null;
        // The book only updates when it changes, so the mark (every second) carries freshness.
        return new Quote(mark[0],book[0],book[1],(long)mark[2],mark[1]);
    }
    private void syncQuoteSubscriptions(List<String> open) {
        var s=stream; var marks=markConnection; var bookConn=bookConnection;
        if(s==null || marks==null || bookConn==null) return;
        var wanted=new HashSet<>(open);
        var add=wanted.stream().filter(quoteSubscriptions::add).toList();
        var remove=quoteSubscriptions.stream().filter(x->!wanted.contains(x)).toList();
        if(!add.isEmpty()) {
            s.subscribe(marks,add.stream().map(MarketStream::markStream).toList());
            s.subscribe(bookConn,add.stream().map(MarketStream::bookStream).toList());
        }
        if(!remove.isEmpty()) {
            remove.forEach(quoteSubscriptions::remove);
            s.unsubscribe(marks,remove.stream().map(MarketStream::markStream).toList());
            s.unsubscribe(bookConn,remove.stream().map(MarketStream::bookStream).toList());
            remove.forEach(x->{ books.remove(x); markState.remove(x); });
        }
    }
    @Scheduled(initialDelay=1000,fixedDelayString="${bot.monitor-delay-ms:250}")
    public void monitor() {
        if(!settings.enabled() || !recovered.get()) return;
        List<String> open=broker.symbols();
        lastMark.keySet().retainAll(new HashSet<>(open));
        lastRestQuote.keySet().retainAll(new HashSet<>(open));
        syncQuoteSubscriptions(open);
        for(String symbol:open) {
            long now=System.currentTimeMillis();
            lastMark.putIfAbsent(symbol,now);
            try {
                Quote quote=streamQuote(symbol,now);
                if(quote==null && now-lastRestQuote.getOrDefault(symbol,0L)>=feed.restMonitorIntervalMs()) {
                    quote=client.quote(symbol); lastRestQuote.put(symbol,now);
                }
                if(quote!=null) { broker.mark(symbol,quote); if(quote.fresh()) lastMark.put(symbol,now); }
            }
            catch(InterruptedException e) { Thread.currentThread().interrupt(); return; }
            catch(Exception e) { lastRestQuote.put(symbol,now); dashboard.error("Qiymət monitoru "+symbol+": "+e.getMessage()); log.error("MONITOR {}: {}. Virtual exits delayed until connection recovers.",symbol,e.toString()); }
            // Silence here used to look identical to a healthy position: an unmanaged stop is the
            // single most expensive failure this bot has had, so it is surfaced loudly.
            long silence=System.currentTimeMillis()-lastMark.getOrDefault(symbol,System.currentTimeMillis());
            if(settings.staleMarkMs()>0 && silence>settings.staleMarkMs()) {
                String message=symbol+": "+silence/1000+" saniyədir təzə qiymət yoxdur — mövqe İDARƏ OLUNMUR, TP/SL icra edilmir";
                dashboard.error(message); log.error("UNMANAGED {}",message);
            }
        }
    }
    @PreDestroy public void stop() {
        var s=stream; if(s!=null) s.close();
        evaluator.shutdownNow(); rest.shutdownNow();
    }
}
