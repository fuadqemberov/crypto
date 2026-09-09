package az.fuad.futures;

import jakarta.annotation.PreDestroy;
import org.slf4j.*;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.math.*;
import java.nio.file.Path;
import java.util.*;
import static az.fuad.futures.Models.*;

@Component
public class PaperBroker {
    private static final Logger log=LoggerFactory.getLogger(PaperBroker.class);
    private final Settings settings;
    private final Journal journal;
    private Account account;
    private boolean failed;
    private final Map<String,Quote> marks=new HashMap<>();
    public PaperBroker(Settings settings) throws IOException {
        this.settings=settings; journal=new Journal(Path.of(settings.dataDir()));
        try { account=journal.load(settings.initialBalance()); }
        catch(IOException | RuntimeException e) { journal.close(); throw e; }
        if(account.sequence==0) commit("INIT","Virtual USD account; score is NOT probability; funding excluded",account);
        log.info("PAPER ACCOUNT restored: cash={} open={} history={}",account.cash,account.positions.size(),Path.of(settings.dataDir()).toAbsolutePath());
    }
    public synchronized Account snapshot() { return journal.copy(account); }
    public synchronized List<String> symbols() { return account.positions.stream().map(p->p.symbol).toList(); }
    public synchronized void recordSignal(Signal signal) throws IOException {
        commit("SIGNAL",signal.toString(),journal.copy(account));
    }
    private void commit(String type,String detail,Account next) throws IOException {
        if(failed) throw new IOException("Trading halted: previous journal write failed");
        next.sequence=account.sequence+1;
        try { journal.append(type,detail,next); account=next; }
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
        if(failed || !quote.fresh() || quote.spreadBps()>settings.maxSpreadBps() || signal.score()<settings.threshold()
                || System.currentTimeMillis()-signal.candleTime()>930000 || signal.candleTime()>System.currentTimeMillis()
                || !contract.symbol().equals(signal.symbol()) || Math.abs(quote.mark()-signal.reference())>signal.atr()
                || Math.abs(quote.funding())>.001 || account.positions.size()>=settings.maxPositions()
                || account.positions.stream().anyMatch(p->p.symbol.equals(signal.symbol()))
                || account.lastSignals.getOrDefault(signal.symbol(),0L)>=signal.candleTime()) return false;
        double equity=equity(); if(!Double.isFinite(equity) || equity<=0) return false;
        int d=signal.direction(); if(d!=1 && d!=-1) return false;
        double entry=(d==1?quote.ask():quote.bid())*(1+d*settings.slippageBps()/10000);
        double budget=equity*settings.allocation();
        // Entry fees are included inside the 7% cash allocation.
        double notional=Math.min(budget,account.cash)/(1.0/settings.leverage()+settings.feeRate());
        double qty=BigDecimal.valueOf(notional/entry).divide(BigDecimal.valueOf(contract.step()),0,RoundingMode.DOWN).multiply(BigDecimal.valueOf(contract.step())).doubleValue();
        if(qty<contract.minQty() || qty*entry<contract.minNotional() || qty<=0 || signal.stopDistance()<=0 || signal.stopDistance()>=entry*.2) return false;
        double fee=qty*entry*settings.feeRate(),margin=qty*entry/settings.leverage();
        if(margin+fee>account.cash || margin+fee>budget+1e-8) return false;
        Account next=journal.copy(account); Position p=new Position();
        p.id=UUID.randomUUID().toString(); p.symbol=signal.symbol(); p.direction=d; p.openedAt=System.currentTimeMillis();
        p.entry=entry; p.quantity=qty; p.initialQuantity=qty; p.margin=margin; p.risk=signal.stopDistance();
        p.stop=entry-d*p.risk; p.tp1=entry+d*p.risk; p.tp2=entry+d*2*p.risk; p.tp3=entry+d*3*p.risk;
        p.signal=signal; p.realized=-fee;
        next.cash-=margin+fee; next.fees+=fee; next.realized-=fee; next.positions.add(p); next.lastSignals.put(p.symbol,signal.candleTime());
        commit("OPEN",p.symbol+" "+(d==1?"LONG":"SHORT")+" id="+p.id+" score="+signal.score()+"/100 entry="+entry+" qty="+qty+" margin="+margin+" SL="+p.stop+" TP="+p.tp1+","+p.tp2+","+p.tp3+" reasons="+signal.reasons(),next);
        marks.put(p.symbol,quote); return true;
    }
    public synchronized void mark(String symbol,Quote quote) throws IOException {
        if(!quote.fresh()) { log.warn("STALE quote {}; position cannot be managed until fresh data arrives",symbol); return; }
        marks.put(symbol,quote);
        while(true) {
            Position existing=account.positions.stream().filter(p->p.symbol.equals(symbol)).findFirst().orElse(null);
            if(existing==null) return;
            double exit=(existing.direction==1?quote.bid():quote.ask())*(1-existing.direction*settings.slippageBps()/10000);
            double distance=existing.direction*(quote.mark()-existing.stop);
            if(distance<=0) { close(existing,exit,1,"STOP_LOSS"); return; }
            if(distance<=existing.risk*settings.stopProximity()) { close(existing,exit,1,"SL_PROXIMITY"); return; }
            double target=existing.stage==0?existing.tp1:existing.stage==1?existing.tp2:existing.tp3;
            if(existing.direction*(exit-target)<0) return;
            double fraction=existing.stage==0?1.0/3:existing.stage==1?.5:1;
            close(existing,exit,fraction,"TP"+(existing.stage+1));
        }
    }
    private void close(Position old,double price,double fraction,String reason) throws IOException {
        Account next=journal.copy(account);
        Position p=next.positions.stream().filter(x->x.id.equals(old.id)).findFirst().orElseThrow();
        double qty=p.quantity*fraction,margin=p.margin*fraction,fee=qty*price*settings.feeRate();
        double pnl=p.direction*(price-p.entry)*qty-fee;
        next.cash+=margin+pnl; next.fees+=fee; next.realized+=pnl; p.realized+=pnl;
        p.quantity-=qty; p.margin-=margin;
        if(fraction==1) { next.positions.remove(p); next.closed++; if(p.realized>0) next.wins++; }
        else { p.stage++; p.stop=p.stage==1?p.entry:p.entry+p.direction*p.risk; }
        commit(reason,p.symbol+" id="+p.id+" exit="+price+" quantity="+qty+" netPnl="+pnl+" remaining="+p.quantity+" newSL="+p.stop,next);
    }
    @PreDestroy public synchronized void shutdown() throws IOException { journal.close(); }
}
