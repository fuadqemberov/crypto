# Futures Lab — Java 17 / Spring Boot / Thymeleaf

Binance üslubunda qaranlıq mövzulu, Azərbaycan dilində ticarət terminalı. Binance public REST API-dən canlı məlumat alır, LONG/SHORT siqnalları çıxarır və $2,000 virtual hesabda orderləri idarə edir. Spring MVC + Thymeleaf paneli mövcud virtual hesaba və dəyişməz order jurnalına bağlıdır. API açarı tələb etmir; real Binance hesabında order açmaq funksiyası yoxdur.

## Başlatma

Kod yenilənəndən sonra əvvəl `build.cmd`, sonra `run.cmd` işlədin. Brauzerdə **http://localhost:8080** açın. Dayandırmaq: Ctrl+C. Hazır JAR alternativi:

```powershell
java -jar target/futures-lab-1.0.0.jar
```

Kod dəyişəndə `build.cmd` işlədin. Java 17+ lazımdır. Lokal Maven `.tools` daxilindədir; ilk build internet tələb edir.

Linux/macOS və ya PATH-də Maven olduqda:

```bash
mvn clean verify
java -jar target/futures-lab-1.0.0.jar
# Skan etmədən mövcud hesabı və tarixçəni göstərmək
java -jar target/futures-lab-1.0.0.jar --bot.enabled=false
```

Panel standart olaraq yalnız `127.0.0.1:8080` ünvanına bağlanır. Başqa port üçün `--server.port=8081` istifadə edin. Şəbəkəyə açmaq lazım gəlsə, autentifikasiyalı reverse proxy arxasında yerləşdirin. Frontend üçün Node, npm və CDN tələb olunmur.

## Panel

- Cüzdan balansı = cash + ayrılmış margin; equity = cüzdan balansı + mark qiyməti üzrə açıq PnL. Təzə qiyməti olmayan açıq mövqe varsa equity/PnL sıfır kimi göstərilmir: `—` və xəbərdarlıq görünür.
- Realizə edilmiş PnL giriş, hissəli çıxış və yekun çıxış komissiyalarını ehtiva edir. Açıq PnL mark qiymətindən hesablanan, hələ realizə edilməyən məbləğdir; gələcək çıxış komissiyası daxil deyil.
- Açıq, qazanclı, zərərli və bütün orderlər üzrə filtr; simvol axtarışı; giriş/mark/çıxış, miqdar, siqnal balı, SL və TP mərhələləri.
- **Yaşıl:** yekun xalis PnL > 0. **Qırmızı:** yekun xalis PnL < 0. **Neytral:** başabaş. Status həm mətnlə, həm rənglə göstərilir. TP1/TP2 hissəli çıxışı tamamlanmış order sayılmır; stop ilə bağlanan, amma yekun xalis nəticəsi müsbət olan order qazanclıdır.
- Son 60 bağlanmış 15m şamı, EMA20 və həcm qrafiki. Simvol radarı; hər simvol üçün bal bölgüsü, məcburi filtrlər və 15m/1h/4h indikator cədvəli. Aşağı ballı/rədd edilmiş analizlər də qərarın səbəbi ilə görünür.
- Panel hər 5 saniyədə Thymeleaf fraqmenti ilə yenilənir; axtarış, seçilmiş simvol, order filtri, açıq SL/TP detalları və siyahı mövqeyi qorunur. Avtomatik yenilənməni söndürmək mümkündür. JavaScript olmadan server səhifəsi və GET filtrləri işləyir.
- `GET /` tam səhifə, `GET /dashboard/content` yenilənən fraqmentdir. Səhifəni yeniləmək Binance sorğusu və ya order yaratmır. Hazır analiz cache-i oxunur.
- Son 500 tamamlanmış order, 500 hesab hadisəsinin balans qrafiki və 30 jurnal hadisəsi paneldə görünür; ümumi qazanc/zərər sayları bütün jurnaldan hesablanır. Tarixçənin özü kəsilmir. Radarda maksimum 500 son simvol analizi saxlanılır.

```powershell
# Yalnız BTC və ETH ilə işlətmək
java -jar target/futures-lab-1.0.0.jar --bot.symbols=BTCUSDT,ETHUSDT
# Başqa müstəqil virtual hesab
java -jar target/futures-lab-1.0.0.jar --bot.data-dir=./other-account
```

## Analiz və əhatə

