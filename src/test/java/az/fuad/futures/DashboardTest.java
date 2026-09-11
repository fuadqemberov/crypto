package az.fuad.futures;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.file.Path;
import java.util.*;
import static az.fuad.futures.Models.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"bot.enabled=false","logging.file.name=target/test-application.log"})
@AutoConfigureMockMvc
@Import(DashboardTest.Config.class)
class DashboardTest {
    @TempDir static Path directory;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) { registry.add("bot.data-dir",()->directory.toString()); }
    @Autowired MockMvc mvc;
    @Autowired PaperBroker broker;
    @Autowired DashboardState state;
    @Autowired GuardClient client;
    static class GuardClient extends BinanceClient {
        int calls;
        GuardClient(Settings settings) { super(settings); }
        @Override public com.fasterxml.jackson.databind.JsonNode get(String path) {
            calls++;
            throw new AssertionError("Dashboard test must never call Binance: "+path);
        }
    }
    @TestConfiguration static class Config {
        @Bean @Primary GuardClient guardClient(Settings settings) { return new GuardClient(settings); }
    }

    @Test void thymeleafRendersBalancesChartOutcomesAndSafeFragmentsWithoutTradingOnRefresh() throws Exception {
        String empty=mvc.perform(get("/")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andReturn().getResponse().getContentAsString();
        assertTrue(empty.contains("İlk bazar skanı gözlənilir")); assertTrue(empty.contains("2,000.00"));
        assertFalse(empty.contains("th:text"));
        long now=System.currentTimeMillis();
        var candles=new ArrayList<Candle>();
        for(int i=0;i<300;i++) {
            double price=100+i*.05+Math.sin(i*.8)*.4;
            candles.add(new Candle(now-(300L-i)*900000-5000,price-.1,price+1,price-1,price,100,now-(299L-i)*900000-5001));
        }
        var report=new Analysis().evaluate("BTCUSDT",candles,candles,candles);
        state.begin(); state.record(report,candles,"BLOCKED","Məcburi filtr keçilməyib"); state.finish();
        var contract=new Contract("BTCUSDT",.001,.001,5);
        var quote=new Quote(100,99.99,100.01,now,0);
        assertTrue(broker.open(new Signal("BTCUSDT",1,85,now-10000,100,2,4,Map.of(),List.of()),contract,quote));
        long sequence=broker.snapshot().sequence;
        String open=mvc.perform(get("/dashboard/content").param("asset","BTCUSDT")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(open.contains("price-chart")); assertTrue(open.contains("LONG")); assertTrue(open.contains("order-status OPEN"));
        assertTrue(open.contains("15m.ema") || open.contains("ema50SlopeAtr"));
        assertFalse(open.contains("<html")); assertEquals(sequence,broker.snapshot().sequence);
        broker.mark("BTCUSDT",new Quote(113,112.99,113.01,System.currentTimeMillis(),0));
        String won=mvc.perform(get("/").param("order","WON")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(won.contains("order-status WON")); assertTrue(won.contains("TP3"));
        assertTrue(broker.open(new Signal("ETHUSDT",1,90,System.currentTimeMillis()-10000,100,2,4,Map.of(),List.of()),new Contract("ETHUSDT",.001,.001,5),new Quote(100,99.99,100.01,System.currentTimeMillis(),0)));
        broker.mark("ETHUSDT",new Quote(90,89.99,90.01,System.currentTimeMillis(),0));
        String lost=mvc.perform(get("/").param("order","LOST")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(lost.contains("order-status LOST")); assertTrue(lost.contains("STOP_LOSS"));
        String escaped=mvc.perform(get("/").param("q","<script>alert(1)</script>")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(escaped.contains("<SCRIPT>"));
        mvc.perform(get("/css/dashboard.css")).andExpect(status().isOk());
        mvc.perform(get("/js/dashboard.js")).andExpect(status().isOk());
        assertEquals(0,client.calls);
    }
}
