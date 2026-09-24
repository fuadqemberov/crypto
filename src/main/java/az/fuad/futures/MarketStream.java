package az.fuad.futures;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static az.fuad.futures.Models.*;

/**
 * Binance USD-M public market streams over WebSocket: closed klines for the scan universe and
 * book/mark updates for open positions. Public data only — no keys, no user-data stream.
 * Every connection reconnects on its own with backoff and re-subscribes; a connection that goes
 * silent is treated as dead. Candles missed while disconnected are repaired by the caller, which
 * sees a {@link CandleStore.Append#GAP} on the next close.
 */
public final class MarketStream implements AutoCloseable {
    private static final Logger log=LoggerFactory.getLogger(MarketStream.class);
    /** Binance accepts at most 10 incoming messages per second per connection. */
    private static final int PARAMS_PER_MESSAGE=100;
    private static final long MESSAGE_SPACING_MS=150;
    public record KlineEvent(String symbol,String interval,Candle candle,boolean closed) {}
    public record BookEvent(String symbol,double bid,double ask,long time) {}
    public record MarkEvent(String symbol,double mark,double funding,long time) {}
    public interface Handler {
        void onKline(KlineEvent event);
        default void onBook(BookEvent event) {}
        default void onMark(MarkEvent event) {}
        default void onReconnected(String connection) {}
    }
    private final ObjectMapper json=new ObjectMapper();
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final long staleMs;
    private final Handler handler;
    private final List<Connection> connections=new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService scheduler=Executors.newSingleThreadScheduledExecutor(r->daemon(r,"stream-watchdog"));
    private final ExecutorService sender=Executors.newSingleThreadExecutor(r->daemon(r,"stream-sender"));
    private final AtomicInteger ids=new AtomicInteger();
    private volatile boolean closed;

    public MarketStream(long staleMs,Handler handler) {
        this.staleMs=staleMs; this.handler=handler;
        scheduler.scheduleWithFixedDelay(this::watchdog,5,5,TimeUnit.SECONDS);
    }
    private static Thread daemon(Runnable r,String name) { Thread t=new Thread(r,name); t.setDaemon(true); return t; }

