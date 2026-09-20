package az.fuad.futures;

import org.springframework.stereotype.Component;
import java.util.*;
import static az.fuad.futures.Models.*;

@Component
public class Analysis {
    /** 4h exhaustion limits. A LONG uses the value, a SHORT its mirror at 100. */
    public static final double MAX_SLOW_RSI=85;
    public static final double MAX_SLOW_EMA_DISTANCE_ATR=4, MAX_SLOW_RELATIVE_VOLUME=5;
    /**
     * Rejects an entry taken into an exhausted 4h leg. Deliberately RSI only: a stochastic saturates
     * for the whole length of a strong trend, which is the regime this strategy trades, so vetoing
     * on it rejects healthy signals — TRUMPUSDT's 1h stochastic sat at 1.8 inside a clean downtrend
     * whose only problem was that the bot was offline when the stop was hit. 1h RSI is already
     * bounded by the "RSI rejimi" gate; 4h was the timeframe with no mandatory check at all.
     *
     * @param slow the 4h indicator map as {@link #indicators} returns it
     */
    public static boolean exhaustionAllowed(int direction,Map<String,Double> slow) {
        double rsi=slow.get("rsi14");
        if(direction==1) return rsi<=MAX_SLOW_RSI;
        if(direction==-1) return rsi>=100-MAX_SLOW_RSI;
        return false;
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
    public Report evaluate(String symbol,List<Candle> fast,List<Candle> hourly,List<Candle> slow) {
        var f=indicators(fast); var h=indicators(hourly); var s=indicators(slow);
        int d=trend(h); if(d==0) d=trend(f);
        List<Check> checks=new ArrayList<>();
        scored(checks,d!=0 && trend(f)==d,10,"15m trend", "EMA20 / EMA50 / EMA200 uyğunluğu");
        scored(checks,d!=0 && trend(h)==d,10,"1h trend", "Saatlıq trend təsdiqi");
        scored(checks,d!=0 && trend(s)==d,10,"4h trend", "Böyük zaman intervalında trend təsdiqi");
        scored(checks,f.get("adx14")>=25 && d*(f.get("plusDI")-f.get("minusDI"))>0,10,"ADX və DI", "ADX ≥ 25 və istiqamət uyğunluğu");
        scored(checks,d*f.get("macdHistogram")>0 && d*h.get("macdHistogram")>0,10,"MACD", "15m və 1h momentum eyni istiqamətdə");
        double rsi=f.get("rsi14");
        scored(checks,d==1?rsi>=50 && rsi<=70:d==-1 && rsi>=30 && rsi<=50,5,"RSI14", "LONG: 50–70 · SHORT: 30–50");
        scored(checks,d*(f.get("close")-f.get("rollingVwap20"))>0,5,"VWAP", "Qiymət 20 şamlıq VWAP-ın trend tərəfindədir");
        scored(checks,f.get("relativeVolume")>=1.2 && d*f.get("obvChange20")>0,10,"Həcm və OBV", "Əvvəlki 20 şama nisbətən həcm ≥ 1.2x, OBV uyğunluğu");
        Candle a=fast.get(fast.size()-1),b=fast.get(fast.size()-2);
        double body=Math.abs(a.close()-a.open()),range=a.high()-a.low();
        boolean engulf=d*(a.close()-a.open())>0 && d*(b.close()-b.open())<0 && Math.max(a.open(),a.close())>=Math.max(b.open(),b.close()) && Math.min(a.open(),a.close())<=Math.min(b.open(),b.close());
        double lower=Math.min(a.open(),a.close())-a.low(),upper=a.high()-Math.max(a.open(),a.close());
        boolean pin=d==1?lower>2*body && upper<body:upper>2*body && lower<body;
        boolean impulse=range>0 && body/range>.65 && d*(a.close()-a.open())>0;
        scored(checks,d!=0 && (engulf||pin||impulse),10,"Şam təsdiqi", "Engulfing: "+engulf+" · pin bar: "+pin+" · impuls: "+impulse);
        boolean structure=d==1?a.close()>f.get("resistance20") || (a.low()<=f.get("ema20") && a.close()>f.get("ema20")):
                a.close()<f.get("support20") || (a.high()>=f.get("ema20") && a.close()<f.get("ema20"));
        scored(checks,d!=0 && structure,5,"Bazar strukturu", "20 şamlıq səviyyə qırılması və ya EMA20-yə geriçəkilmə");
        scored(checks,d*f.get("ema50SlopeAtr")>.05 && d*h.get("ema50SlopeAtr")>.05,5,"Trendin meyli", "15m və 1h EMA50 meyli > 0.05 ATR / 5 şam");
        scored(checks,f.get("bollingerWidthPct")>=.4 && f.get("bollingerWidthPct")<=15,5,"Bollinger diapazonu", "Zolaq eni 0.4–15%: çox dar və ifrat rejimlərə bal verilmir");
        double hourlyRsi=h.get("rsi14");
        scored(checks,d==1?hourlyRsi>=50 && hourlyRsi<=72:d==-1 && hourlyRsi>=28 && hourlyRsi<=50,5,"1h RSI təsdiqi", "Saatlıq momentum həddən artıq yüklənməyib");
        Map<String,Double> all=new LinkedHashMap<>();
        f.forEach((k,v)->all.put("15m."+k,v)); h.forEach((k,v)->all.put("1h."+k,v)); s.forEach((k,v)->all.put("4h."+k,v));
        double atr=f.get("atr14"),risk=2*atr;
        gate(checks,d!=0 && trend(h)==d && trend(s)==d && trend(f)!=-d,"Zaman intervalları", "1h və 4h eyni istiqamətdə; 15m əks trenddə deyil");
        gate(checks,atr/a.close()>=.001 && atr/a.close()<=.05 && risk>0,"Volatilite limiti", "ATR / qiymət 0.1–5% aralığında olmalıdır");
        gate(checks,d==1?rsi<=78:d==-1 && rsi>=22,"İfrat RSI filtri", "LONG RSI ≤ 78; SHORT RSI ≥ 22");
        gate(checks,f.get("emaDistanceAtr")<=2,"Gec giriş filtri", "Qiymət EMA20-dən maksimum 2 ATR uzaqdadır");
        // Every mandatory filter above reads 15m only, so a parabolic 4h blow-off can pass them all
        // while the higher timeframes are exhausted. These three gates close that hole.
        gate(checks,exhaustionAllowed(d,s),"4h ifrat rejimi",
                String.format(Locale.ROOT,"4h RSI %.1f; LONG ≤ %.0f / SHORT ≥ %.0f",s.get("rsi14"),MAX_SLOW_RSI,100-MAX_SLOW_RSI));
        gate(checks,s.get("emaDistanceAtr")<=MAX_SLOW_EMA_DISTANCE_ATR,"4h gec giriş filtri",
                String.format(Locale.ROOT,"Qiymət 4h EMA20-dən %.2f ATR uzaqdadır; limit %.0f",
                        s.get("emaDistanceAtr"),MAX_SLOW_EMA_DISTANCE_ATR));
        gate(checks,s.get("relativeVolume")<=MAX_SLOW_RELATIVE_VOLUME,"Blow-off həcm filtri",
                String.format(Locale.ROOT,"4h həcm 20 şam ortalamasının %.2f qatıdır; limit %.0f",
                        s.get("relativeVolume"),MAX_SLOW_RELATIVE_VOLUME));
        gate(checks,d!=0 && structure && (engulf||pin||impulse),"Giriş strukturu", "Səviyyə qırılması və ya EMA20 geriçəkilməsi şam təsdiqi ilə birlikdə tələb olunur");
        gate(checks,f.get("relativeVolume")>=1.2 && d*f.get("obvChange20")>0,"Həcm təsdiqi", "Həcm ≥ 1.2x və OBV istiqaməti məcburidir");
        gate(checks,f.get("adx14")>=25 && d*(f.get("plusDI")-f.get("minusDI"))>0
                && d*f.get("macdHistogram")>0 && d*h.get("macdHistogram")>0,
                "Momentum təsdiqi", "ADX/DI və 15m/1h MACD eyni istiqamətdə olmalıdır");
        // The 15m ceiling is deliberately looser than the scored 50–70 band: an impulse entry often
        // prints a 15m RSI in the low seventies, and the exhaustion that actually hurts shows up on
        // 1h and 4h, which the gates below cover.
        gate(checks,d==1?rsi>=50 && rsi<=76 && hourlyRsi>=50 && hourlyRsi<=72
                :d==-1 && rsi>=24 && rsi<=50 && hourlyRsi>=28 && hourlyRsi<=50,
                "RSI rejimi",String.format(Locale.ROOT,"15m RSI %.2f; 1h RSI %.2f. LONG 50–76 / 50–72, SHORT 24–50 / 28–50",rsi,hourlyRsi));
        gate(checks,d*f.get("rsiChange")>=0,"RSI meyli",
                String.format(Locale.ROOT,"Son bağlanmış 15m şamda RSI dəyişməsi %.3f; momentum istiqamətə əks zəifləməməlidir",f.get("rsiChange")));
        gate(checks,d*f.get("macdHistogramChange")>0 && d*h.get("macdHistogramChange")>=0,
                "MACD sürəti",String.format(Locale.ROOT,"15m line %.6f / signal %.6f / histogram dəyişməsi %.6f; 1h dəyişmə %.6f",
                f.get("macdLine"),f.get("macdSignal"),f.get("macdHistogramChange"),h.get("macdHistogramChange")));
        Levels levels=mergeLevels(a.close(),levels(fast,a.close()),levels(hourly,a.close()),levels(slow,a.close()));
        if(levels.support()!=null) all.put("nearestSupport",levels.support());
        if(levels.resistance()!=null) all.put("nearestResistance",levels.resistance());
        gate(checks,hasTargetRoom(d,a.close(),risk,atr,levels),"Dəstək / müqavimət məsafəsi",
                "Təsdiqlənmiş 15m/1h/4h pivotları: dəstək="+levels.support()+"; müqavimət="+levels.resistance()
                +". TP2 üçün 2R + 0.25 ATR boşluq tələb olunur. Səviyyə yoxdursa maneə naməlumdur.");
        gate(checks,all.values().stream().allMatch(Double::isFinite),"Məlumat keyfiyyəti", "Bütün indikatorlar sonlu rəqəm olmalıdır");
        double score=checks.stream().mapToInt(Check::points).sum();
        List<String> reasons=checks.stream().map(c->(c.passed()?"PASS":"FAIL")+" +"+c.points()+" "+c.label()+": "+c.detail()).toList();
        boolean qualified=checks.stream().filter(Check::mandatory).allMatch(Check::passed);
        Signal signal=qualified?new Signal(symbol,d,score,a.closeTime(),a.close(),atr,risk,Map.copyOf(all),reasons):null;
        return new Report(symbol,d,score,a.closeTime(),a.close(),atr,risk,Map.copyOf(all),List.copyOf(checks),signal);
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
    public static boolean hasTargetRoom(int direction,double price,double risk,double atr,Levels levels) {
        double distance=2*risk+.25*atr;
        if(direction==1) return levels.resistance()==null || levels.resistance()-price>=distance;
        if(direction==-1) return levels.support()==null || price-levels.support()>=distance;
        return false;
    }
    public static boolean fundingAllowed(int direction,double rate) {
        return (direction==1 || direction==-1) && Double.isFinite(rate)
                && Math.abs(rate)<=.001 && direction*rate<=.0003;
    }
    public Report withFunding(Report report,Quote quote) {
        var checks=new ArrayList<>(report.checks());
        double rate=quote.funding();
        gate(checks,quote.fresh() && fundingAllowed(report.direction(),rate),"Funding istiqaməti",
                "Rate="+String.format(Locale.ROOT,"%.4f%%",rate*100)
                +" · istiqamət üzrə ödənən rate ≤ 0.03%; mütləq rate ≤ 0.1%. Son açıqlanan rate, gələcək ödəniş zəmanəti deyil");
        var indicators=new LinkedHashMap<>(report.indicators());
        if(Double.isFinite(rate)) indicators.put("fundingRatePct",rate*100);
        var reasons=checks.stream().map(c->(c.passed()?"PASS":"FAIL")+" "+c.label()+": "+c.detail()).toList();
        boolean pass=report.signal()!=null && checks.stream().filter(Check::mandatory).allMatch(Check::passed);
        Signal signal=pass?new Signal(report.symbol(),report.direction(),report.score(),report.candleTime(),
                report.reference(),report.atr(),report.stopDistance(),Map.copyOf(indicators),reasons):null;
        return new Report(report.symbol(),report.direction(),report.score(),report.candleTime(),report.reference(),
                report.atr(),report.stopDistance(),Map.copyOf(indicators),List.copyOf(checks),signal);
    }
    private void scored(List<Check> checks,boolean pass,int points,String label,String detail) {
        checks.add(new Check(label,pass,pass?points:0,points,false,detail));
    }
    private void gate(List<Check> checks,boolean pass,String label,String detail) {
        checks.add(new Check(label,pass,0,0,true,detail));
    }
}