Aktiv USDT/USDC ilə kotirovka olunan USD-M perpetual müqavilələr avtomatik kəşf edilir. COIN-M, spot və müddətli futures daxil deyil. USDT/USDC virtual hesabda 1 USD sayılır. Standart olaraq 24 saatlıq dövriyyəsi 10 milyonun altında olanlar filtr edilir; `--bot.min-quote-volume=0` filtri söndürür. Yeni listinqdə ən azı 250 bağlanmış şam olmayanda analiz buraxılır.

15m / 1h / 4h üçün EMA20/50/200, Wilder RSI14, ATR14, ADX/DI, MACD histogram, Bollinger zolaqları, stochastic K, 20 şamlıq rolling VWAP, relative volume, OBV, support/resistance hesablanır. Engulfing, pin bar, impulse, breakout və EMA pullback yoxlanır. Stochastic diaqnostik göstəricidir; Bollinger eni bal bölgüsünə daxildir.

Bal bölgüsü:

| Kriteriya | Maksimum bal |
| --- | ---: |
| 15m / 1h / 4h EMA trend uyğunluğu | 30 (10 + 10 + 10) |
| ADX ≥ 25 və istiqamətli DI | 10 |
| 15m / 1h MACD momentum | 10 |
| 15m RSI momentum | 5 |
| Rolling VWAP | 5 |
| Relative volume ≥ 1.2 və OBV | 10 |
| Engulfing / pin bar / impuls | 10 |
| Breakout / EMA20 pullback | 5 |
| 15m və 1h EMA50 meyli | 5 |
| Bollinger eni 0.4–15% | 5 |
| 1h RSI momentum | 5 |
| **Cəmi** | **100** |

**Məcburi filtrlər baldan asılı deyil:** 1h/4h trend uyğunluğu, əks 15m trendinin olmaması, ATR/qiymət 0.1–5%, LONG RSI ≤ 78 / SHORT RSI ≥ 22, qiymətin EMA20-dən maksimum 3 ATR uzaqlığı və sonlu/etibarlı indikatorlar. Hər filtr paneldə ayrıca izah edilir. Girişdə TP2 üçün spread, slippage və komissiya nəzərə alınmaqla konservativ risk/gəlir nisbəti ən azı 1.5 olmalıdır.

**Yalnız 85/100 və yuxarı** siqnallar order mərhələsinə keçir. Hədd konfiqurasiyada, `Scanner` və birbaşa `PaperBroker.tryOpen` daxilində tətbiq edilir; 85-dən aşağı konfigurasiya qəbul edilmir. `NaN`, sonsuz və 100-dən böyük bal rədd olunur. `--bot.threshold=90` ilə həddi yüksəltmək mümkündür. Minimum balı keçmək orderə zəmanət vermir: hesab limiti, təzə qiymət, təkrar şam, spread, funding və ölçü filtrləri də keçilməlidir.

**85/100 indikator uyğunluq balıdır, 85% uğur ehtimalı deyil.** Backtest və out-of-sample kalibrasiya aparılmayıb. Qaydalar korrelyasiyalıdır; əlavə filtrlərin proqnoz gücü və gəlirliliyə təsiri hələ ölçülməyib. Xəbər/fundamental/on-chain analizi yoxdur. Siqnalsız uzun müddət normaldır. Paneldəki uğur faizi yalnız tamamlanmış virtual orderlərin faktiki nəticəsidir; siqnal balı ilə eyni anlayış deyil.

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

Əlavə yoxlamalar: 84.999 rəddi / 85 qəbulu, etibarsız bal və qiymətlər, komissiyadan sonra risk/gəlir, bağlanmış orderlərin restartdan sonra bərpası, Thymeleaf renderi, balanslar, qrafik, rəngli statuslar, axtarış escaping-i və panel GET sorğularının heç bir order/Binance sorğusu yaratmaması. HTTP renderi MockMvc ilə yoxlanılır; tarixi gəlirlilik backtest-i deyil.

Arxitektura və dəyişiklik qeydləri: [Layihə analizi](docs/ANALYSIS.az.md).

Rəsmi sənədlər: [Binance USD-M market data](https://developers.binance.com/en/docs/catalog/core-trading-derivatives-trading-usd-s-m-futures/api/rest-api/market-data), [Spring Boot 3.5 servlet web](https://docs.spring.io/spring-boot/3.5/reference/web/servlet.html), [Thymeleaf + Spring](https://www.thymeleaf.org/doc/tutorials/3.1/thymeleafspring.html).
