package az.fuad.futures;

import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import static az.fuad.futures.Models.*;

/** Public market data only. No API keys, signed requests or exchange order endpoints. */
@Component
public class BinanceClient {
    private final Settings settings;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private long nextRequest, blockedUntil;
    public BinanceClient(Settings settings) { this.settings=settings; }
    private synchronized void permit() throws InterruptedException {
        long now=System.currentTimeMillis();
        if (now < blockedUntil) throw new IllegalStateException("Binance rate-limit cooldown until " + blockedUntil);
        long delay=nextRequest-now;
        if (delay>0) Thread.sleep(delay);
        nextRequest=System.currentTimeMillis()+settings.requestSpacingMs();
    }
    public JsonNode get(String path) throws Exception {
        permit();
        var request=HttpRequest.newBuilder(URI.create(settings.baseUrl()+path)).timeout(Duration.ofSeconds(15)).GET().build();
        var response=http.send(request,HttpResponse.BodyHandlers.ofString());
        if (response.statusCode()==429 || response.statusCode()==418) {
            long seconds=response.statusCode()==418 ? 3600 : 120;
            try { seconds=Math.max(seconds,Long.parseLong(response.headers().firstValue("Retry-After").orElse("120"))); }
            catch (NumberFormatException ignored) { }
            synchronized(this) { blockedUntil=System.currentTimeMillis()+seconds*1000; }
        }
        if(response.statusCode()!=200) throw new IllegalStateException("Binance HTTP "+response.statusCode()+" "+path);
        var node=json.readTree(response.body());
        if(node.has("code") && node.path("code").asInt()<0) throw new IllegalStateException("Binance: "+node);
        return node;
    }
    public List<Contract> contracts() throws Exception {
        List<Contract> result=new ArrayList<>();
        for(var s:get("/fapi/v1/exchangeInfo").path("symbols")) {
            if(!s.path("status").asText().equals("TRADING") || !s.path("contractType").asText().equals("PERPETUAL")
                    || !Set.of("USDT","USDC").contains(s.path("quoteAsset").asText())) continue;
            double step=0,minQty=0,minNotional=5;
            for(var f:s.path("filters")) {
                if(f.path("filterType").asText().equals("LOT_SIZE")) { step=f.path("stepSize").asDouble(); minQty=f.path("minQty").asDouble(); }
                if(f.path("filterType").asText().equals("MIN_NOTIONAL")) minNotional=f.path("notional").asDouble();
            }
            if(step>0) result.add(new Contract(s.path("symbol").asText(),step,minQty,minNotional));
        }
        return result;
    }
    public Map<String,Double> volumes() throws Exception {
        Map<String,Double> result=new HashMap<>();
        for(var n:get("/fapi/v1/ticker/24hr")) result.put(n.path("symbol").asText(),n.path("quoteVolume").asDouble());
        return result;
    }
    public List<Candle> candles(String symbol,String interval) throws Exception {
        var rows=get("/fapi/v1/klines?symbol="+symbol+"&interval="+interval+"&limit=300");
        long now=System.currentTimeMillis();
        List<Candle> result=new ArrayList<>();
        for(var n:rows) if(n.get(6).asLong()<now-2000) {
            Candle c=new Candle(n.get(0).asLong(),n.get(1).asDouble(),n.get(2).asDouble(),n.get(3).asDouble(),n.get(4).asDouble(),n.get(5).asDouble(),n.get(6).asLong());
            if(c.close()<=0 || c.low()<=0 || c.high()<Math.max(c.open(),c.close()) || c.low()>Math.min(c.open(),c.close()) || c.volume()<0)
                throw new IllegalStateException("Invalid candle "+symbol);
            result.add(c);
        }
        long duration=switch(interval) { case "15m" -> 900000; case "1h" -> 3600000; case "4h" -> 14400000; default -> throw new IllegalArgumentException(interval); };
        if(result.size()<250 || now-result.get(result.size()-1).closeTime()>duration+30000) throw new IllegalStateException("Insufficient/stale candles "+symbol);
        for(int i=1;i<result.size();i++) if(result.get(i).openTime()-result.get(i-1).openTime()!=duration) throw new IllegalStateException("Candle gap "+symbol);
        return result;
    }
    public Quote quote(String symbol) throws Exception {
        var m=get("/fapi/v1/premiumIndex?symbol="+symbol);
        var b=get("/fapi/v1/ticker/bookTicker?symbol="+symbol);
        long time=Math.min(m.path("time").asLong(),b.path("time").asLong());
        return new Quote(m.path("markPrice").asDouble(),b.path("bidPrice").asDouble(),b.path("askPrice").asDouble(),time,m.path("lastFundingRate").asDouble());
    }
}
