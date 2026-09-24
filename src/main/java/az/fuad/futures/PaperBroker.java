package az.fuad.futures;

import jakarta.annotation.PreDestroy;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.math.*;
import java.nio.file.Path;
import java.util.*;
import static az.fuad.futures.Models.*;

@Component
public class PaperBroker {
    private static final Logger log=LoggerFactory.getLogger(PaperBroker.class);
    private static final long FAST_CANDLE_MS=900000;
    private final Settings settings;
    private final Strategy cfg;
    private final Journal journal;
    private Account account;
    private boolean failed;
    private final TradeHistory history=new TradeHistory();
    private final Map<String,Quote> marks=new HashMap<>();
    public PaperBroker(Settings settings) throws IOException { this(settings,Strategy.defaults()); }
    @Autowired public PaperBroker(Settings settings,Strategy cfg) throws IOException {
        this.settings=settings; this.cfg=cfg; journal=new Journal(Path.of(settings.dataDir()));
        try { account=journal.load(settings.initialBalance(),history::accept); }
        catch(IOException | RuntimeException e) { journal.close(); throw e; }
        if(account.pending==null) account.pending=new ArrayList<>();
        if(account.sequence==0) commit("INIT","Virtual USD account; score is NOT probability; funding excluded",account);
        log.info("PAPER ACCOUNT restored: cash={} open={} pending={} history={}",account.cash,account.positions.size(),account.pending.size(),Path.of(settings.dataDir()).toAbsolutePath());
    }
    public synchronized Account snapshot() { return journal.copy(account); }
    public record View(Account account, Map<String,Quote> marks, List<ClosedTrade> trades,
                       List<WalletPoint> wallet, List<Activity> activity, long losses, long breakeven,
                       Double profitFactor, boolean halted) {}
    public synchronized View view() {
        return new View(snapshot(),Map.copyOf(marks),history.trades(),history.wallet(),history.activity(),
                history.losses(),history.breakeven(),history.profitFactor(),failed);
    }
    /** Symbols the monitor must quote: open positions and resting limit orders. */
    public synchronized List<String> symbols() {
        var result=new LinkedHashSet<String>();
        account.positions.forEach(p->result.add(p.symbol));
        account.pending.forEach(o->result.add(o.symbol));
        return List.copyOf(result);
    }
    public synchronized void recordSignal(Signal signal) throws IOException {
        commit("SIGNAL",signal.toString(),journal.copy(account));
    }
    /**
     * A candidate that failed. Written so blocked setups can be measured against the ones taken;
     * the journal format is unchanged apart from the optional {@code data} field.
     */
    public synchronized void recordRejected(String symbol,String side,List<String> filters,double reference,long candleTime,String stage) throws IOException {
        if(!cfg.logRejected() || failed) return;
        Map<String,Object> data=new LinkedHashMap<>();
        data.put("symbol",symbol); data.put("side",side); data.put("stage",stage); data.put("filters",List.copyOf(filters));
        data.put("reference",reference); data.put("candleTime",candleTime); data.put("rejectedAt",System.currentTimeMillis());
        commit("REJECTED",symbol+" "+side+" stage="+stage+" reference="+reference+" candle="+candleTime+" filters="+filters,journal.copy(account),data);
    }
    private void commit(String type,String detail,Account next) throws IOException { commit(type,detail,next,null); }
    private void commit(String type,String detail,Account next,Map<String,Object> data) throws IOException {
        if(failed) throw new IOException("Trading halted: previous journal write failed");
        next.sequence=account.sequence+1;
        try { Event event=journal.append(type,detail,next,data); account=next; history.accept(event); }
        catch(IOException e) { failed=true; throw e; }
        log.info("{} | {} | cash={} open={}",type,detail,account.cash,account.positions.size());
    }
    private double equity() {
        double total=account.cash;
        for(var p:account.positions) {
            Quote q=marks.get(p.symbol);
            if(q==null || !q.fresh()) return Double.NaN;
            total+=p.margin+p.direction*(q.mark()-p.entry)*p.quantity;
        }
        return total;
    }
    public synchronized boolean open(Signal signal,Contract contract,Quote quote) throws IOException {
        return tryOpen(signal,contract,quote).opened();
    }
    public synchronized OpenDecision tryOpen(Signal signal,Contract contract,Quote quote) throws IOException {
        long now=System.currentTimeMillis();
        String reason=preconditions(signal,contract,quote,now,null);
        if(reason!=null) return rejected(reason);
        int d=signal.direction();
        if(cfg.entryMode()==Strategy.EntryMode.LIMIT) return placeLimit(signal,contract,quote,now);
        double entry=(d==1?quote.ask():quote.bid())*(1+d*settings.slippageBps()/10000);
        String guard=entryGuard(signal,entry,now,cfg);
        if(guard!=null) return rejected(guard);
        return execute(signal,contract.step(),contract.minQty(),contract.minNotional(),quote,entry,null);
    }
    /**
     * Everything that must hold before an order is placed or a resting limit fills.
     *
     * @param filling the resting order being filled, or null for a fresh signal
     * @return the rejection reason, or null
     */
    private String preconditions(Signal signal,Contract contract,Quote quote,long now,PendingOrder filling) {
        if(failed) return "Jurnala yazı xətası: əməliyyatlar dayandırılıb";
        if(signal==null || contract==null || quote==null) return "Siqnal və ya bazar məlumatı yoxdur";
        if(java.util.stream.DoubleStream.of(signal.score(),signal.reference(),signal.atr(),signal.stopDistance(),
                contract.step(),contract.minQty(),contract.minNotional()).anyMatch(v->!Double.isFinite(v))
                || signal.reference()<=0 || signal.atr()<=0 || signal.stopDistance()<=0
                || contract.step()<=0 || contract.minQty()<0 || contract.minNotional()<0)
            return "Etibarsız rəqəm və ya müqavilə parametri";
        if(signal.score()<Math.max(85,settings.threshold()) || signal.score()>100) return "Siqnal balı minimum "+settings.threshold()+" olmalıdır";
        if(!quote.fresh()) return "Qiymət köhnədir və ya etibarsızdır";
        if(quote.spreadBps()>settings.maxSpreadBps()) return "Spread limitdən yüksəkdir";
        if(filling==null && (now-signal.candleTime()>FAST_CANDLE_MS+30000 || signal.candleTime()>now)) return "Siqnal şamının vaxtı etibarsızdır";
        if(!contract.symbol().equals(signal.symbol())) return "Siqnal və müqavilə uyğun deyil";
        int d=signal.direction(); if(d!=1 && d!=-1) return "LONG və ya SHORT istiqaməti tələb olunur";
        if(!Analysis.fundingAllowed(d,quote.funding(),cfg))
            return "Funding istiqamət üzrə "+Analysis.n(cfg.maxFundingPaid()*100)+"% və ya mütləq "+Analysis.n(cfg.maxFundingAbs()*100)+"% limitini keçir";
        List<PendingOrder> otherPending=account.pending.stream().filter(o->filling==null || !o.id.equals(filling.id)).toList();
        if(account.positions.size()+otherPending.size()>=settings.maxPositions()) return "Açıq mövqe limiti doludur";
        if(account.positions.stream().anyMatch(p->p.symbol.equals(signal.symbol()))
                || otherPending.stream().anyMatch(o->o.symbol.equals(signal.symbol()))) return "Bu simvol üzrə artıq açıq mövqe və ya limit order var";
        if(filling==null && account.lastSignals.getOrDefault(signal.symbol(),0L)>=signal.candleTime()) return "Bu şam üzrə order artıq açılıb";
        long sameSide=account.positions.stream().filter(p->p.direction==d).count()+otherPending.stream().filter(o->o.direction==d).count();
        if(sameSide>=cfg.maxSameDirectionPositions())
            return "Eyni istiqamətdə ("+(d==1?"LONG":"SHORT")+") artıq "+sameSide+" mövqe var; limit "+cfg.maxSameDirectionPositions();
        if(account.cooldownUntil>now)
            return "Ardıcıl "+cfg.consecutiveLossCooldownCount()+" itkidən sonra fasilə: yeni giriş "+java.time.Instant.ofEpochMilli(account.cooldownUntil)+"-dək bağlıdır";
        return null;
    }
    /**
     * The market-entry guard, applied at the real fill price rather than the candle close the
     * signal was scored on. AEROUSDT filled 115 s after the close and below the level it had
     * "broken"; ETCUSDT filled 247 s late, 2.40 ATR from EMA20, after passing a 2 ATR limit that
     * was measured at the reference price.
     *
     * @return the rejection reason, or null when the fill still describes the signal
     */
    public static String entryGuard(Signal s,double entry,long now,Strategy c) {
        long age=(now-s.candleTime())/1000;
        if(age>c.maxSignalAgeSec()) return "Siqnal köhnədir: şam bağlanandan "+age+" san keçib; limit "+c.maxSignalAgeSec()+" san";
        double deviation=Math.abs(entry-s.reference())/s.atr();
        if(deviation>c.maxEntryDeviationAtr())
            return String.format(Locale.ROOT,"İcra qiyməti reference-dən %.2f ATR uzaqdır; limit %s ATR",deviation,Analysis.n(c.maxEntryDeviationAtr()));
        int d=s.direction();
        Double level=s.indicators().get("entry.breakoutLevel");
        if(level!=null && d*(entry-level)<=0)
            return "Breakout səviyyəsi "+Analysis.n(level)+" icra anında geri alınıb: icra qiyməti "+entry;
        Double ema=s.indicators().get("15m.ema20");
        if(ema!=null && Math.abs(entry-ema)/s.atr()>c.maxEmaDistanceAtr15m())
            return String.format(Locale.ROOT,"İcra qiyməti 15m EMA20-dən %.2f ATR uzaqdır; limit %s",Math.abs(entry-ema)/s.atr(),Analysis.n(c.maxEmaDistanceAtr15m()));
        Double ema4=s.indicators().get("4h.ema20"),atr4=s.indicators().get("4h.atr14");
        if(ema4!=null && atr4!=null && atr4>0 && Math.abs(entry-ema4)/atr4>c.maxEmaDistanceAtr4h())
            return String.format(Locale.ROOT,"İcra qiyməti 4h EMA20-dən %.2f ATR uzaqdır; limit %s",Math.abs(entry-ema4)/atr4,Analysis.n(c.maxEmaDistanceAtr4h()));
        return null;
    }
    /** Pullback limit at EMA20 ± offset, cancelled if not filled within N fast candles. */
    private OpenDecision placeLimit(Signal signal,Contract contract,Quote quote,long now) throws IOException {
        Double ema=signal.indicators().get("15m.ema20");
        if(ema==null) return rejected("Limit rejimi üçün 15m EMA20 lazımdır");
        long age=(now-signal.candleTime())/1000;
        if(age>cfg.maxSignalAgeSec()) return rejected("Siqnal köhnədir: şam bağlanandan "+age+" san keçib; limit "+cfg.maxSignalAgeSec()+" san");
        int d=signal.direction();
        double limit=ema+d*cfg.limitOffsetAtr()*signal.atr();
        Account next=journal.copy(account);
        PendingOrder o=new PendingOrder();
        o.id=UUID.randomUUID().toString(); o.symbol=signal.symbol(); o.direction=d; o.limit=limit; o.signal=signal;
        o.step=contract.step(); o.minQty=contract.minQty(); o.minNotional=contract.minNotional();
        o.placedAt=now; o.expiresAt=signal.candleTime()+cfg.limitTimeoutCandles()*FAST_CANDLE_MS;
        next.pending.add(o); next.lastSignals.put(o.symbol,signal.candleTime());
        commit("LIMIT_PLACED",o.symbol+" "+(d==1?"LONG":"SHORT")+" id="+o.id+" limit="+limit+" expiresAt="+o.expiresAt,next,
                Map.of("symbol",o.symbol,"limit",limit,"expiresAt",o.expiresAt));
        marks.put(o.symbol,quote);
        if(fillPending(o.symbol,quote,now)) return new OpenDecision(true,"Limit order dərhal doldu");
        if(account.pending.stream().noneMatch(x->x.id.equals(o.id))) return rejected("Limit order yerləşdirildikdən dərhal sonra ləğv edildi");
        return new OpenDecision(false,"Limit order yerləşdirildi: "+limit,true);
    }
    /** @return true when a resting order for the symbol filled */
    private boolean fillPending(String symbol,Quote quote,long now) throws IOException {
        PendingOrder o=account.pending.stream().filter(x->x.symbol.equals(symbol)).findFirst().orElse(null);
        if(o==null) return false;
        if(now>o.expiresAt) { cancel(o,"LIMIT_CANCELLED","Vaxt bitdi: "+cfg.limitTimeoutCandles()+" şam ərzində dolmadı"); return false; }
        int d=o.direction;
        boolean touched=d==1?quote.ask()<=o.limit:quote.bid()>=o.limit;
        if(!touched) return false;
        double entry=d==1?Math.min(quote.ask(),o.limit):Math.max(quote.bid(),o.limit);
        var contract=new Contract(o.symbol,o.step,o.minQty,o.minNotional);
        String reason=preconditions(o.signal,contract,quote,now,o);
        if(reason!=null) { cancel(o,"LIMIT_CANCELLED",reason); return false; }
        var decision=execute(o.signal,o.step,o.minQty,o.minNotional,quote,entry,o.id);
        if(!decision.opened()) cancel(o,"LIMIT_CANCELLED",decision.reason());
        return decision.opened();
    }
    private void cancel(PendingOrder o,String type,String reason) throws IOException {
        Account next=journal.copy(account);
        next.pending.removeIf(x->x.id.equals(o.id));
        commit(type,o.symbol+" id="+o.id+" "+reason,next,Map.of("symbol",o.symbol,"reason",reason));
    }
    private OpenDecision execute(Signal signal,double step,double minQty,double minNotional,Quote quote,double entry,String pendingId) throws IOException {
        double equity=equity(); if(!Double.isFinite(equity) || equity<=0) return rejected("Equity üçün təzə qiymət və müsbət balans lazımdır");
        int d=signal.direction();
        // A structural stop is a price level from the signal; a legacy ATR stop travels with the fill.
        double stop=cfg.structuralStop()?signal.indicators().getOrDefault("stop.price",signal.reference()-d*signal.stopDistance())
                :entry-d*signal.stopDistance();
        double risk=d*(entry-stop);
        if(!(risk>0)) return rejected("Stop icra qiymətinin yanlış tərəfindədir");
        if(risk/signal.atr()>cfg.maxStopAtr())
            return rejected(String.format(Locale.ROOT,"İcra qiymətindən stopa %.2f ATR; limit %s ATR",risk/signal.atr(),Analysis.n(cfg.maxStopAtr())));
        var levels=new Analysis.Levels(signal.indicators().get("nearestSupport"),signal.indicators().get("nearestResistance"));
        if(!Analysis.hasTargetRoom(d,entry,risk,signal.atr(),levels,cfg))
            return rejected("İcra qiymətindən TP2-yə qədər dəstək/müqavimət boşluğu kifayət deyil");
        double cost=entry*(2*settings.feeRate()+2*settings.slippageBps()/10000+quote.spreadBps()/10000);
        if((cfg.targetRoomR()*risk-cost)/(risk+cost)<cfg.minNetRewardRisk())
            return rejected("Xərclərdən sonra TP2 risk/gəlir nisbəti "+Analysis.n(cfg.minNetRewardRisk())+"-dən aşağıdır");
        double budget=equity*settings.allocation();
        // Risk-first sizing: the stop distance decides the lot, the margin share is only a ceiling.
        // A fixed notional would risk five times more money on a wide-ATR symbol than a tight one.
        double notional=lotNotional(equity,settings.allocation(),settings.leverage(),settings.riskPerTrade(),risk,entry);
        double qty=BigDecimal.valueOf(notional/entry).divide(BigDecimal.valueOf(step),0,RoundingMode.DOWN).multiply(BigDecimal.valueOf(step)).doubleValue();
        if(qty<minQty || qty*entry<minNotional || qty<=0 || risk>=entry*.2) return rejected("Order ölçüsü və ya stop məsafəsi limitə uyğun deyil");
        double fee=qty*entry*settings.feeRate(),margin=qty*entry/settings.leverage();
        if(margin+fee>account.cash || margin>budget+1e-8) return rejected("Sərbəst balans kifayət deyil");
        Account next=journal.copy(account); Position p=new Position();
        p.id=UUID.randomUUID().toString(); p.symbol=signal.symbol(); p.direction=d; p.openedAt=System.currentTimeMillis();
        p.entry=entry; p.quantity=qty; p.initialQuantity=qty; p.margin=margin; p.risk=risk; p.atr=signal.atr();
        p.stop=stop; p.tp1=entry+d*p.risk; p.tp2=entry+d*2*p.risk; p.tp3=entry+d*3*p.risk;
        p.signal=signal; p.realized=-fee;
        next.cash-=margin+fee; next.fees+=fee; next.realized-=fee; next.positions.add(p); next.lastSignals.put(p.symbol,signal.candleTime());
        if(pendingId!=null) next.pending.removeIf(x->x.id.equals(pendingId));
        commit("OPEN",p.symbol+" "+(d==1?"LONG":"SHORT")+" id="+p.id+" score="+signal.score()+"/100 quality="
                +String.format(Locale.ROOT,"%.1f",signal.indicators().getOrDefault("quality.score",Double.NaN))
                +" entry="+entry+" qty="+qty+" margin="+margin+" SL="+p.stop+" TP="+p.tp1+","+p.tp2+","+p.tp3
                +(pendingId!=null?" limitFill=true":"")+" reasons="+signal.reasons(),next);
        marks.put(p.symbol,quote); return new OpenDecision(true,"Virtual order açıldı");
    }
    private OpenDecision rejected(String reason) { return new OpenDecision(false,reason); }
    /** Smaller of the risk-derived lot and the margin-share lot, in quote currency. */
    public static double lotNotional(double equity,double allocation,int leverage,double riskPerTrade,double stopDistance,double entry) {
        double byMargin=equity*allocation*leverage;
        double byRisk=equity*riskPerTrade/stopDistance*entry;
        return Math.min(byMargin,byRisk);
    }
    /**
     * Early-exit buffer ahead of the stop. The old rule closed when the mark came within
     * stopProximity (0.15) × risk of the stop; with risk = 2 ATR that is 0.3 ATR, and filling at the
     * bid minus slippage on a 5 s poll made the realised exit land 0.16–0.27 ATR early, i.e. an
     * effective stop of ~1.75 ATR instead of 2. The buffer is now a small ATR amount, or zero.
     */
    public static double proximityBuffer(Position p,Strategy c) {
        if(!c.slProximityEnabled()) return 0;
        double atr=p.atr>0?p.atr:p.signal!=null && p.signal.atr()>0?p.signal.atr():p.risk/2;
        return c.slProximityAtr()*atr;
    }
    public synchronized void mark(String symbol,Quote quote) throws IOException {
        if(!quote.fresh()) { log.warn("STALE quote {}; position cannot be managed until fresh data arrives",symbol); return; }
        marks.put(symbol,quote);
        long now=System.currentTimeMillis();
        if(fillPending(symbol,quote,now)) return;
        while(true) {
            Position existing=account.positions.stream().filter(p->p.symbol.equals(symbol)).findFirst().orElse(null);
            if(existing==null) return;
            excursion(existing,quote.mark(),quote.mark());
            double exit=(existing.direction==1?quote.bid():quote.ask())*(1-existing.direction*settings.slippageBps()/10000);
            double distance=existing.direction*(quote.mark()-existing.stop);
            if(distance<=0) { close(existing,exit,1,"STOP_LOSS"); return; }
            if(cfg.slProximityEnabled() && distance<=proximityBuffer(existing,cfg)) { close(existing,exit,1,"SL_PROXIMITY"); return; }
            if(expired(existing)) { close(existing,exit,1,"TIME_STOP"); return; }
            double target=existing.stage==0?existing.tp1:existing.stage==1?existing.tp2:existing.tp3;
            if(existing.direction*(exit-target)<0) return;
            double fraction=existing.stage==0?1.0/3:existing.stage==1?.5:1;
            close(existing,exit,fraction,"TP"+(existing.stage+1));
        }
    }
    /** Updates MFE/MAE in R on the live position; persisted with the next journal event. */
    private static void excursion(Position p,double favorablePrice,double adversePrice) {
        if(!(p.risk>0)) return;
        p.mfeR=Math.max(p.mfeR,p.direction*(favorablePrice-p.entry)/p.risk);
        p.maeR=Math.max(p.maeR,-p.direction*(adversePrice-p.entry)/p.risk);
    }
    private boolean expired(Position p) {
        return settings.maxHoldMs()>0 && System.currentTimeMillis()-p.openedAt>settings.maxHoldMs();
    }
    /** Price a stop or target fills at, with the same slippage the live path applies. */
    private double fill(double level,int direction) { return level*(1-direction*settings.slippageBps()/10000); }
    /** One exit a historical candle owes an open position: which level filled, and how much of it. */
    public record GapFill(String reason,double level,double fraction) {}
    /**
     * What a single closed candle does to a position that was open while the bot was down.
     * A candle spanning both sides is scored as the loss: the stop is assumed to come first, never
     * the target, because the true order of touches inside a candle is unknowable.
     *
     * @param proximity the early-exit buffer in price units ({@link #proximityBuffer})
     * @return the exit this candle triggers, or null when the position survives it
     */
    public static GapFill gapFill(Position p,Candle candle,double proximity,long maxHoldMs) {
        if(candle.closeTime()<=p.openedAt) return null;
        int d=p.direction;
        double adverse=d==1?candle.low():candle.high(),favorable=d==1?candle.high():candle.low();
        double trigger=p.stop+d*proximity;
        if(d*(adverse-trigger)<=0) return new GapFill("GAP_STOP_LOSS",trigger,1);
        double target=p.stage==0?p.tp1:p.stage==1?p.tp2:p.tp3;
        if(d*(favorable-target)>=0) return new GapFill("GAP_TP"+(p.stage+1),target,p.stage==0?1.0/3:p.stage==1?.5:1);
        if(maxHoldMs>0 && candle.closeTime()-p.openedAt>maxHoldMs) return new GapFill("GAP_TIME_STOP",candle.close(),1);
        return null;
    }
    /**
     * Replays closed candles over a restored position so exits missed while the process was down are
     * booked at their own level instead of at whatever price is live after the restart.
     *
     * @return the number of exits filled from history
     */
    public synchronized int replay(String symbol,List<Candle> candles) throws IOException {
        int fills=0;
        for(Candle candle:candles) {
            while(true) {
                Position p=account.positions.stream().filter(x->x.symbol.equals(symbol)).findFirst().orElse(null);
                if(p==null) return fills;
                if(candle.closeTime()>p.openedAt)
                    excursion(p,p.direction==1?candle.high():candle.low(),p.direction==1?candle.low():candle.high());
                GapFill gap=gapFill(p,candle,proximityBuffer(p,cfg),settings.maxHoldMs());
                if(gap==null) break;
                close(p,fill(gap.level(),p.direction),gap.fraction(),gap.reason());
                fills++;
                if(gap.fraction()==1) return fills;
            }
        }
        return fills;
    }
    private void close(Position old,double price,double fraction,String reason) throws IOException {
        Account next=journal.copy(account);
        Position p=next.positions.stream().filter(x->x.id.equals(old.id)).findFirst().orElseThrow();
        double qty=p.quantity*fraction,margin=p.margin*fraction,fee=qty*price*settings.feeRate();
        double pnl=p.direction*(price-p.entry)*qty-fee;
        next.cash+=margin+pnl; next.fees+=fee; next.realized+=pnl; p.realized+=pnl;
        p.quantity-=qty; p.margin-=margin;
        long now=System.currentTimeMillis();
        Map<String,Object> data=null;
        String measured="";
        if(fraction==1) {
            next.positions.remove(p); next.closed++; if(p.realized>0) next.wins++;
            // Cooldown after a run of losses: two correlated stops 45 minutes apart were not independent bad luck.
            if(p.realized<0) {
                next.lossStreak++;
                if(next.lossStreak>=cfg.consecutiveLossCooldownCount()) { next.cooldownUntil=now+cfg.cooldownMinutes()*60000; next.lossStreak=0; }
            } else if(p.realized>0) next.lossStreak=0;
            double riskMoney=p.risk*p.initialQuantity;
            double resultR=riskMoney>0?p.realized/riskMoney:0;
            double holdingMinutes=(now-p.openedAt)/60000.0;
            data=new LinkedHashMap<>();
            data.put("symbol",p.symbol); data.put("side",p.direction==1?"LONG":"SHORT"); data.put("reason",reason);
            data.put("entry",p.entry); data.put("exit",price); data.put("stop",p.stop); data.put("netPnl",p.realized);
            data.put("resultR",resultR); data.put("mfeR",p.mfeR); data.put("maeR",p.maeR); data.put("holdingMinutes",holdingMinutes);
            if(p.signal!=null && p.signal.indicators()!=null && p.signal.indicators().get("quality.score")!=null)
                data.put("qualityScore",p.signal.indicators().get("quality.score"));
            if(next.cooldownUntil>now) data.put("cooldownUntil",next.cooldownUntil);
            measured=String.format(Locale.ROOT," resultR=%.2f mfeR=%.2f maeR=%.2f holdingMin=%.1f",resultR,p.mfeR,p.maeR,holdingMinutes);
        }
        else { p.stage++; p.stop=p.stage==1?p.entry:p.entry+p.direction*p.risk; }
        commit(reason,p.symbol+" id="+p.id+" exit="+price+" quantity="+qty+" netPnl="+pnl+" remaining="+p.quantity+" newSL="+p.stop+measured,next,data);
    }
    @PreDestroy public synchronized void shutdown() throws IOException { journal.close(); }
}
