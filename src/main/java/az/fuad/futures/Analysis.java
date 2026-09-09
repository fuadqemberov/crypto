package az.fuad.futures;

import org.springframework.stereotype.Component;
import java.util.*;
import static az.fuad.futures.Models.*;

@Component
public class Analysis {
    public static double[] ema(double[] x,int period) {
        double[] out=new double[x.length]; out[0]=x[0]; double k=2.0/(period+1);
        for(int i=1;i<x.length;i++) out[i]=out[i-1]+k*(x[i]-out[i-1]);
        return out;
    }
    private static double last(double[] a) { return a[a.length-1]; }
    private static double avg(double[] a,int from,int to) { return Arrays.stream(a,from,to).average().orElse(0); }
    public static Map<String,Double> indicators(List<Candle> c) {
        int n=c.size(); if(n<200) throw new IllegalArgumentException("200 candles required");
        double[] close=c.stream().mapToDouble(Candle::close).toArray();
        double[] volume=c.stream().mapToDouble(Candle::volume).toArray();
        double gain=0,loss=0,atr=0,plus=0,minus=0,adx=0,obv=0,obvPast=0;
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
        m.put("atr14",atr); m.put("adx14",adx); m.put("plusDI",atr==0?0:100*plus/atr); m.put("minusDI",atr==0?0:100*minus/atr);
        m.put("macdHistogram",last(macd)-last(ema(macd,9))); m.put("bollingerUpper",mean+2*Math.sqrt(variance)); m.put("bollingerLower",mean-2*Math.sqrt(variance));
        m.put("rollingVwap20",v==0?mean:pv/v); m.put("relativeVolume",avg(volume,n-21,n-1)==0?0:last(volume)/avg(volume,n-21,n-1));
        m.put("obvChange20",obv-obvPast); m.put("resistance20",high); m.put("support20",low);
        m.put("stochasticK",high14==low14?50:100*(last(close)-low14)/(high14-low14));
        return m;
    }
    private int trend(Map<String,Double> m) {
        if(m.get("close")>m.get("ema20") && m.get("ema20")>m.get("ema50") && m.get("ema50")>m.get("ema200")) return 1;
        if(m.get("close")<m.get("ema20") && m.get("ema20")<m.get("ema50") && m.get("ema50")<m.get("ema200")) return -1;
        return 0;
    }
    public Signal analyze(String symbol,List<Candle> fast,List<Candle> hourly,List<Candle> slow) {
        var f=indicators(fast); var h=indicators(hourly); var s=indicators(slow);
        int d=trend(h); if(d==0) d=trend(f); if(d==0) return null;
        List<String> reasons=new ArrayList<>(); double score=0;
        score+=check(trend(f)==d,15,"15m EMA20/50/200 trend",reasons);
        score+=check(trend(h)==d,15,"1h EMA20/50/200 trend",reasons);
        score+=check(trend(s)==d,15,"4h EMA20/50/200 trend",reasons);
        score+=check(f.get("adx14")>=25 && d*(f.get("plusDI")-f.get("minusDI"))>0,10,"ADX>=25 + directional DI",reasons);
        score+=check(d*f.get("macdHistogram")>0 && d*h.get("macdHistogram")>0,10,"15m/1h MACD momentum",reasons);
        double rsi=f.get("rsi14");
        score+=check(d==1?rsi>=50 && rsi<=70:rsi>=30 && rsi<=50,5,"RSI momentum without extreme",reasons);
        score+=check(d*(f.get("close")-f.get("rollingVwap20"))>0,5,"Rolling VWAP alignment",reasons);
        score+=check(f.get("relativeVolume")>=1.2 && d*f.get("obvChange20")>0,10,"Volume expansion + OBV",reasons);
        Candle a=fast.get(fast.size()-1),b=fast.get(fast.size()-2);
        double body=Math.abs(a.close()-a.open()),range=a.high()-a.low();
        boolean engulf=d*(a.close()-a.open())>0 && d*(b.close()-b.open())<0 && Math.max(a.open(),a.close())>=Math.max(b.open(),b.close()) && Math.min(a.open(),a.close())<=Math.min(b.open(),b.close());
        double lower=Math.min(a.open(),a.close())-a.low(),upper=a.high()-Math.max(a.open(),a.close());
        boolean pin=d==1?lower>2*body && upper<body:upper>2*body && lower<body;
        boolean impulse=range>0 && body/range>.65 && d*(a.close()-a.open())>0;
        score+=check(engulf||pin||impulse,10,"Candle: engulfing="+engulf+", pin="+pin+", impulse="+impulse,reasons);
        boolean structure=d==1?a.close()>f.get("resistance20") || (a.low()<=f.get("ema20") && a.close()>f.get("ema20")):
                a.close()<f.get("support20") || (a.high()>=f.get("ema20") && a.close()<f.get("ema20"));
        score+=check(structure,5,"20-bar breakout or EMA20 pullback",reasons);
        Map<String,Double> all=new LinkedHashMap<>();
        f.forEach((k,v)->all.put("15m."+k,v)); h.forEach((k,v)->all.put("1h."+k,v)); s.forEach((k,v)->all.put("4h."+k,v));
        double atr=f.get("atr14"),risk=2*atr;
        // Mandatory quality gates cannot be offset by other indicator points.
        if(trend(h)!=d || trend(s)!=d || atr/a.close()<.001 || atr/a.close()>.05 || risk<=0 || all.values().stream().anyMatch(v->!Double.isFinite(v))) return null;
        return new Signal(symbol,d,score,a.closeTime(),a.close(),atr,risk,all,reasons);
    }
    private double check(boolean pass,int points,String name,List<String> reasons) { reasons.add((pass?"PASS +"+points:"FAIL +0")+" "+name); return pass?points:0; }
}
