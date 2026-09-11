package az.fuad.futures;

import java.util.*;
import static az.fuad.futures.Models.*;

public record PriceChart(List<Bar> bars, List<Axis> axes, String ema, String wallet, double lastPrice) {
    public record Bar(double x, double high, double low, double top, double height, double volume, boolean up) {}
    public record Axis(double y, double price) {}
    public static PriceChart of(List<Candle> source, List<WalletPoint> wallet) {
        List<Candle> candles = source.subList(Math.max(0, source.size()-60), source.size());
        if (candles.isEmpty()) return new PriceChart(List.of(), List.of(), "", walletLine(wallet), 0);
        double low = candles.stream().mapToDouble(Candle::low).min().orElse(0);
        double high = candles.stream().mapToDouble(Candle::high).max().orElse(1);
        double range = Math.max(high-low, high*.001);
        double bottom = low - range*.12, span = range*1.24;
        double maxVolume = Math.max(1, candles.stream().mapToDouble(Candle::volume).max().orElse(1));
        List<Bar> bars = new ArrayList<>(); List<Axis> axes = new ArrayList<>();
        for (int i=0; i<candles.size(); i++) {
            Candle c = candles.get(i);
            bars.add(new Bar(18+i*12.5, y(c.high(),bottom,span), y(c.low(),bottom,span),
                    y(Math.max(c.open(),c.close()),bottom,span), Math.max(1,Math.abs(c.close()-c.open())/span*220),
                    c.volume()/maxVolume*38, c.close()>=c.open()));
        }
        for (int i=0; i<5; i++) axes.add(new Axis(20+i*55, bottom+span*(1-i/4.0)));
        double[] emaValues = Analysis.ema(source.stream().mapToDouble(Candle::close).toArray(),20);
        StringBuilder line = new StringBuilder();
        for (int i=0; i<candles.size(); i++) {
            double value = y(emaValues[source.size()-candles.size()+i],bottom,span);
            line.append(String.format(Locale.ROOT,"%.2f,%.2f ",18+i*12.5,Math.max(10,Math.min(250,value))));
        }
        return new PriceChart(List.copyOf(bars),List.copyOf(axes),line.toString(),walletLine(wallet),candles.get(candles.size()-1).close());
    }
    private static double y(double price,double bottom,double span) { return 240-(price-bottom)/span*220; }
    private static String walletLine(List<WalletPoint> source) {
        if (source.isEmpty()) return "";
        double min = source.stream().mapToDouble(WalletPoint::balance).min().orElse(0);
        double max = source.stream().mapToDouble(WalletPoint::balance).max().orElse(1);
        StringBuilder line = new StringBuilder();
        for (int i=0; i<source.size(); i++) line.append(String.format(Locale.ROOT,"%.2f,%.2f ",
                i*280.0/Math.max(1,source.size()-1), 45-(source.get(i).balance()-min)/Math.max(.01,max-min)*35));
        return line.toString();
    }
}
