package az.fuad.futures;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import jakarta.servlet.http.HttpServletResponse;
import java.util.*;
import static az.fuad.futures.Models.*;

@Controller
public class DashboardController {
    public record Summary(double cash, double wallet, double margin, Double equity, Double unrealized,
                          double realized, double fees, long closed, long wins, long losses, long breakeven,
                          Double winRate, Double profitFactor, int stale, int open) {}
    public record OrderRow(String id, String symbol, String side, double score, double entry, Double price,
                           double quantity, Double pnl, String status, long time, String detail,
                           Double stop, Double tp1, Double tp2, Double tp3, int stage, double invested, double notional, Double roi) {}
    private final PaperBroker broker;
    private final DashboardState state;
    private final Settings settings;

    public DashboardController(PaperBroker broker, DashboardState state, Settings settings) {
        this.broker=broker; this.state=state; this.settings=settings;
    }
    @GetMapping({"/", "/dashboard/content"})
    public String dashboard(@RequestParam(defaultValue="OPEN") String order,
                            @RequestParam(defaultValue="") String q,
                            @RequestParam(defaultValue="") String asset,
                            jakarta.servlet.http.HttpServletRequest request, HttpServletResponse response, Model model) {
        response.setHeader("Cache-Control","no-store");
        response.setHeader("X-Content-Type-Options","nosniff");
        response.setHeader("X-Frame-Options","DENY");
        response.setHeader("Content-Security-Policy","default-src 'self'; style-src 'self'; script-src 'self'; img-src 'self' data:; object-src 'none'; base-uri 'self'; frame-ancestors 'none'");
        String filter = Set.of("OPEN","WON","LOST","ALL").contains(order) ? order : "OPEN";
        String query = q.strip().toUpperCase(Locale.ROOT);
        if(query.length()>30) query=query.substring(0,30);
        final String search=query;
        var view=broker.view(); var account=view.account();
        List<OrderRow> rows=new ArrayList<>();
        double margin=0, unrealized=0; int stale=0;
        for(Position p:account.positions) {
            Quote quote=view.marks().get(p.symbol);
            boolean fresh=quote!=null && quote.fresh();
            Double price=fresh?quote.mark():null;
            Double pnl=fresh?p.direction*(price-p.entry)*p.quantity:null;
            margin+=p.margin; if(fresh) unrealized+=pnl; else stale++;
            double invested=p.quantity>0?p.margin*p.initialQuantity/p.quantity:0;
            double exit=fresh?(p.direction==1?quote.bid():quote.ask())*(1-p.direction*settings.slippageBps()/10000):0;
            Double net=fresh?p.realized+p.direction*(exit-p.entry)*p.quantity-exit*p.quantity*settings.feeRate():null;
            rows.add(new OrderRow(p.id,p.symbol,p.direction==1?"LONG":"SHORT",p.signal==null?0:p.signal.score(),
                    p.entry,price,p.quantity,net,"OPEN",p.openedAt,fresh?"İndi bağlansa təxmini xalis nəticə; hissəli çıxışlar və komissiyalar daxil":"Təzə qiymət gözlənilir",
                    p.stop,p.tp1,p.tp2,p.tp3,p.stage,invested,p.entry*p.initialQuantity,net!=null && invested>0?net/invested*100:null));
        }
        for(ClosedTrade trade:view.trades()) rows.add(new OrderRow(trade.id(),trade.symbol(),trade.side(),trade.score(),
                trade.entry(),trade.exit(),trade.quantity(),trade.netPnl(),trade.outcome(),trade.closedAt(),trade.reason(),null,null,null,null,3,trade.initialMargin(),trade.entry()*trade.quantity(),trade.initialMargin()>0?trade.netPnl()/trade.initialMargin()*100:null));
        rows.sort(Comparator.comparingLong(OrderRow::time).reversed());
        List<OrderRow> visible=rows.stream().filter(r->filter.equals("ALL") || r.status().equals(filter))
                .filter(r->r.symbol().contains(search)).toList();
        var markets=state.markets();
        var selected=markets.stream().filter(m->m.report().symbol().equals(asset)).findFirst()
                .orElseGet(()->markets.stream().filter(m->m.report().symbol().equals("BTCUSDT")).findFirst().orElse(markets.isEmpty()?null:markets.get(0)));
        double wallet=account.cash+margin;
        model.addAttribute("summary",new Summary(account.cash,wallet,margin,stale==0?wallet+unrealized:null,
                stale==0?unrealized:null,account.realized,account.fees,account.closed,account.wins,view.losses(),view.breakeven(),
                account.closed>0?account.wins*100.0/account.closed:null,view.profitFactor(),stale,account.positions.size()));
        model.addAttribute("orders",visible); model.addAttribute("orderFilter",filter); model.addAttribute("query",search);
        model.addAttribute("settings",settings); model.addAttribute("halted",view.halted());
        model.addAttribute("scan",state.status()); model.addAttribute("markets",markets);
        model.addAttribute("selected",selected); model.addAttribute("asset",selected==null?"":selected.report().symbol());
        model.addAttribute("chart",PriceChart.of(selected==null?List.of():selected.candles(),view.wallet()));
        model.addAttribute("activity",view.activity()); model.addAttribute("updatedAt",System.currentTimeMillis());
        return request.getRequestURI().equals("/dashboard/content")?"dashboard :: workspace":"dashboard";
    }
}
