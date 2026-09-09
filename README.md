# Binance Futures Lab — Java 17 / Spring Boot

Desktopdakı `Cryprto` qovluğunda işləyən konsol tətbiqi. Binance public REST API-dən canlı məlumat alır, LONG/SHORT siqnalları çıxarır və $2,000 virtual hesabda orderləri idarə edir. API açarı tələb etmir; real Binance hesabında order açmaq funksiyası yoxdur.

## Başlatma

`run.cmd` faylını iki dəfə klikləyin. Dayandırmaq: Ctrl+C. Hazır JAR alternativi:

```powershell
java -jar target/futures-lab-1.0.0.jar
```

Kod dəyişəndə `build.cmd` işlədin. Java 17+ lazımdır. Lokal Maven `.tools` daxilindədir; ilk build internet tələb edir.

```powershell
# Yalnız BTC və ETH ilə işlətmək
java -jar target/futures-lab-1.0.0.jar --bot.symbols=BTCUSDT,ETHUSDT
# Başqa müstəqil virtual hesab
java -jar target/futures-lab-1.0.0.jar --bot.data-dir=./other-account
```

## Analiz və əhatə

Aktiv USDT/USDC ilə kotirovka olunan USD-M perpetual müqavilələr avtomatik kəşf edilir. COIN-M, spot və müddətli futures daxil deyil. USDT/USDC virtual hesabda 1 USD sayılır. Standart olaraq 24 saatlıq dövriyyəsi 10 milyonun altında olanlar filtr edilir; `--bot.min-quote-volume=0` filtri söndürür. Yeni listinqdə ən azı 250 bağlanmış şam olmayanda analiz buraxılır.

15m / 1h / 4h üçün EMA20/50/200, Wilder RSI14, ATR14, ADX/DI, MACD histogram, Bollinger zolaqları, stochastic K, 20 şamlıq rolling VWAP, relative volume, OBV, support/resistance hesablanır. Engulfing, pin bar, impulse, breakout və EMA pullback yoxlanır. Bollinger və stochastic diaqnostik detallardır, bala ayrıca əlavə olunmur.

Bal bölgüsü: 3 timeframe trendi 45; ADX/DI 10; MACD 10; RSI 5; VWAP 5; həcm/OBV 10; şam 10; struktur 5. 1h və 4h trend uyğunluğu və ATR volatilite diapazonu məcburidir. Konsola yalnız minimum 90/100 siqnallar və onların bütün indikator dəyərləri yazılır; texniki status/xəta mesajları da görünür.

**90/100 indikator uyğunluq balıdır, 90% uğur ehtimalı deyil.** Backtest və out-of-sample kalibrasiya aparılmayıb. Bu qayda sistemi 20 illik treyderin bütün bacarıqlarını, xəbər/fundamental/on-chain analizi və zəmanətli gəliri əvəz etmir. Qaydalar korrelyasiyalıdır; yüksək balın proqnoz gücü hələ ölçülməyib. Siqnalsız uzun müddət normaldır.

## Virtual hesab və orderlər

- İlkin cash: $2,000. Hər girişin margin + giriş komissiyası cari equity-nin maksimum 7%-i, ilk order üçün maksimum $140.
- Eyni anda maksimum 5 mövqe, hər simvola 1 mövqe. Standart leverage 1x; konfiqurasiya 1–3x qəbul edir. Mövcud açıq mövqelər üçün təzə mark qiyməti yoxdursa yeni order buraxılır.
- Miqdar LOT_SIZE addımına aşağı yuvarlaqlaşdırılır, minimum miqdar və notional yoxlanır. Balans çatmırsa ölçü azalır və ya order açılmır.
- Başlanğıc SL: girişdən 2×ATR. TP1/TP2/TP3: 1R/2R/3R; hərəsində ilkin miqdarın təxminən üçdə biri bağlanır. TP1-dən sonra SL girişə, TP2-dən sonra +1R-ə keçir.
- Mark qiyməti SL məsafəsinin son 15%-nə girəndə `SL_PROXIMITY` ilə avtomatik çıxış. SL keçilibsə `STOP_LOSS`. TP-lər icra oluna bilən bid/ask qiyməti ilə yoxlanır.
- Giriş/çıxış komissiyası 0.05%, hər tərəf üçün 3 bps slippage. Spread >15 bps, funding göstəricisi mütləq 0.1%-dən böyük və ya qiymət siqnal qiymətindən 1 ATR uzaqdırsa giriş yoxdur.
- Real order book dərinliyi, funding ödənişləri, liquidation/ADL və exchange fill modeli daxil deyil. Hissəli virtual çıxışların miqdarı exchange lot qaydasına yenidən yuvarlaqlaşdırılmır. 1x standartını saxlamaq daha sadə simulyasiya verir.
- Hər 5 saniyədən sonra monitor dövrü başlayır; API sorğularının müddəti intervala əlavə olunur. Tətbiq bağlı/internet kəsilmiş halda SL/TP işləmir. Açıldıqda mövcud mövqelər ilk təzə qiymətdə idarə edilir; offline dövrdə toxunulmuş TP/SL-lər bərpa edilmir. Gap zamanı çıxış cari bid/ask qiymətindədir, SL qiymətinə zəmanət yoxdur.

## Davamlı yaddaş

`data/order_history.txt` UTF-8 JSON Lines formatındadır. Hər `INIT`, `SIGNAL`, `OPEN`, TP və SL hadisəsi **append** edilir və `FileChannel.force(true)` ilə diskə göndərilir. Hər sətir hesabın tam snapshot-ını saxlayır; siqnal detalları və bütün indikatorlar da tarixçədədir. Başlanğıcda son tam hadisədən cash, orderlər, TP mərhələləri, fees, realized PnL və son giriş şamı bərpa edilir. Balans hər restartda sıfırlanmır.

Eyni qovluqda ikinci proses fayl kilidi ilə bloklanır. Crash zamanı yalnız yarımçıq son sətir ayrıca `.bin` faylına köçürülür və atılır. Tam sətirdə korlanma varsa proqram balansı sıfırlamaq əvəzinə dayanır. Yazı xətasında yeni hesab əməliyyatları dayanır. `order_history.txt` silinməməlidir; backup üçün proqramı dayandırıb bütün `data` qovluğunu köçürün. Disk/hardware itkisinə qarşı ayrıca backup lazımdır. Tarixçə avtomatik silinmir, zamanla böyüyür.

`data/application.log` konsolun fırlanan texniki jurnalıdır. Əsas dəyişməz tarixçə `order_history.txt`-dir. `initial-balance` yalnız boş tarixçə üçün tətbiq edilir.

## API və test

REST sorğuları minimum 350 ms aralı göndərilir. HTTP timeout 15 saniyədir; 429/418 üçün ümumi cooldown var. Bütün bazarın skanı bir neçə dəqiqə çəkə bilər. Yalnız bağlanmış, ardıcıl və təzə şamlar istifadə edilir. Əməliyyat monitoru skandan ayrı scheduler thread-dədir.

`build.cmd` indikator, hesablaşma, LONG/SHORT, TP/SL, təzə məlumat, təkrar giriş, disk bərpası və korlanma testlərini çalışdırır. Bu testlər strategiyanın gəlirliliyini sübut etmir.

Rəsmi sənədlər: [Binance USD-M market data](https://developers.binance.com/en/docs/catalog/core-trading-derivatives-trading-usd-s-m-futures/api/rest-api/market-data), [Spring Boot](https://docs.spring.io/spring-boot/).
