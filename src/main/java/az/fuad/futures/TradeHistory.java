package az.fuad.futures;

import java.util.*;
import java.util.regex.Pattern;
import static az.fuad.futures.Models.*;

/** Rebuilds a bounded dashboard projection from the original, unmodified journal format. */
final class TradeHistory {
    private static final int LIMIT = 500;
    private static final Pattern EXIT = Pattern.compile("(?:^| )exit=([^ ]+)");
    private final Deque<ClosedTrade> trades = new ArrayDeque<>();
    private final Deque<WalletPoint> wallet = new ArrayDeque<>();
    private final Deque<Activity> activity = new ArrayDeque<>();
    private Account previous;
    private long losses, breakeven;
    private double grossProfit, grossLoss;

    void accept(Event event) {
        Account next = event.account();
        if (previous != null) {
            Set<String> remaining = new HashSet<>();
            next.positions.forEach(p -> remaining.add(p.id));
            for (Position p : previous.positions) {
                if (remaining.contains(p.id)) continue;
                double net = p.realized + next.realized - previous.realized;
                Double exit = null;
                var matcher = EXIT.matcher(event.detail());
                if (matcher.find()) {
                    try { double value = Double.parseDouble(matcher.group(1)); if (Double.isFinite(value)) exit = value; }
                    catch (NumberFormatException ignored) { /* Older journals may not include an exit. */ }
                }
                add(trades, new ClosedTrade(p.id, p.symbol, p.direction, p.openedAt, event.timestamp(),
                        p.entry, exit, p.initialQuantity, net, p.signal == null ? 0 : p.signal.score(), event.type(), p.quantity>0?p.margin*p.initialQuantity/p.quantity:0));
                if (net > 0) grossProfit += net;
                else if (net < 0) { grossLoss -= net; losses++; }
                else breakeven++;
            }
        }
        if (!event.type().equals("SIGNAL") && !event.type().equals("REJECTED")) {
            add(wallet, new WalletPoint(event.timestamp(), next.cash + next.positions.stream().mapToDouble(p -> p.margin).sum()));
            add(activity, new Activity(event.timestamp(), event.type(), event.detail()));
        }
        previous = next;
    }

    private <T> void add(Deque<T> items, T value) {
        items.addLast(value);
        while (items.size() > LIMIT) items.removeFirst();
    }
    List<ClosedTrade> trades() { var result = new ArrayList<>(trades); Collections.reverse(result); return List.copyOf(result); }
    List<WalletPoint> wallet() { return List.copyOf(wallet); }
    List<Activity> activity() { var result = new ArrayList<>(activity); Collections.reverse(result); return result.stream().limit(30).toList(); }
    long losses() { return losses; }
    long breakeven() { return breakeven; }
    Double profitFactor() { return grossLoss > 0 ? grossProfit / grossLoss : null; }
}
