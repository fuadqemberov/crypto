package az.fuad.futures;

import java.time.*;
import java.util.*;
import static az.fuad.futures.Models.*;

/** Readable projection of the durable account journal. */
final class OrderReport {
    private final Map<String,Long> numbers=new HashMap<>();
    private Account previous=new Account();
    private long nextNumber;
    static String balance(Account a) {
        double margin=a.positions.stream().mapToDouble(p->p.margin).sum();
        return String.format(Locale.ROOT,"Balance: %.2f USD | Available: %.2f | Margin: %.2f | Net realized: %+.2f | Fees: %.2f | Open: %d",
                a.cash+margin,a.cash,margin,a.realized,a.fees,a.positions.size());
    }
    String accept(Event event) {
        Account a=event.account(); String line=null;
        if(event.type().equals("INIT")) line="PAPER ACCOUNT | "+balance(a);
        for(Position p:a.positions) {
            if(previous.positions.stream().noneMatch(x->x.id.equals(p.id))) {
                numbers.put(p.id,++nextNumber);
                line=String.format(Locale.ROOT,"Order no%d : OPEN | %s %s | Entry: %.8f | SL: %.8f | TP: %.8f / %.8f / %.8f | Score: %.0f/100 | %s",
                        numbers.get(p.id),p.symbol,p.direction==1?"LONG":"SHORT",p.entry,p.stop,p.tp1,p.tp2,p.tp3,p.signal.score(),balance(a));
            }
        }
        for(Position old:previous.positions) {
            Position remaining=a.positions.stream().filter(p->p.id.equals(old.id)).findFirst().orElse(null);
            if(remaining==null || remaining.quantity<old.quantity) {
                double net=remaining==null?old.realized+a.realized-previous.realized:remaining.realized;
                String status=remaining!=null?"PARTIAL":net>0?"SUCCEEDED":net<0?"FAILED":"BREAKEVEN";
                line=String.format(Locale.ROOT,"Order no%d : %s | %s %s | %s | Order net PnL: %+.4f USD | %s",
                        numbers.get(old.id),status,old.symbol,old.direction==1?"LONG":"SHORT",event.type(),net,balance(a));
            }
        }
        previous=a;
        return line==null?null:Instant.ofEpochMilli(event.timestamp())+" | "+line;
    }
}
