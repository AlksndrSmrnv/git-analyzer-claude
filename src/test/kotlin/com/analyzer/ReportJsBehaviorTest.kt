package com.analyzer

import org.graalvm.polyglot.Context
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.time.ZoneId

/**
 * Поведенческие тесты JavaScript, встроенного в report.html.
 *
 * Отчёт генерируется по-настоящему через HtmlReportGenerator.generate, из HTML
 * извлекаются блок данных (window.REPORT_DATA) и report.js, после чего они
 * исполняются в GraalJS с минимальным DOM-стабом. Часовой пояс JS-движка
 * задаётся через Context.Builder.timeZone — это аналог часового пояса браузера,
 * в котором открыт отчёт. generatedAt подменяется на фиксированную дату,
 * потому что generate() штампует реальное текущее время.
 */
class ReportJsBehaviorTest {

    @TempDir
    lateinit var tempDir: Path

    private val generator = HtmlReportGenerator()
    private var context: Context? = null

    @AfterEach
    fun closeContext() {
        context?.close()
        context = null
    }

    private fun record(author: String, date: String, test: String, system: String? = null) =
        TestRecord(author, test, "src/test/kotlin/SomeTest.kt", date, system)

    /**
     * Генерирует отчёт, извлекает из него данные и report.js и исполняет их
     * в GraalJS. init() внутри report.js отрабатывает сразу (как в браузере)
     * и выставляет период «Последняя неделя».
     *
     * generatedAt и согласованный с ним yearStart подменяются после генерации,
     * потому что generate() штампует реальное текущее время.
     */
    private fun loadReport(
        records: List<TestRecord>,
        generatedAt: String,
        browserZone: String,
        authorNames: Map<String, String> = emptyMap(),
        excludedTesters: Set<String> = emptySet()
    ): Context {
        val generatedAtParsed = java.time.OffsetDateTime.parse(generatedAt)
        val yearStart = generatedAtParsed.toLocalDate().withDayOfYear(1)
            .atStartOfDay()
            .atOffset(generatedAtParsed.offset)
            .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val outputDir = tempDir.resolve("report-${System.nanoTime()}").toString()
        generator.generate(
            records = records,
            repoPath = "/repo",
            outputDir = outputDir,
            authorNames = authorNames,
            excludedTesters = excludedTesters
        )
        val html = File(outputDir, "report.html").readText(Charsets.UTF_8)
        val scripts = Regex("<script>(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
            .findAll(html)
            .map { it.groupValues[1] }
            .toList()
        assertEquals(3, scripts.size, "report.html should contain Chart.js, data and report.js blocks")

        val ctx = Context.newBuilder("js")
            .timeZone(ZoneId.of(browserZone))
            .option("engine.WarnInterpreterOnly", "false")
            .build()
        ctx.eval("js", DOM_STUB)
        // scripts[0] — Chart.js: не исполняем, report.js работает и без него
        // (все вызовы графиков под проверкой typeof Chart !== 'undefined').
        ctx.eval("js", scripts[1])
        ctx.eval("js", "window.REPORT_DATA.generatedAt = '$generatedAt';")
        ctx.eval("js", "window.REPORT_DATA.yearStart = '$yearStart';")
        ctx.eval("js", scripts[2])
        context = ctx
        return ctx
    }

    private fun Context.js(expression: String) = eval("js", expression)
    private fun Context.clickPeriod(period: String) = js("__clickPeriod('$period')")
    private fun Context.innerHtml(id: String): String =
        js("document.getElementById('$id').innerHTML").asString()
    private fun Context.isHidden(id: String): Boolean =
        js("document.getElementById('$id').hidden").asBoolean()

    // textContent может быть числом (renderVelocity/renderForecast присваивают
    // числа, браузер сам приводит к строке) — приводим явно на стороне JS.
    private fun Context.textContent(id: String): String =
        js("String(document.getElementById('$id').textContent)").asString()

    private fun Context.selectSystem(system: String) =
        js("document.getElementById('systemFilter').value = '$system'; " +
            "document.getElementById('systemFilter').listeners.change();")

    @Test
    @DisplayName("YTD: период начинается 1 января года формирования отчёта")
    fun ytdStartsAtJanuaryFirstOfReportYear() {
        val ctx = loadReport(
            records = listOf(
                record("a@x.com", "2025-12-20T10:00:00+03:00", "lastYearTest"),
                record("a@x.com", "2026-03-05T10:00:00+03:00", "marchTest")
            ),
            generatedAt = "2026-07-10T12:00:00+03:00",
            browserZone = "Europe/Moscow"
        )

        ctx.clickPeriod("ytd")

        // Декабрьская запись 2025 года отфильтрована, мартовская — нет
        assertEquals("<strong>1</strong>", ctx.innerHtml("totalCount"))
        assertTrue(ctx.innerHtml("summaryBody").contains("a@x.com"))
        // Период — Янв..Июл 2026 (7 месяцев), тест был только в марте
        assertTrue(
            ctx.innerHtml("inactiveList").contains("6 из 7"),
            "author with a single March test should have 6 of 7 zero months, got: ${ctx.innerHtml("inactiveList")}"
        )
    }

    @Test
    @DisplayName("YTD: начало года считается в часовом поясе формирования отчёта")
    fun ytdStartsInReportTimezone() {
        // Отчёт сформирован 01.01.2027 00:30 в Москве; браузер в UTC ещё видит
        // 31 декабря 2026. Начало YTD — точный момент 2027-01-01T00:00+03:00
        // (= 21:00Z 31 декабря), а не полночь в поясе браузера: тест, добавленный
        // в первые полчаса 2027 года по Москве, попадает в период, а записи
        // 2026 года — нет, и диапазон не переворачивается.
        val ctx = loadReport(
            records = listOf(
                record("a@x.com", "2026-06-15T12:00:00+03:00", "juneTest"),
                record("b@x.com", "2027-01-01T00:15:00+03:00", "newYearTest")
            ),
            generatedAt = "2027-01-01T00:30:00+03:00",
            browserZone = "UTC"
        )

        ctx.clickPeriod("ytd")

        assertEquals("<strong>1</strong>", ctx.innerHtml("totalCount"))
        val summary = ctx.innerHtml("summaryBody")
        assertTrue(summary.contains("b@x.com"), "record from the first 30 minutes of 2027 (Moscow) must be included, got: $summary")
        assertFalse(summary.contains("a@x.com"), "2026 record must be excluded from YTD of 2027, got: $summary")

        // Окно YTD в поясе браузера — один календарный месяц (Дек 2026 по UTC):
        // a в нём молчал («1 из 1»), b активен — зелёная карточка «без пропусков».
        val inactive = ctx.innerHtml("inactiveList")
        assertTrue(inactive.contains("a@x.com") && inactive.contains("1 из 1"), "got: $inactive")
        val cardB = inactive.substringAfter("b@x.com")
        assertTrue(inactive.contains("b@x.com") && cardB.contains("без пропусков"), "got: $inactive")
    }

    @Test
    @DisplayName("Записи с другим UTC-offset попадают в тот же месяц, что и фильтр периода")
    fun offsetRecordsClassifiedConsistently() {
        // 2026-05-01T00:30+03:00 в UTC-браузере — это ещё 30 апреля. Фильтр
        // «Апрель 2026» запись включает, значит и ключ месяца активности обязан
        // быть апрельским: автор активен и в сводке неактивных не показывается.
        val ctx = loadReport(
            records = listOf(record("a@x.com", "2026-05-01T00:30:00+03:00", "borderTest")),
            generatedAt = "2026-07-10T12:00:00+03:00",
            browserZone = "UTC"
        )

        ctx.js("document.getElementById('customMonth').value = '4';")
        ctx.js("document.getElementById('customYear').value = '2026';")
        ctx.clickPeriod("custom")
        ctx.js("document.getElementById('applyCustom').click();")

        assertEquals("<strong>1</strong>", ctx.innerHtml("totalCount"), "April filter should include the record")
        val inactive = ctx.innerHtml("inactiveList")
        assertFalse(ctx.isHidden("inactiveList"))
        assertTrue(
            inactive.contains("data-month=\"2026-04\" data-count=\"1\""),
            "April must be a green cell — the record lands in April in browser time, got: $inactive"
        )
        assertFalse(inactive.contains("inactive-month-zero"), "no zero months expected, got: $inactive")
        assertTrue(inactive.contains("без пропусков"), "active author must get a clean badge, got: $inactive")
    }

    @Test
    @DisplayName("Сводка неактивных: корректный список авторов, счётчики и сортировка")
    fun listsInactiveTestersWithZeroMonthCounts() {
        val records = (1..7).map { m ->
            record("a@x.com", "2026-%02d-05T10:00:00+03:00".format(m), "monthly$m")
        } + listOf(
            record("b@x.com", "2026-01-15T10:00:00+03:00", "jan"),
            record("b@x.com", "2026-05-20T10:00:00+03:00", "may"),
            record("c@x.com", "2025-11-03T10:00:00+03:00", "old")
        )
        val ctx = loadReport(
            records = records,
            generatedAt = "2026-07-10T12:00:00+03:00",
            browserZone = "Europe/Moscow",
            authorNames = mapOf(
                "a@x.com" to "Автор А",
                "b@x.com" to "Автор Б",
                "c@x.com" to "Автор В"
            )
        )

        ctx.clickPeriod("ytd")
        val html = ctx.innerHtml("inactiveList")

        // Автор А активен каждый месяц — показан зелёной карточкой в конце списка
        val cardA = html.substringAfter("Автор А")
        assertTrue(html.contains("Автор А") && cardA.contains("без пропусков"), "got: $html")
        assertTrue(html.contains("inactive-card-ok"), "fully active card must be green, got: $html")
        assertTrue(html.contains("Автор Б") && html.contains("5 из 7"), "got: $html")
        assertTrue(html.contains("Автор В") && html.contains("7 из 7"), "got: $html")
        assertTrue(
            html.indexOf("Автор В") < html.indexOf("Автор Б") && html.indexOf("Автор Б") < html.indexOf("Автор А"),
            "authors must be sorted by zero-month count descending, got: $html"
        )

        // Карточка Б: полоса месяцев — нулевой февраль красный, май с одним тестом зелёный
        val cardB = html.substringAfter("Автор Б")
        assertTrue(
            cardB.contains("inactive-month-zero") && cardB.contains("data-month=\"2026-02\" data-count=\"0\""),
            "February must be a zero cell, got: $cardB"
        )
        assertTrue(
            cardB.contains("inactive-month-ok") && cardB.contains("data-month=\"2026-05\" data-count=\"1\""),
            "May must be a green cell with the test count, got: $cardB"
        )

        // Бейджи по доле нулевых месяцев: В — 7 из 7 (high) с выделенной карточкой,
        // Б — 5 из 7 (mid)
        assertTrue(html.contains("inactive-card-full"), "fully inactive card must be highlighted, got: $html")
        assertTrue(html.contains("inactive-badge-high"), "7 of 7 zero months must be a high badge, got: $html")
        assertTrue(html.contains("inactive-badge-mid"), "5 of 7 zero months must be a mid badge, got: $html")
    }

    @Test
    @DisplayName("EXCLUDED_TESTERS: исключённые не попадают в сводку неактивных, но остаются в остальном отчёте")
    fun excludedTestersAreHiddenFromInactiveSummary() {
        val records = (1..7).map { m ->
            record("a@x.com", "2026-%02d-05T10:00:00+03:00".format(m), "monthly$m")
        } + listOf(
            record("b@x.com", "2026-01-15T10:00:00+03:00", "jan"),
            record("c@x.com", "2025-11-03T10:00:00+03:00", "old")
        )
        val ctx = loadReport(
            records = records,
            generatedAt = "2026-07-10T12:00:00+03:00",
            browserZone = "Europe/Moscow",
            authorNames = mapOf("c@x.com" to "Автор В"),
            // b исключён по e-mail, c — по отображаемому имени из authorNames
            excludedTesters = setOf("b@x.com", "Автор В")
        )

        ctx.clickPeriod("ytd")

        val inactive = ctx.innerHtml("inactiveList")
        assertFalse(inactive.contains("b@x.com"), "excluded by email must be hidden, got: $inactive")
        assertFalse(inactive.contains("Автор В"), "excluded by display name must be hidden, got: $inactive")
        // Оба неактивных исключены; активный каждый месяц a показан зелёной карточкой
        assertFalse(ctx.isHidden("inactiveList"))
        assertTrue(inactive.contains("a@x.com") && inactive.contains("без пропусков"), "got: $inactive")
        // На остальные секции исключение не влияет: запись b за период есть в общей сводке
        assertTrue(ctx.innerHtml("summaryBody").contains("b@x.com"))
    }

    @Test
    @DisplayName("EXCLUDED_TESTERS: исключение по одному e-mail скрывает человека со всеми его адресами")
    fun excludedTesterWithMultipleEmailsIsFullyHidden() {
        // У Ивана два адреса, объединённых через AUTHOR_NAMES; исключён только
        // старый. Записи с обоих адресов не должны ни вернуть его в ростер,
        // ни попасть в подсчёты карточек.
        val records = (1..7).map { m ->
            record("a@x.com", "2026-%02d-05T10:00:00+03:00".format(m), "monthly$m")
        } + listOf(
            record("old@x.com", "2026-01-15T10:00:00+03:00", "oldJan"),
            record("new@x.com", "2026-05-20T10:00:00+03:00", "newMay"),
            record("b@x.com", "2026-02-10T10:00:00+03:00", "febOnly")
        )
        val ctx = loadReport(
            records = records,
            generatedAt = "2026-07-10T12:00:00+03:00",
            browserZone = "Europe/Moscow",
            authorNames = mapOf("old@x.com" to "Иван", "new@x.com" to "Иван"),
            excludedTesters = setOf("old@x.com")
        )

        ctx.clickPeriod("ytd")

        val inactive = ctx.innerHtml("inactiveList")
        assertFalse(inactive.contains("Иван"), "person must be excluded with all his emails, got: $inactive")
        // Непричастный автор с тестом только в феврале остаётся в сводке
        assertTrue(
            inactive.contains("b@x.com") && inactive.contains("6 из 7"),
            "unrelated author must still be listed, got: $inactive"
        )
    }

    @Test
    @DisplayName("Пустые данные: отчёт не падает, показываются заглушки")
    fun emptyDataShowsPlaceholders() {
        val ctx = loadReport(
            records = emptyList(),
            generatedAt = "2026-07-10T12:00:00+03:00",
            browserZone = "Europe/Moscow"
        )

        ctx.clickPeriod("ytd")

        assertTrue(ctx.isHidden("summaryTable"))
        assertFalse(ctx.isHidden("noData"))
        assertTrue(ctx.isHidden("inactiveList"))
        assertFalse(ctx.isHidden("noInactiveData"))
        assertTrue(ctx.isHidden("forecastTable"))
        assertFalse(ctx.isHidden("noForecastData"))
        assertTrue(ctx.isHidden("batchingList"))
        assertFalse(ctx.isHidden("noBatchingData"))
    }

    @Test
    @DisplayName("Прогноз: YTD, тот же период прошлого года, дельта и экстраполяция")
    fun forecastComputesYtdYoYAndProjection() {
        val records = listOf(
            // a: 4 теста в этом году, 2 в окне прошлого года
            record("a@x.com", "2026-02-01T10:00:00+03:00", "aFeb"),
            record("a@x.com", "2026-03-01T10:00:00+03:00", "aMar"),
            record("a@x.com", "2026-04-01T10:00:00+03:00", "aApr"),
            record("a@x.com", "2026-05-01T10:00:00+03:00", "aMay"),
            record("a@x.com", "2025-03-10T10:00:00+03:00", "aPrevMar"),
            // Границы окна прошлого года (сам момент — 2025-07-10T12:00+03:00):
            // 11:00 входит, 13:00 — уже нет
            record("a@x.com", "2025-07-10T11:00:00+03:00", "aPrevBoundaryIn"),
            record("a@x.com", "2025-07-10T13:00:00+03:00", "aPrevBoundaryOut"),
            // old: только прошлый год; new: только этот год
            record("old@x.com", "2025-05-05T10:00:00+03:00", "oldPrev"),
            record("new@x.com", "2026-06-15T10:00:00+03:00", "newCur")
        )
        val ctx = loadReport(
            records = records,
            generatedAt = "2026-07-10T12:00:00+03:00",
            browserZone = "Europe/Moscow"
        )

        // Карточки: 5 YTD (4 у a + 1 у new), 3 за тот же период прошлого года
        // (2 у a + 1 у old), дельта +67%, прогноз round(5 / 190.5 * 365) = 10.
        assertEquals("5", ctx.textContent("fcYtd"))
        assertEquals("3", ctx.textContent("fcPrev"))
        assertTrue(ctx.innerHtml("fcDelta").contains("+67%"), "got: ${ctx.innerHtml("fcDelta")}")
        assertEquals("10", ctx.textContent("fcProjected"))
        assertEquals("Прогноз на 31 декабря", ctx.textContent("fcProjectedLabel"))

        // Таблица по авторам: сортировка по YTD, -100% у ушедшего, +∞ у новичка,
        // прогноз a = round(4 / 190.5 * 365) = 8
        val body = ctx.innerHtml("forecastBody")
        assertFalse(ctx.isHidden("forecastTable"))
        assertTrue(body.indexOf("a@x.com") < body.indexOf("new@x.com"), "got: $body")
        assertTrue(body.indexOf("new@x.com") < body.indexOf("old@x.com"), "got: $body")
        val rowA = body.substringAfter("a@x.com").substringBefore("</tr>")
        assertTrue(rowA.contains("<td>4</td><td>2</td>") && rowA.contains("+100%") && rowA.contains("<td>8</td>"),
            "got: $rowA")
        val rowOld = body.substringAfter("old@x.com").substringBefore("</tr>")
        assertTrue(rowOld.contains("<td>0</td><td>1</td>") && rowOld.contains("-100%"), "got: $rowOld")
        val rowNew = body.substringAfter("new@x.com").substringBefore("</tr>")
        assertTrue(rowNew.contains("+∞"), "got: $rowNew")
    }

    @Test
    @DisplayName("Прогноз: не зависит от фильтра периода, но уважает фильтр по системе")
    fun forecastIgnoresPeriodButRespectsSystemFilter() {
        val ctx = loadReport(
            records = listOf(
                record("a@x.com", "2026-02-01T10:00:00+03:00", "t1", system = "CI001"),
                record("a@x.com", "2026-03-01T10:00:00+03:00", "t2", system = "CI001"),
                record("a@x.com", "2026-04-01T10:00:00+03:00", "t3", system = "CI002")
            ),
            generatedAt = "2026-07-10T12:00:00+03:00",
            browserZone = "Europe/Moscow"
        )

        // init() рендерит с периодом «неделя» — прогноз всё равно за весь YTD
        assertEquals("3", ctx.textContent("fcYtd"))
        ctx.clickPeriod("month")
        assertEquals("3", ctx.textContent("fcYtd"), "forecast must not react to period filter")
        ctx.clickPeriod("ytd")
        assertEquals("3", ctx.textContent("fcYtd"))

        ctx.selectSystem("CI001")
        assertEquals("2", ctx.textContent("fcYtd"), "forecast must respect the system filter")
        ctx.selectSystem("all")
        assertEquals("3", ctx.textContent("fcYtd"))
    }

    @Test
    @DisplayName("Прогноз: в первые дни января экстраполяция скрывается как ненадёжная")
    fun forecastSuppressesProjectionInEarlyJanuary() {
        val ctx = loadReport(
            records = listOf(
                record("a@x.com", "2026-01-02T10:00:00+03:00", "jan2"),
                record("a@x.com", "2026-01-03T10:00:00+03:00", "jan3")
            ),
            generatedAt = "2026-01-05T12:00:00+03:00",
            browserZone = "Europe/Moscow"
        )

        // 4.5 прошедших дня < 14 — прогноз «—», метка с пояснением
        assertEquals("2", ctx.textContent("fcYtd"))
        assertEquals("—", ctx.textContent("fcProjected"))
        assertTrue(ctx.textContent("fcProjectedLabel").contains("мало данных"))
        // Рост с нуля относительно прошлого года
        assertTrue(ctx.innerHtml("fcDelta").contains("+∞"))
    }

    @Test
    @DisplayName("Батчинг: классификация по доле топ-3 дней, сортировка и EXCLUDED_TESTERS")
    fun batchingClassifiesDeliveryPatterns() {
        val records =
            // spike: 10 тестов в один день
            (1..10).map { record("spike@x.com", "2026-03-05T10:%02d:00+03:00".format(it), "spike$it") } +
            // even: 10 тестов по одному в день
            (1..10).map { record("even@x.com", "2026-05-%02dT10:00:00+03:00".format(it), "even$it") } +
            // wave: дни [3,1,1,1,1,1] → топ-3 = 5/8 = 62.5%
            listOf(
                record("wave@x.com", "2026-04-01T10:00:00+03:00", "wave1"),
                record("wave@x.com", "2026-04-01T11:00:00+03:00", "wave2"),
                record("wave@x.com", "2026-04-01T12:00:00+03:00", "wave3"),
                record("wave@x.com", "2026-04-05T10:00:00+03:00", "wave4"),
                record("wave@x.com", "2026-04-08T10:00:00+03:00", "wave5"),
                record("wave@x.com", "2026-04-12T10:00:00+03:00", "wave6"),
                record("wave@x.com", "2026-04-15T10:00:00+03:00", "wave7"),
                record("wave@x.com", "2026-04-20T10:00:00+03:00", "wave8"),
                // tiny: меньше 5 тестов — «мало данных»
                record("tiny@x.com", "2026-02-01T10:00:00+03:00", "tiny1"),
                record("tiny@x.com", "2026-02-15T10:00:00+03:00", "tiny2"),
                // excluded: скрыт из батчинга, но виден в прогнозе
                record("ex@x.com", "2026-06-01T10:00:00+03:00", "ex1"),
                record("ex@x.com", "2026-06-01T11:00:00+03:00", "ex2"),
                record("ex@x.com", "2026-06-01T12:00:00+03:00", "ex3"),
                record("ex@x.com", "2026-06-01T13:00:00+03:00", "ex4"),
                record("ex@x.com", "2026-06-01T14:00:00+03:00", "ex5")
            )
        val ctx = loadReport(
            records = records,
            generatedAt = "2026-07-10T12:00:00+03:00",
            browserZone = "Europe/Moscow",
            excludedTesters = setOf("ex@x.com")
        )

        ctx.clickPeriod("ytd")
        val html = ctx.innerHtml("batchingList")
        assertFalse(ctx.isHidden("batchingList"))

        val cardSpike = html.substringAfter("spike@x.com").substringBefore("batch-card")
        assertTrue(cardSpike.contains("редкие крупные пачки"), "got: $cardSpike")
        assertTrue(cardSpike.contains("100%"), "top-3 share of a one-day author must be 100%, got: $cardSpike")
        val cardWave = html.substringAfter("wave@x.com").substringBefore("batch-card")
        assertTrue(cardWave.contains("волнами"), "got: $cardWave")
        assertTrue(cardWave.contains("63%"), "top-3 share 5/8 must round to 63%, got: $cardWave")
        val cardEven = html.substringAfter("even@x.com").substringBefore("batch-card")
        assertTrue(cardEven.contains("равномерно"), "got: $cardEven")
        val cardTiny = html.substringAfter("tiny@x.com")
        assertTrue(cardTiny.contains("мало данных"), "got: $cardTiny")

        // Сортировка: пачечники сверху по доле топ-3, «мало данных» в конце
        assertTrue(
            html.indexOf("spike@x.com") < html.indexOf("wave@x.com") &&
                html.indexOf("wave@x.com") < html.indexOf("even@x.com") &&
                html.indexOf("even@x.com") < html.indexOf("tiny@x.com"),
            "cards must be sorted spike > wave > even, no-data last, got: $html"
        )

        // Спарклайн: недельные бары с провалами (YTD ≈ 27 недель < 60)
        assertTrue(html.contains("batch-spark") && html.contains("batch-bar"), "got: $html")
        assertTrue(html.contains("batch-bar-zero"), "gaps must be visible as zero bars, got: $html")

        // Исключённый скрыт из батчинга, но остаётся в таблице прогноза
        assertFalse(html.contains("ex@x.com"), "excluded tester must be hidden from batching, got: $html")
        assertTrue(ctx.innerHtml("forecastBody").contains("ex@x.com"),
            "forecast deliberately includes excluded testers")
    }

    @Test
    @DisplayName("Батчинг: уважает фильтры периода и системы")
    fun batchingRespectsPeriodAndSystemFilters() {
        val records =
            // 6 тестов одним днём в марте (CI001) + 7 тестов по одному в день в июне (CI002)
            (1..6).map { record("a@x.com", "2026-03-05T10:0$it:00+03:00", "mar$it", system = "CI001") } +
            (1..7).map { record("a@x.com", "2026-06-%02dT10:00:00+03:00".format(it), "jun$it", system = "CI002") }
        val ctx = loadReport(
            records = records,
            generatedAt = "2026-07-10T12:00:00+03:00",
            browserZone = "Europe/Moscow"
        )

        // Весь YTD: дни [6,1,1,1,1,1,1,1] → топ-3 = 8/13 ≈ 0.62 → «волнами»
        ctx.clickPeriod("ytd")
        assertTrue(ctx.innerHtml("batchingList").contains("волнами"),
            "got: ${ctx.innerHtml("batchingList")}")

        // Только март: 6 тестов одним днём → «редкие крупные пачки»
        ctx.js("document.getElementById('customMonth').value = '3';")
        ctx.js("document.getElementById('customYear').value = '2026';")
        ctx.clickPeriod("custom")
        ctx.js("document.getElementById('applyCustom').click();")
        assertTrue(ctx.innerHtml("batchingList").contains("редкие крупные пачки"),
            "period filter must narrow batching to March, got: ${ctx.innerHtml("batchingList")}")

        // YTD + фильтр по системе CI002: 7 дней по одному тесту → «равномерно»
        ctx.clickPeriod("ytd")
        ctx.selectSystem("CI002")
        assertTrue(ctx.innerHtml("batchingList").contains("равномерно"),
            "system filter must leave only the even June deliveries, got: ${ctx.innerHtml("batchingList")}")
    }

    @Test
    @DisplayName("Будущий месяц: сводка неактивных не показывает несуществующие месяцы")
    fun futureMonthShowsNoInactiveData() {
        val ctx = loadReport(
            records = listOf(record("a@x.com", "2026-06-15T12:00:00+03:00", "juneTest")),
            generatedAt = "2026-07-10T12:00:00+03:00",
            browserZone = "Europe/Moscow"
        )

        ctx.js("document.getElementById('customMonth').value = '12';")
        ctx.js("document.getElementById('customYear').value = '2026';")
        ctx.clickPeriod("custom")
        ctx.js("document.getElementById('applyCustom').click();")

        assertTrue(ctx.isHidden("inactiveList"), "December 2026 has not started yet")
        assertFalse(ctx.isHidden("noInactiveData"))
    }

    companion object {
        /**
         * Минимальный DOM-стаб: элементы по id создаются лениво, состояние
         * is-hidden отражается в поле hidden, клики по кнопкам периодов
         * доступны через __clickPeriod(period).
         */
        private val DOM_STUB = """
            const __els = {};
            function __makeEl(id) {
                // systemFilter — select, в браузере populateSystemFilter даёт ему
                // первую опцию 'all'; остальным стабам достаточно числового value.
                const el = {
                    id: id, value: id === 'systemFilter' ? 'all' : '1',
                    innerHTML: '', textContent: '',
                    dataset: {}, hidden: false, listeners: {}
                };
                el.classList = {
                    toggle: function(cls, on) { if (cls === 'is-hidden') el.hidden = !!on; },
                    add: function() {},
                    remove: function() {}
                };
                el.addEventListener = function(type, fn) { el.listeners[type] = fn; };
                el.click = function() { if (el.listeners.click) el.listeners.click(); };
                el.getContext = function() { return {}; };
                return el;
            }
            const __periodButtons = ['week', 'month', 'quarter', 'year', 'ytd', 'custom', 'all'].map(p => {
                const el = __makeEl('period-btn-' + p);
                el.dataset.period = p;
                return el;
            });
            const document = {
                getElementById: function(id) {
                    if (!__els[id]) __els[id] = __makeEl(id);
                    return __els[id];
                },
                querySelectorAll: function(selector) {
                    return selector === '.period-btn' ? __periodButtons : [];
                }
            };
            const window = globalThis;
            function __clickPeriod(period) {
                __periodButtons.find(b => b.dataset.period === period).click();
            }
        """.trimIndent()
    }
}
