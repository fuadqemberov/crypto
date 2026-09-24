package az.fuad.futures;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.*;
import static az.fuad.futures.Models.*;

@Component
public class Analysis {
    private static final Strategy DEFAULTS=Strategy.defaults();
    private final Strategy cfg;
    public Analysis() { this(DEFAULTS); }
    @Autowired public Analysis(Strategy cfg) { this.cfg=cfg; }
    public Strategy strategy() { return cfg; }
    /**
     * Rejects an entry taken into an exhausted 4h leg. Deliberately RSI only: a stochastic saturates
     * for the whole length of a strong trend, which is the regime this strategy trades, so vetoing
     * on it rejects healthy signals — TRUMPUSDT's 1h stochastic sat at 1.8 inside a clean downtrend
     * whose only problem was that the bot was offline when the stop was hit. 1h RSI is already
     * bounded by the "RSI rejimi" gate; 4h was the timeframe with no mandatory check at all.
     * (Stochastic does veto in {@link #htfOverextended}, but only together with a close outside the band.)
     *
     * @param slow the 4h indicator map as {@link #indicators} returns it
     */
    public static boolean exhaustionAllowed(int direction,Map<String,Double> slow,double maxRsi) {
        double rsi=slow.get("rsi14");
        if(direction==1) return rsi<=maxRsi;
        if(direction==-1) return rsi>=Strategy.mirror(maxRsi);
        return false;
    }
    public static boolean exhaustionAllowed(int direction,Map<String,Double> slow) {
        return exhaustionAllowed(direction,slow,DEFAULTS.rsiMax4h());
    }
    /**
     * A higher-timeframe leg that is both outside its Bollinger band and stochastically saturated.
     * Either alone is normal in a trend; together they marked the top of AEROUSDT (1h/4h close above
     * the upper band, stoch 96/97) and ETCUSDT (4h close above the upper band, RSI 76).
     */
    public static boolean htfOverextended(int direction,Map<String,Double> m,double stochExtreme) {
        if(direction==1) return m.get("close")>m.get("bollingerUpper") && m.get("stochasticK")>stochExtreme;
        if(direction==-1) return m.get("close")<m.get("bollingerLower") && m.get("stochasticK")<Strategy.mirror(stochExtreme);
        return false;
    }
    /** Distance of the close beyond the broken 20-candle level, in ATR; negative when not broken. */
    public static double breakoutAtr(int direction,double close,double resistance20,double support20,double atr) {
        if(atr<=0 || direction==0) return Double.NaN;
        return direction==1?(close-resistance20)/atr:(support20-close)/atr;
    }
    public static boolean bollingerInRange(Map<String,Double> m,Strategy c) {
        double w=m.get("bollingerWidthPct");
        return w>=c.minBollingerWidthPct() && w<=c.maxBollingerWidthPct();
    }
    /**
     * Stop placed behind structure rather than at a fixed ATR multiple: below the lower of the nearest
     * support and the 15m EMA20 (mirrored for a SHORT), plus a buffer, and never tighter than
     * {@code minStopAtr}. The old entry−2×ATR stop sat above EMA20 on ETCUSDT, so an ordinary
     * pullback to the mean was enough to stop it out.
     *
     * @return the stop price
     */
    public static double stopPrice(int direction,double price,Double support,Double resistance,double ema20,double atr,Strategy c) {
        if(!c.structuralStop()) return price-direction*c.atrStopMultiple()*atr;
        double anchor=direction==1?(support==null?ema20:Math.min(support,ema20)):(resistance==null?ema20:Math.max(resistance,ema20));
        double stop=anchor-direction*c.stopBufferAtr()*atr;
        double floor=price-direction*c.minStopAtr()*atr;
        return direction==1?Math.min(stop,floor):Math.max(stop,floor);
    }
    public static double[] ema(double[] x,int period) {
        if(x.length==0 || period<1) throw new IllegalArgumentException("EMA üçün məlumat və müsbət period lazımdır");
        double[] out=new double[x.length]; out[0]=x[0]; double k=2.0/(period+1);
        for(int i=1;i<x.length;i++) out[i]=out[i-1]+k*(x[i]-out[i-1]);
        return out;
    }
    private static double last(double[] a) { return a[a.length-1]; }
    private static double avg(double[] a,int from,int to) { return Arrays.stream(a,from,to).average().orElse(0); }
    public static Map<String,Double> indicators(List<Candle> c) {
        int n=c.size(); if(n<200) throw new IllegalArgumentException("200 candles required");
        for(Candle a:c) {
            if(java.util.stream.DoubleStream.of(a.open(),a.high(),a.low(),a.close(),a.volume()).anyMatch(v->!Double.isFinite(v))
                    || a.open()<=0 || a.low()<=0 || a.volume()<0 || a.high()<Math.max(a.open(),a.close())
                    || a.low()>Math.min(a.open(),a.close())) throw new IllegalArgumentException("Etibarsız şam məlumatı");
        }
        double[] close=c.stream().mapToDouble(Candle::close).toArray();
        double[] volume=c.stream().mapToDouble(Candle::volume).toArray();
        double gain=0,loss=0,atr=0,plus=0,minus=0,adx=0,obv=0,obvPast=0,previousRsi=50;
        double[] e12=ema(close,12),e26=ema(close,26),macd=new double[n];
        for(int i=0;i<n;i++) macd[i]=e12[i]-e26[i];
        for(int i=1;i<n;i++) {
            Candle a=c.get(i),b=c.get(i-1);
            double change=a.close()-b.close();
            double tr=Math.max(a.high()-a.low(),Math.max(Math.abs(a.high()-b.close()),Math.abs(a.low()-b.close())));
            double up=a.high()-b.high(),down=b.low()-a.low();
            double pd=up>down && up>0 ? up:0, md=down>up && down>0 ? down:0;
            if(i<=14) { gain+=Math.max(change,0)/14; loss+=Math.max(-change,0)/14; atr+=tr/14; plus+=pd/14; minus+=md/14; }
            else { gain=(gain*13+Math.max(change,0))/14; loss=(loss*13+Math.max(-change,0))/14; atr=(atr*13+tr)/14; plus=(plus*13+pd)/14; minus=(minus*13+md)/14; }
            if(i>=14) { double dx=(plus+minus)==0?0:100*Math.abs(plus-minus)/(plus+minus); if(i<=27) adx+=dx/14; else adx=(adx*13+dx)/14; }
            if(i==n-2) previousRsi=loss==0?(gain==0?50:100):100-100/(1+gain/loss);
            obv+=Math.signum(change)*a.volume(); if(i==n-21) obvPast=obv;
        }
        double mean=avg(close,n-20,n),variance=0,pv=0,v=0,high=-Double.MAX_VALUE,low=Double.MAX_VALUE;
        for(int i=n-20;i<n;i++) { Candle a=c.get(i); variance+=Math.pow(a.close()-mean,2)/20; pv+=(a.high()+a.low()+a.close())/3*a.volume(); v+=a.volume(); }
        for(int i=n-21;i<n-1;i++) { high=Math.max(high,c.get(i).high()); low=Math.min(low,c.get(i).low()); }
        double high14=-Double.MAX_VALUE,low14=Double.MAX_VALUE;
        for(int i=n-14;i<n;i++) { high14=Math.max(high14,c.get(i).high()); low14=Math.min(low14,c.get(i).low()); }
        Map<String,Double> m=new LinkedHashMap<>();
        m.put("close",last(close)); m.put("ema20",last(ema(close,20))); m.put("ema50",last(ema(close,50))); m.put("ema200",last(ema(close,200)));
        m.put("rsi14",loss==0 ? (gain==0?50:100) : 100-100/(1+gain/loss));
        m.put("rsiChange",m.get("rsi14")-previousRsi);
        double[] macdSignal=ema(macd,9);
        m.put("macdLine",last(macd)); m.put("macdSignal",last(macdSignal));
        m.put("macdHistogramChange",(last(macd)-last(macdSignal))-(macd[n-2]-macdSignal[n-2]));
        m.put("atr14",atr); m.put("adx14",adx); m.put("plusDI",atr==0?0:100*plus/atr); m.put("minusDI",atr==0?0:100*minus/atr);
        m.put("macdHistogram",last(macd)-last(ema(macd,9))); m.put("bollingerUpper",mean+2*Math.sqrt(variance)); m.put("bollingerLower",mean-2*Math.sqrt(variance));
        m.put("rollingVwap20",v==0?mean:pv/v); m.put("relativeVolume",avg(volume,n-21,n-1)==0?0:last(volume)/avg(volume,n-21,n-1));
        m.put("obvChange20",obv-obvPast); m.put("resistance20",high); m.put("support20",low);
        m.put("stochasticK",high14==low14?50:100*(last(close)-low14)/(high14-low14));
        double[] ema50=ema(close,50);
        m.put("ema50SlopeAtr",atr==0?0:(last(ema50)-ema50[n-6])/atr);
        m.put("bollingerWidthPct",mean==0?0:4*Math.sqrt(variance)/mean*100);
        m.put("emaDistanceAtr",atr==0?0:Math.abs(last(close)-m.get("ema20"))/atr);
        m.put("atrPct",atr/last(close)*100);
        return m;
    }
    private int trend(Map<String,Double> m) {
        if(m.get("close")>m.get("ema20") && m.get("ema20")>m.get("ema50") && m.get("ema50")>m.get("ema200")) return 1;
        if(m.get("close")<m.get("ema20") && m.get("ema20")<m.get("ema50") && m.get("ema50")<m.get("ema200")) return -1;
        return 0;
    }
    public Signal analyze(String symbol,List<Candle> fast,List<Candle> hourly,List<Candle> slow) {
        return evaluate(symbol,fast,hourly,slow).signal();
    }
    /** Numbers in reason texts: plain, no trailing zeros, always the configured value. */
    static String n(double v) { return Double.isFinite(v)?BigDecimal.valueOf(v).stripTrailingZeros().toPlainString():String.valueOf(v); }
    private static String f(String format,Object... args) { return String.format(Locale.ROOT,format,args); }
    public Report evaluate(String symbol,List<Candle> fast,List<Candle> hourly,List<Candle> slow) {
        var f=indicators(fast); var h=indicators(hourly); var s=indicators(slow);
        int d=trend(h); if(d==0) d=trend(f);
        Candle a=fast.get(fast.size()-1),b=fast.get(fast.size()-2);
        double atr=f.get("atr14"),close=a.close(),ema20=f.get("ema20");
        List<Check> checks=new ArrayList<>();
        double rsiLong=cfg.rsiScoredMax15m(),rsiGate=cfg.rsiGateMax15m(),rsi1h=cfg.rsiMax1h();
        scored(checks,d!=0 && trend(f)==d,10,"15m trend", "EMA20 / EMA50 / EMA200 uyğunluğu");
        scored(checks,d!=0 && trend(h)==d,10,"1h trend", "Saatlıq trend təsdiqi");
        scored(checks,d!=0 && trend(s)==d,10,"4h trend", "Böyük zaman intervalında trend təsdiqi");
        scored(checks,f.get("adx14")>=cfg.minAdx() && d*(f.get("plusDI")-f.get("minusDI"))>0,10,"ADX və DI", "ADX ≥ "+n(cfg.minAdx())+" və istiqamət uyğunluğu");
        scored(checks,d*f.get("macdHistogram")>0 && d*h.get("macdHistogram")>0,10,"MACD", "15m və 1h momentum eyni istiqamətdə");
        double rsi=f.get("rsi14");
        scored(checks,d==1?rsi>=50 && rsi<=rsiLong:d==-1 && rsi>=Strategy.mirror(rsiLong) && rsi<=50,5,"RSI14",
                "LONG: 50–"+n(rsiLong)+" · SHORT: "+n(Strategy.mirror(rsiLong))+"–50");
        scored(checks,d*(close-f.get("rollingVwap20"))>0,5,"VWAP", "Qiymət 20 şamlıq VWAP-ın trend tərəfindədir");
        scored(checks,f.get("relativeVolume")>=cfg.minRelVol15m() && d*f.get("obvChange20")>0,10,"Həcm və OBV",
                "Əvvəlki 20 şama nisbətən həcm ≥ "+n(cfg.minRelVol15m())+"x, OBV uyğunluğu");
        double body=Math.abs(a.close()-a.open()),range=a.high()-a.low();
        boolean engulf=d*(a.close()-a.open())>0 && d*(b.close()-b.open())<0 && Math.max(a.open(),a.close())>=Math.max(b.open(),b.close()) && Math.min(a.open(),a.close())<=Math.min(b.open(),b.close());
        double lower=Math.min(a.open(),a.close())-a.low(),upper=a.high()-Math.max(a.open(),a.close());
        boolean pin=d==1?lower>2*body && upper<body:upper>2*body && lower<body;
        boolean impulse=range>0 && body/range>cfg.impulseBodyRatio() && d*(a.close()-a.open())>0;
        scored(checks,d!=0 && (engulf||pin||impulse),10,"Şam təsdiqi", "Engulfing: "+engulf+" · pin bar: "+pin+" · impuls (gövdə/diapazon > "+n(cfg.impulseBodyRatio())+"): "+impulse);
        // Two entry types. A pullback touches EMA20 and closes back on the trend side; a breakout
        // closes beyond the 20-candle level. A breakout by a hair (AEROUSDT: 0.05 ATR) is not one.
        boolean pullback=d==1?a.low()<=ema20 && close>ema20:d==-1 && a.high()>=ema20 && close<ema20;
        double breakout=breakoutAtr(d,close,f.get("resistance20"),f.get("support20"),atr);
        boolean broke=d!=0 && breakout>0, strongBreakout=broke && breakout>=cfg.minBreakoutAtr();
        boolean structure=d!=0 && (pullback || strongBreakout);
        scored(checks,structure,5,"Bazar strukturu", "20 şamlıq səviyyənin ≥ "+n(cfg.minBreakoutAtr())+" ATR qırılması və ya EMA20-yə geriçəkilmə");
        scored(checks,d*f.get("ema50SlopeAtr")>cfg.minEmaSlopeAtr() && d*h.get("ema50SlopeAtr")>cfg.minEmaSlopeAtr(),5,"Trendin meyli",
                "15m və 1h EMA50 meyli > "+n(cfg.minEmaSlopeAtr())+" ATR / 5 şam");
        // The band filter used to read 15m only, so PYTHUSDT's 4h width of 16.82% passed a "0.4–15%" check.
        scored(checks,bollingerInRange(f,cfg) && bollingerInRange(s,cfg),5,"Bollinger diapazonu",
                f("15m eni %.2f%%, 4h eni %.2f%%; hər ikisi %s–%s%% olmalıdır",f.get("bollingerWidthPct"),s.get("bollingerWidthPct"),
                        n(cfg.minBollingerWidthPct()),n(cfg.maxBollingerWidthPct())));
        double hourlyRsi=h.get("rsi14");
        scored(checks,d==1?hourlyRsi>=50 && hourlyRsi<=rsi1h:d==-1 && hourlyRsi>=Strategy.mirror(rsi1h) && hourlyRsi<=50,5,"1h RSI təsdiqi",
                "Saatlıq RSI LONG 50–"+n(rsi1h)+" / SHORT "+n(Strategy.mirror(rsi1h))+"–50");
        Map<String,Double> all=new LinkedHashMap<>();
        f.forEach((k,v)->all.put("15m."+k,v)); h.forEach((k,v)->all.put("1h."+k,v)); s.forEach((k,v)->all.put("4h."+k,v));
        Levels levels=mergeLevels(close,levels(fast,close),levels(hourly,close),levels(slow,close));
        if(levels.support()!=null) all.put("nearestSupport",levels.support());
        if(levels.resistance()!=null) all.put("nearestResistance",levels.resistance());
        double stop=d==0?close-cfg.atrStopMultiple()*atr:stopPrice(d,close,levels.support(),levels.resistance(),ema20,atr,cfg);
        double risk=d==0?cfg.atrStopMultiple()*atr:d*(close-stop);
        double riskAtr=atr>0?risk/atr:Double.NaN;
        all.put("stop.price",stop); all.put("stop.distanceAtr",riskAtr);
        all.put("entry.pullback",pullback?1.0:0.0); all.put("entry.breakoutAtr",broke?breakout:0.0);
        // The broker re-checks that the fill is still beyond this level; a pullback has none.
        if(broke && !pullback) all.put("entry.breakoutLevel",d==1?f.get("resistance20"):f.get("support20"));
        gate(checks,d!=0 && trend(h)==d && trend(s)==d && trend(f)!=-d,"Zaman intervalları", "1h və 4h eyni istiqamətdə; 15m əks trenddə deyil");
        gate(checks,f.get("atrPct")>=cfg.minAtrPct() && f.get("atrPct")<=cfg.maxAtrPct() && risk>0,"Volatilite limiti",
                f("ATR / qiymət %.3f%%; %s–%s%% aralığında olmalıdır",f.get("atrPct"),n(cfg.minAtrPct()),n(cfg.maxAtrPct())));
        gate(checks,d==1?rsi<=cfg.rsiExtreme15m():d==-1 && rsi>=Strategy.mirror(cfg.rsiExtreme15m()),"İfrat RSI filtri",
                f("15m RSI %.2f; LONG ≤ %s, SHORT ≥ %s",rsi,n(cfg.rsiExtreme15m()),n(Strategy.mirror(cfg.rsiExtreme15m()))));
        gate(checks,f.get("emaDistanceAtr")<=cfg.maxEmaDistanceAtr15m(),"Gec giriş filtri",
                f("Siqnal şamında qiymət 15m EMA20-dən %.2f ATR uzaqdadır; limit %s (icra qiyməti ilə yenidən yoxlanılır)",
                        f.get("emaDistanceAtr"),n(cfg.maxEmaDistanceAtr15m())));
        // Every mandatory filter above reads 15m only, so a parabolic 4h blow-off can pass them all
        // while the higher timeframes are exhausted. These gates close that hole.
        gate(checks,exhaustionAllowed(d,s,cfg.rsiMax4h()),"4h ifrat rejimi",
                f("4h RSI %.1f; LONG ≤ %s / SHORT ≥ %s",s.get("rsi14"),n(cfg.rsiMax4h()),n(Strategy.mirror(cfg.rsiMax4h()))));
        gate(checks,s.get("emaDistanceAtr")<=cfg.maxEmaDistanceAtr4h(),"4h gec giriş filtri",
                f("Qiymət 4h EMA20-dən %.2f ATR uzaqdadır; limit %s",s.get("emaDistanceAtr"),n(cfg.maxEmaDistanceAtr4h())));
        gate(checks,s.get("relativeVolume")<=cfg.maxRelVol4h(),"Blow-off həcm filtri",
                f("4h həcm 20 şam ortalamasının %.2f qatıdır; limit %s",s.get("relativeVolume"),n(cfg.maxRelVol4h())));
        boolean hourStretched=htfOverextended(d,h,cfg.htfStochExtreme()),slowStretched=htfOverextended(d,s,cfg.htfStochExtreme());
        gate(checks,d!=0 && !hourStretched && !slowStretched,"HTF uzanma filtri",
                f("1h: close %s zolaqdan kənarda, stoch K %.1f · 4h: close %s zolaqdan kənarda, stoch K %.1f. "
                        +"Zolaqdan kənar close + K > %s (SHORT: < %s) birlikdə rədd edir",
                        outside(d,h)?"":"DEYİL",h.get("stochasticK"),outside(d,s)?"":"DEYİL",s.get("stochasticK"),
                        n(cfg.htfStochExtreme()),n(Strategy.mirror(cfg.htfStochExtreme()))));
        gate(checks,d!=0 && (pullback || broke) && (engulf||pin||impulse),"Giriş strukturu", "Səviyyə qırılması və ya EMA20 geriçəkilməsi şam təsdiqi ilə birlikdə tələb olunur");
        gate(checks,d!=0 && (pullback || !broke || strongBreakout),"Breakout təsdiqi",
                pullback?"EMA20 geriçəkilməsi; breakout tələbi tətbiq olunmur"
                        :f("Close səviyyədən %.2f ATR kənarda; minimum %s ATR",broke?breakout:0.0,n(cfg.minBreakoutAtr())));
        gate(checks,f.get("relativeVolume")>=cfg.minRelVol15m() && d*f.get("obvChange20")>0,"Həcm təsdiqi",
                f("15m həcm %.2fx; ≥ %sx və OBV istiqaməti məcburidir",f.get("relativeVolume"),n(cfg.minRelVol15m())));
        gate(checks,h.get("relativeVolume")>=cfg.minRelVol1h(),"1h həcm təsdiqi",
                f("1h həcm %.2fx; minimum %sx",h.get("relativeVolume"),n(cfg.minRelVol1h())));
        gate(checks,f.get("adx14")>=cfg.minAdx() && d*(f.get("plusDI")-f.get("minusDI"))>0
                && d*f.get("macdHistogram")>0 && d*h.get("macdHistogram")>0,
                "Momentum təsdiqi", "ADX ≥ "+n(cfg.minAdx())+", DI və 15m/1h MACD eyni istiqamətdə olmalıdır");
        // The 15m ceiling is deliberately looser than the scored band: an impulse entry often prints a
        // 15m RSI above it, and the exhaustion that actually hurts shows up on 1h and 4h.
        gate(checks,d==1?rsi>=50 && rsi<=rsiGate && hourlyRsi>=50 && hourlyRsi<=rsi1h
                :d==-1 && rsi>=Strategy.mirror(rsiGate) && rsi<=50 && hourlyRsi>=Strategy.mirror(rsi1h) && hourlyRsi<=50,
                "RSI rejimi",f("15m RSI %.2f; 1h RSI %.2f. LONG 50–%s / 50–%s, SHORT %s–50 / %s–50",rsi,hourlyRsi,
                        n(rsiGate),n(rsi1h),n(Strategy.mirror(rsiGate)),n(Strategy.mirror(rsi1h))));
        gate(checks,d*f.get("rsiChange")>=0,"RSI meyli",
                f("Son bağlanmış 15m şamda RSI dəyişməsi %.3f; momentum istiqamətə əks zəifləməməlidir",f.get("rsiChange")));
        gate(checks,d*f.get("macdHistogramChange")>0 && d*h.get("macdHistogramChange")>=0,
                "MACD sürəti",f("15m line %.6f / signal %.6f / histogram dəyişməsi %.6f; 1h dəyişmə %.6f",
                f.get("macdLine"),f.get("macdSignal"),f.get("macdHistogramChange"),h.get("macdHistogramChange")));
        gate(checks,d!=0 && risk>0 && riskAtr<=cfg.maxStopAtr(),"Stop məsafəsi",
                cfg.structuralStop()
                        ?f("Struktural stop %s: min(dəstək, EMA20) − %s ATR (minimum %s ATR) = %.2f ATR; limit %s ATR",
                                n(stop),n(cfg.stopBufferAtr()),n(cfg.minStopAtr()),riskAtr,n(cfg.maxStopAtr()))
                        :f("Stop %s = qiymət − %s ATR; limit %s ATR",n(stop),n(cfg.atrStopMultiple()),n(cfg.maxStopAtr())));
        gate(checks,hasTargetRoom(d,close,risk,atr,levels,cfg),"Dəstək / müqavimət məsafəsi",
                "Təsdiqlənmiş 15m/1h/4h pivotları: dəstək="+levels.support()+"; müqavimət="+levels.resistance()
                +". TP2 üçün "+n(cfg.targetRoomR())+"R + "+n(cfg.targetRoomBufferAtr())+" ATR boşluq tələb olunur. Səviyyə yoxdursa maneə naməlumdur.");
        gate(checks,all.values().stream().allMatch(Double::isFinite),"Məlumat keyfiyyəti", "Bütün indikatorlar sonlu rəqəm olmalıdır");
        all.putAll(quality(cfg,d,f,h,s,pullback,broke?breakout:0,riskAtr));
        double score=checks.stream().mapToInt(Check::points).sum();
        boolean qualified=checks.stream().filter(Check::mandatory).allMatch(Check::passed);
        Signal signal=qualified?new Signal(symbol,d,score,a.closeTime(),close,atr,risk,Map.copyOf(all),reasons(checks)):null;
        return new Report(symbol,d,score,a.closeTime(),close,atr,risk,Map.copyOf(all),List.copyOf(checks),signal);
    }
    private static boolean outside(int d,Map<String,Double> m) {
        return d==1?m.get("close")>m.get("bollingerUpper"):d==-1 && m.get("close")<m.get("bollingerLower");
    }
    private static double clip(double v) { return Double.isFinite(v)?Math.max(0,Math.min(1,v)):0; }
    /**
     * Weighted 0–100 quality score. Unlike the 100-point check score, which every qualifying signal
     * maxes out, this one grades how good a qualifying setup is. Diagnostic only: it is logged and
     * journalled with the signal but never opens or blocks a trade.
     */
    public static Map<String,Double> quality(Strategy c,int d,Map<String,Double> f,Map<String,Double> h,Map<String,Double> s,
                                             boolean pullback,double breakoutAtr,double riskAtr) {
        Map<String,Double> q=new LinkedHashMap<>();
        if(d==0) { q.put("quality.score",0.0); return q; }
        double extension=clip(1-f.get("emaDistanceAtr")/c.maxEmaDistanceAtr15m());
        double breakout=pullback?1:c.minBreakoutAtr()>0?clip(breakoutAtr/(2*c.minBreakoutAtr())):breakoutAtr>0?1:0;
        double full1h=Math.max(2*c.minRelVol1h(),1e-9);
        double htfVolume=(clip(h.get("relativeVolume")/full1h)+clip(s.get("relativeVolume")/full1h))/2;
        double rsi4h=s.get("rsi14"),span=c.rsiMax4h()-50;
        double htfRoom=span<=0?0:clip(d==1?(c.rsiMax4h()-rsi4h)/span:(rsi4h-Strategy.mirror(c.rsiMax4h()))/span);
        double stopSpan=c.maxStopAtr()-c.minStopAtr();
        double stop=stopSpan<=0?1:clip(1-(riskAtr-c.minStopAtr())/stopSpan);
        double volume=clip(f.get("relativeVolume")/Math.max(2*c.minRelVol15m(),1e-9));
        double[] w={c.qualityWeightExtension(),c.qualityWeightBreakout(),c.qualityWeightHtfVolume(),c.qualityWeightHtfRoom(),c.qualityWeightStop(),c.qualityWeightVolume()};
        double[] x={extension,breakout,htfVolume,htfRoom,stop,volume};
        double total=0,weights=0;
        for(int i=0;i<w.length;i++) { total+=w[i]*x[i]; weights+=w[i]; }
        q.put("quality.extension",extension); q.put("quality.breakout",breakout); q.put("quality.htfVolume",htfVolume);
        q.put("quality.htfRoom",htfRoom); q.put("quality.stop",stop); q.put("quality.volume",volume);
        q.put("quality.score",weights>0?100*total/weights:0);
        return q;
    }
    public record Levels(Double support,Double resistance) {}
    public static Levels levels(List<Candle> candles,double price) {
        Double support=null,resistance=null;
        // A pivot needs two closed candles on each side; never use future candles.
        for(int i=Math.max(2,candles.size()-120);i<candles.size()-2;i++) {
            Candle pivot=candles.get(i);
            boolean high=true,low=true;
            for(int j=i-2;j<=i+2;j++) {
                if(j==i) continue;
                high &= pivot.high()>candles.get(j).high();
                low &= pivot.low()<candles.get(j).low();
            }
            if(high && pivot.high()>price && (resistance==null || pivot.high()<resistance)) resistance=pivot.high();
            if(low && pivot.low()<price && (support==null || pivot.low()>support)) support=pivot.low();
        }
        return new Levels(support,resistance);
    }
    private static Levels mergeLevels(double price,Levels... levels) {
        Double support=null,resistance=null;
        for(Levels level:levels) {
            if(level.support()!=null && level.support()<price && (support==null || level.support()>support)) support=level.support();
            if(level.resistance()!=null && level.resistance()>price && (resistance==null || level.resistance()<resistance)) resistance=level.resistance();
        }
        return new Levels(support,resistance);
    }
    public static boolean hasTargetRoom(int direction,double price,double risk,double atr,Levels levels,Strategy c) {
        double distance=c.targetRoomR()*risk+c.targetRoomBufferAtr()*atr;
        if(direction==1) return levels.resistance()==null || levels.resistance()-price>=distance;
        if(direction==-1) return levels.support()==null || price-levels.support()>=distance;
        return false;
    }
    public static boolean hasTargetRoom(int direction,double price,double risk,double atr,Levels levels) {
        return hasTargetRoom(direction,price,risk,atr,levels,DEFAULTS);
    }
    public static boolean fundingAllowed(int direction,double rate,Strategy c) {
        return (direction==1 || direction==-1) && Double.isFinite(rate)
                && Math.abs(rate)<=c.maxFundingAbs() && direction*rate<=c.maxFundingPaid();
    }
    public static boolean fundingAllowed(int direction,double rate) { return fundingAllowed(direction,rate,DEFAULTS); }
    public Report withFunding(Report report,Quote quote) {
        double rate=quote.funding();
        Map<String,Double> extra=Double.isFinite(rate)?Map.of("fundingRatePct",rate*100):Map.of();
        return withGate(report,quote.fresh() && fundingAllowed(report.direction(),rate,cfg),"Funding istiqaməti",
                f("Rate=%.4f%% · istiqamət üzrə ödənən rate ≤ %s%%; mütləq rate ≤ %s%%. Son açıqlanan rate, gələcək ödəniş zəmanəti deyil",
                        rate*100,n(cfg.maxFundingPaid()*100),n(cfg.maxFundingAbs()*100)),extra);
    }
    /**
     * BTC regime filter for altcoins: a LONG needs BTCUSDT 1h EMA20 above EMA50, a SHORT below.
     * ETCUSDT and PYTHUSDT opened 27 minutes apart and stopped 45 minutes apart on one market-wide
     * move; nothing looked at the market. Unknown BTC state fails closed.
     *
     * @param btcBias +1, -1, 0 (flat) or null (data unavailable)
     */
    public Report withBtcFilter(Report report,Integer btcBias) {
        if(!cfg.btcFilterEnabled() || report.symbol().startsWith("BTC")) return report;
        boolean pass=btcBias!=null && report.direction()!=0 && btcBias==report.direction();
        String state=btcBias==null?"məlumat yoxdur":btcBias==1?"EMA20 > EMA50":btcBias==-1?"EMA20 < EMA50":"EMA20 = EMA50";
        return withGate(report,pass,"BTC filtri","BTCUSDT 1h "+state+"; altcoin "+report.side()+" yalnız BTC eyni istiqamətdə olanda açılır",Map.of());
    }
    /** Adds one mandatory check to a finished report; the signal survives only if every gate still passes. */
    public Report withGate(Report report,boolean pass,String label,String detail,Map<String,Double> extra) {
        var checks=new ArrayList<>(report.checks());
        gate(checks,pass,label,detail);
        var indicators=new LinkedHashMap<>(report.indicators());
        indicators.putAll(extra);
        boolean ok=report.signal()!=null && checks.stream().filter(Check::mandatory).allMatch(Check::passed);
        Signal signal=ok?new Signal(report.symbol(),report.direction(),report.score(),report.candleTime(),
                report.reference(),report.atr(),report.stopDistance(),Map.copyOf(indicators),reasons(checks)):null;
        return new Report(report.symbol(),report.direction(),report.score(),report.candleTime(),report.reference(),
                report.atr(),report.stopDistance(),Map.copyOf(indicators),List.copyOf(checks),signal);
    }
    /** Labels of the mandatory gates that failed, for REJECTED journal events. */
    public static List<String> failedGates(Report report) {
        return report.checks().stream().filter(c->c.mandatory() && !c.passed()).map(Check::label).toList();
    }
    private static List<String> reasons(List<Check> checks) {
        return checks.stream().map(c->(c.passed()?"PASS":"FAIL")+(c.mandatory()?" [məcburi] ":" +"+c.points()+" ")+c.label()+": "+c.detail()).toList();
    }
    private void scored(List<Check> checks,boolean pass,int points,String label,String detail) {
        checks.add(new Check(label,pass,pass?points:0,points,false,detail));
    }
    private void gate(List<Check> checks,boolean pass,String label,String detail) {
        checks.add(new Check(label,pass,0,0,true,detail));
    }
}
