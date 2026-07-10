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
        // b в нём активен, a — нет.
        val inactive = ctx.innerHtml("inactiveList")
        assertTrue(inactive.contains("a@x.com") && inactive.contains("1 из 1"), "got: $inactive")
        assertFalse(inactive.contains("b@x.com"), "got: $inactive")
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
        assertTrue(
            ctx.isHidden("inactiveList"),
            "author active in April must not be listed as inactive, got: ${ctx.innerHtml("inactiveList")}"
        )
        assertFalse(ctx.isHidden("noInactiveData"))
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

        assertFalse(html.contains("Автор А"), "author with tests every month must be absent")
        assertTrue(html.contains("Автор Б") && html.contains("5 из 7"), "got: $html")
        assertTrue(html.contains("Автор В") && html.contains("7 из 7"), "got: $html")
        assertTrue(
            html.indexOf("Автор В") < html.indexOf("Автор Б"),
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
        // a активен каждый месяц, оба неактивных исключены — секция показывает заглушку
        assertTrue(ctx.isHidden("inactiveList"))
        assertFalse(ctx.isHidden("noInactiveData"))
        // На остальные секции исключение не влияет: запись b за период есть в общей сводке
        assertTrue(ctx.innerHtml("summaryBody").contains("b@x.com"))
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
