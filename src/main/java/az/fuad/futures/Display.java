package az.fuad.futures;

import org.springframework.stereotype.Component;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

@Component("display")
public class Display {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm:ss").withZone(ZoneId.of("Asia/Baku"));
    public String number(Number value, int digits) {
        if (value == null || !Double.isFinite(value.doubleValue())) return "—";
        NumberFormat format = NumberFormat.getNumberInstance(Locale.US);
        format.setMinimumFractionDigits(digits); format.setMaximumFractionDigits(digits);
        return format.format(value.doubleValue());
    }
    public String money(Number value) { return number(value, 2); }
    public String signed(Number value) { return value == null ? "—" : (value.doubleValue() > 0 ? "+" : "") + money(value); }
    public String price(Number value) {
        if (value == null) return "—";
        double magnitude = Math.abs(value.doubleValue());
        return number(value, magnitude >= 100 ? 2 : magnitude >= 1 ? 4 : 8);
    }
    public String tone(Number value) { return value == null ? "muted" : value.doubleValue() > 0 ? "positive" : value.doubleValue() < 0 ? "negative" : "muted"; }
    public String time(long time) { return time <= 0 ? "—" : TIME.format(Instant.ofEpochMilli(time)); }
    public String decision(String value) {
        return switch (value) {
            case "OPENED" -> "Order açıldı";
            case "EXECUTION_REJECTED" -> "Giriş buraxıldı";
            case "LOW_SCORE" -> "Bal aşağıdır";
            case "BLOCKED" -> "Filtr keçilmədi";
            case "OPEN" -> "Açıq";
            case "WON" -> "Qazanclı";
            case "LOST" -> "Zərərli";
            case "FLAT" -> "Başabaş";
            default -> value;
        };
    }
}
