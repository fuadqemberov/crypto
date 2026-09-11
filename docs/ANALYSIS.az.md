# Layihə analizi və yenilənmiş axın

Layihə Java 17 / Spring Boot 3.5.11 ilə yazılmış USD-M perpetual **paper trading** tətbiqidir. İstifadəçi balansı virtualdır; Binance public API yalnız bazar məlumatı üçün işlədilir. Spring versiyası, girişdə maksimum 7% equity ayırması, 1–3x leverage, TP/SL mexanizmi və mövcud jurnal formatı qorunub.

| Komponent | Məsuliyyət |
| --- | --- |
| `BinanceClient` | Müqavilələr, 24h dövriyyə, bağlanmış şamlar, mark/bid/ask/funding; rate-limit və təzə məlumat yoxlamaları |
| `Analysis` | 3 interval üzrə indikatorlar, 100 ballıq izahlı qiymətləndirmə, baldan asılı olmayan keyfiyyət filtrləri |
| `Scanner` | Likvid simvolların skanı, eyni şamın təkrar analizinin qarşısı, yalnız 85+ siqnalları girişə yönləndirmək |
| `PaperBroker` | Order ölçüsü, komissiya/slippage, mövqe limitləri, təkrar giriş, 85+ son yoxlaması, TP/SL və PnL |
| `Journal` | Append-only, diskə forced yazılan hesab snapshot-ları; restart və yarımçıq son sətirdən bərpa |
| `TradeHistory` | Mövcud jurnal hadisələrindən tamamlanmış orderləri, xalis nəticəni və cüzdan balansını bərpa edən oxu proyeksiyası |
| `DashboardState` | Son bazar analizləri, qəbul/rədd qərarları və skan vəziyyətinin yaddaş cache-i |
| `DashboardController` | Hesab və analiz snapshot-larını birləşdirən, dəyişiklik etməyən GET səhifəsi/fraqmenti |
| `PriceChart` / `Display` | Serverdə SVG qrafik geometriyası, rəqəmlər və Bakı saatı |
| Thymeleaf / CSS / JS | Responsiv terminal, order filtrləri, 5 saniyəlik yenilənmə və detallı siqnal görünüşü |

## Aşkarlanan boşluqlar və düzəlişlər

1. Tətbiqdə HTTP/Thymeleaf interfeysi yox idi. MVC və Thymeleaf əlavə edildi; panel birbaşa virtual hesabı göstərir.
2. Bağlı orderlər `Account.positions` siyahısından silinirdi. `TradeHistory` ardıcıl snapshot-larda yoxa çıxan mövqeni aşkar edir, əvvəlki hissəli realizə edilmiş PnL ilə yekun çıxışı birləşdirir. Beləliklə köhnə tarixçə formatını dəyişmədən qazanan/zərərli orderlər görünür.
3. `score < threshold` təkbaşına `NaN` balını rədd etmirdi. Konfiqurasiya, siqnal, şam və quote məlumatında sonlu rəqəm yoxlaması əlavə edildi. 85 həddi broker səviyyəsində də məcburidir.
4. Siqnal alınmadıqda səbəblər yalnız `null` nəticənin arxasında qalırdı. İndi hətta rədd edilmiş bazarda `Report` mövcuddur; hər indikatorun balı və hər məcburi filtr ayrı göstərilir.
5. Qiymətin trenddən həddindən artıq uzaqlaşması və xərclərin kiçik target-i mənasızlaşdırması üçün əlavə filtrlər quruldu. Bunlar yeni qaydalardır; gəlirliliyə təsiri ayrıca ölçülməlidir.
6. Balans və açıq PnL üçün köhnə mark qiymətindən istifadə edilmir. Təzə qiymət yoxdursa panel bunu açıq göstərir.

## Giriş qərarı

Bağlanmış və ardıcıl 15m/1h/4h şamları → indikatorlar və 100 ballıq hesabat → məcburi keyfiyyət filtrləri → bal ≥ konfiqurasiya həddi (minimum 85) → təzə quote → spread/funding/qiymət sapması → mövqe və təkrar şam limitləri → komissiya/slippage sonrası TP2 risk/gəlir ≥ 1.5 → lot və balans hesabı → diskə `OPEN` → hesab yenilənir.

Panelin yenilənməsi bu zənciri çağırmır. `GET` sorğuları hazır snapshot/cache oxuyur. Panelə real ticarət açarı, depozit, çıxarış və ya real Binance order endpoint-i əlavə edilməyib.

## Məlumatın düzgün şərhi

- **Cüzdan balansı:** sərbəst cash + mövcud mövqelərə ayrılmış margin.
- **Equity:** cüzdan balansı + təzə mark ilə açıq PnL. Açıq PnL gələcək çıxış komissiyasını çıxmır.
- **Realizə edilmiş PnL:** giriş komissiyası və icra edilmiş bütün çıxışların komissiyadan sonrakı nəticəsi. Buraya hələ açıq mövqelərin artıq baş vermiş hissəli çıxışları da daxildir.
- **Tamamlanmış orderin xalis PnL-i:** həmin mövqenin bütün giriş/çıxış ödənişlərindən sonrakı cəmi. Rəng `TP`/`SL` adına görə seçilmir. Hissəli qazancdan sonra stop ilə bağlanan orderin ümumi nəticəsi müsbət qala bilər.
- **Siqnal balı:** indikator uyğunluğu. Kalibrasiya olunmuş ehtimal deyil. Məcburi filtrlər rədd edirsə, yüksək bal belə order yaratmır.
- **Uğur faizi:** bütün tamamlanmış virtual orderlər içində xalis nəticəsi müsbət olanların payı. Başabaş nəticələr məxrəcə daxildir.

## Qalan məhdudiyyətlər

Mövcud simulyatorda funding ödənişi, liquidation/ADL və dərin order-book fill modeli yoxdur. Hesablamalar `double` ilə aparılır; lot miqdarı `BigDecimal` ilə aşağı yuvarlaqlaşdırılır. Tam maliyyə uçotu və real exchange inteqrasiyası ayrıca işdir. İnternet/tətbiq dayananda TP/SL icra edilmir və offline dövrdə toxunulmuş səviyyələr bərpa olunmur. Jurnal hər hadisədə tam hesab snapshot-ı yazdığı üçün disk ölçüsü zamanla artır. Panel proyeksiyası yaddaşda limitlidir, amma disk tarixçəsi kəsilmir. Analiz cache-i restartdan sonra yenidən dolur; hesab/order tarixçəsi qalır.

## Rəsmi istinadlar

- [Spring Boot 3.5 MVC və template mühərrikləri](https://docs.spring.io/spring-boot/3.5/reference/web/servlet.html)
- [Thymeleaf-in Spring MVC ilə inteqrasiyası](https://www.thymeleaf.org/doc/tutorials/3.1/thymeleafspring.html)
- [Binance USD-M REST market data](https://developers.binance.com/en/docs/catalog/core-trading-derivatives-trading-usd-s-m-futures/api/rest-api/market-data)