    public final class Connection {
        final String name,url;
        final Set<String> streams=ConcurrentHashMap.newKeySet();
        volatile WebSocket socket;
        volatile long lastMessage=System.currentTimeMillis();
        volatile long connectedAt;
        volatile boolean connecting;
        int failures;
        final StringBuilder buffer=new StringBuilder();
        Connection(String name,String url) { this.name=name; this.url=url; }
        public int size() { return streams.size(); }
        boolean open() { var s=socket; return s!=null && !s.isInputClosed() && !s.isOutputClosed(); }
    }
    /** Opens a connection subscribed to {@code streams} (lower-case Binance stream names). */
    public Connection open(String name,String url,Collection<String> streams) {
        var c=new Connection(name,url);
        c.streams.addAll(streams);
        connections.add(c);
        connect(c);
        return c;
    }
    public void subscribe(Connection c,Collection<String> streams) {
        var added=streams.stream().filter(c.streams::add).toList();
        if(!added.isEmpty() && c.open()) send(c,"SUBSCRIBE",added);
        else if(!added.isEmpty() && !c.connecting && c.socket==null) connect(c);
    }
    public void unsubscribe(Connection c,Collection<String> streams) {
        var removed=streams.stream().filter(c.streams::remove).toList();
        if(!removed.isEmpty() && c.open()) send(c,"UNSUBSCRIBE",removed);
    }
    /** Every connection that has streams is open and has spoken recently. */
    public boolean healthy() {
        long now=System.currentTimeMillis();
        return !connections.isEmpty() && connections.stream().filter(c->!c.streams.isEmpty())
                .allMatch(c->c.open() && now-c.lastMessage<staleMs);
    }
    private void connect(Connection c) {
        if(closed || c.connecting) return;
        c.connecting=true;
        http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10))
                .buildAsync(URI.create(c.url),new Listener(c))
                .whenComplete((socket,error)->{
                    c.connecting=false;
                    if(error!=null) { log.warn("STREAM {} connect failed: {}",c.name,error.toString()); retry(c); return; }
                    c.socket=socket; c.lastMessage=System.currentTimeMillis(); c.connectedAt=c.lastMessage;
                    boolean reconnect=c.failures>0;
                    c.failures=0;
                    log.info("STREAM {} connected, subscribing {} streams",c.name,c.streams.size());
                    if(!c.streams.isEmpty()) send(c,"SUBSCRIBE",List.copyOf(c.streams));
                    if(reconnect) handler.onReconnected(c.name);
                });
    }
    private void retry(Connection c) {
        if(closed) return;
        c.failures++;
        long delay=Math.min(30000,1000L<<Math.min(5,c.failures-1));
        scheduler.schedule(()->connect(c),delay,TimeUnit.MILLISECONDS);
    }
    private void send(Connection c,String method,List<String> streams) {
        sender.execute(()->{
            for(int i=0;i<streams.size();i+=PARAMS_PER_MESSAGE) {
                var socket=c.socket;
                if(socket==null) return;
                var chunk=streams.subList(i,Math.min(streams.size(),i+PARAMS_PER_MESSAGE));
                try {
                    String message=json.writeValueAsString(Map.of("method",method,"params",chunk,"id",ids.incrementAndGet()));
                    socket.sendText(message,true).get(10,TimeUnit.SECONDS);
                    Thread.sleep(MESSAGE_SPACING_MS);
                } catch(InterruptedException e) { Thread.currentThread().interrupt(); return; }
                catch(Exception e) { log.warn("STREAM {} {} failed: {}",c.name,method,e.toString()); socket.abort(); return; }
            }
        });
    }
    private void watchdog() {
        long now=System.currentTimeMillis();
        for(var c:connections) {
            if(c.streams.isEmpty() || c.connecting) continue;
            if(c.socket==null) { connect(c); continue; }
            if(!c.open() || now-c.lastMessage>staleMs) {
                log.warn("STREAM {} silent for {} ms; reconnecting",c.name,now-c.lastMessage);
                drop(c);
            }
        }
    }
    private void drop(Connection c) {
        var s=c.socket; c.socket=null;
        if(s!=null) s.abort();
        c.failures=Math.max(1,c.failures);
        retry(c);
    }
    private final class Listener implements WebSocket.Listener {
        private final Connection c;
        Listener(Connection c) { this.c=c; }
        @Override public CompletionStage<?> onText(WebSocket socket,CharSequence data,boolean last) {
            c.lastMessage=System.currentTimeMillis();
            c.buffer.append(data);
            if(last) {
                String text=c.buffer.toString(); c.buffer.setLength(0);
                try { dispatch(parse(json,text)); }
                catch(Exception e) { log.warn("STREAM {} bad message: {}",c.name,e.toString()); }
            }
            socket.request(1);
            return null;
        }
        @Override public CompletionStage<?> onPing(WebSocket socket,java.nio.ByteBuffer message) {
            c.lastMessage=System.currentTimeMillis();
            socket.request(1);
            return null;
        }
        @Override public CompletionStage<?> onClose(WebSocket socket,int status,String reason) {
            log.warn("STREAM {} closed by server: {} {}",c.name,status,reason);
            if(c.socket==socket) { c.socket=null; c.failures=Math.max(1,c.failures); retry(c); }
            return null;
        }
        @Override public void onError(WebSocket socket,Throwable error) {
            log.warn("STREAM {} error: {}",c.name,error.toString());
            if(c.socket==socket || c.socket==null) { c.socket=null; c.failures=Math.max(1,c.failures); retry(c); }
        }
    }
    private void dispatch(Object event) {
        if(event instanceof KlineEvent k) handler.onKline(k);
        else if(event instanceof BookEvent b) handler.onBook(b);
        else if(event instanceof MarkEvent m) handler.onMark(m);
    }
    /**
     * One raw message to an event, or null for subscription acknowledgements and anything unknown.
     * Accepts both the combined-stream envelope ({"stream":…,"data":…}) and a bare payload.
     */
    public static Object parse(ObjectMapper json,String text) throws Exception {
        JsonNode node=json.readTree(text);
        JsonNode data=node.has("data")?node.get("data"):node;
        switch(data.path("e").asText()) {
            case "kline" -> {
                JsonNode k=data.get("k");
                var candle=new Candle(k.get("t").asLong(),k.get("o").asDouble(),k.get("h").asDouble(),k.get("l").asDouble(),
                        k.get("c").asDouble(),k.get("v").asDouble(),k.get("T").asLong());
                return new KlineEvent(data.get("s").asText(),k.get("i").asText(),candle,k.get("x").asBoolean());
            }
            case "bookTicker" -> {
                long time=Math.max(data.path("T").asLong(),data.path("E").asLong());
                return new BookEvent(data.get("s").asText(),data.get("b").asDouble(),data.get("a").asDouble(),time);
            }
            case "markPriceUpdate" -> {
                return new MarkEvent(data.get("s").asText(),data.get("p").asDouble(),data.get("r").asDouble(),data.get("E").asLong());
            }
            default -> { return null; }
        }
    }
    public static String klineStream(String symbol,String interval) { return symbol.toLowerCase(Locale.ROOT)+"@kline_"+interval; }
    public static String bookStream(String symbol) { return symbol.toLowerCase(Locale.ROOT)+"@bookTicker"; }
    public static String markStream(String symbol) { return symbol.toLowerCase(Locale.ROOT)+"@markPrice@1s"; }
    @Override public void close() {
        closed=true;
        scheduler.shutdownNow(); sender.shutdownNow();
        for(var c:connections) { var s=c.socket; if(s!=null) s.abort(); }
    }
}
