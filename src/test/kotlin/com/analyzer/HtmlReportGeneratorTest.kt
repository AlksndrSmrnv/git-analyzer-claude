package com.analyzer

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.time.ZoneId

class HtmlReportGeneratorTest {

    @TempDir
    lateinit var tempDir: Path

    private val generator = HtmlReportGenerator()

    @Test
    @DisplayName("generate produces a single report.html with inlined CSS, JS, data and Chart.js")
    fun generatesFullReport() {
        val records = listOf(
            TestRecord("a@x.com", "shouldWork", "src/test/kotlin/MyTest.kt", "2026-04-01T10:00:00+03:00", "CI001"),
            TestRecord("b@x.com", "shouldFail", "src/test/kotlin/OtherTest.kt", "2026-04-02T10:00:00+03:00", null)
        )
        val outputDir = tempDir.resolve("report").toString()

        generator.generate(
            records = records,
            repoPath = "/some/repo",
            outputDir = outputDir,
            systemNames = mapOf("CI001" to "Платежи"),
            authorNames = mapOf("a@x.com" to "Иван")
        )

        val reportHtml = File(outputDir, "report.html")
        assertTrue(reportHtml.isFile, "report.html should be written")

        // Старая структура assets/ больше не должна существовать
        assertFalse(File(outputDir, "assets").isDirectory, "assets/ directory should not be created")
        assertFalse(File(outputDir, "index.html").isFile, "index.html should not be written")

        val content = reportHtml.readText(Charsets.UTF_8)

        // CSS встроен inline
        assertTrue(content.contains("<style>"), "CSS should be inlined into <style>")

        // Данные встроены в inline <script>
        assertTrue(content.contains("window.REPORT_DATA = "), "data should be inlined into <script>")
        assertTrue(content.contains("\"author\":\"a@x.com\""))
        assertTrue(content.contains("\"system\":\"CI001\""))
        assertTrue(content.contains("\"system\":null"), "null systemId must appear as null")
        assertTrue(content.contains("\"systemNames\":{\"CI001\":\"Платежи\"}"))
        assertTrue(content.contains("\"authorNames\":{\"a@x.com\":\"Иван\"}"))

        // report.js (IIFE) встроен
        assertTrue(content.contains("(function() {") && content.contains("'use strict';"),
            "report.js IIFE should be inlined")
        assertTrue(content.contains("(function init() {"), "init IIFE preserved")

        // Chart.js встроен полноценным телом, а не только лицензионной шапкой:
        // Chart.js v4.4.0 UMD-бандл минифицирован, поэтому Chart.register/Chart.prototype
        // в нём не встречаются. Берём несколько характерных рантайм-маркеров, которые
        // присутствуют именно в теле библиотеки (по одному вхождению в chart.umd.js) и
        // не типичны для report.js/HTML-разметки. Уйти должна проверка ровно по маркеру.
        assertTrue(
            content.contains("prototype.draw") ||
                content.contains("prototype.parse") ||
                content.contains("prototype.generateTickLabels") ||
                content.contains("toFontString"),
            "Chart.js runtime body should be inlined (got only header marker otherwise)"
        )
        // Размер встроенного Chart.js блока — не менее ~150 КБ (всё тело библиотеки).
        // Ловит случай, когда случайно вставили только шапку/оборванную копию.
        assertTrue(content.length > 150_000, "report.html should embed full Chart.js bundle (~200KB)")
    }

    @Test
    @DisplayName("generate with empty records produces a single valid empty report.html")
    fun generatesEmptyReport() {
        val outputDir = tempDir.resolve("empty-report").toString()

        generator.generate(
            records = emptyList(),
            repoPath = "/repo",
            outputDir = outputDir
        )

        val reportHtml = File(outputDir, "report.html")
        assertTrue(reportHtml.isFile, "report.html should be written")

        val content = reportHtml.readText(Charsets.UTF_8)
        assertTrue(content.contains("\"records\":[]"))
        assertTrue(content.contains("\"systemNames\":{}"))
        assertTrue(content.contains("\"authorNames\":{}"))
    }

    @Test
    @DisplayName("generate fails when output path is a file, not a directory")
    fun failsWhenOutputIsFile() {
        val file = tempDir.resolve("not-a-dir.txt").toFile()
        file.writeText("hello")

        assertThrows(IllegalStateException::class.java) {
            generator.generate(
                records = emptyList(),
                repoPath = "/repo",
                outputDir = file.absolutePath
            )
        }
    }

    @Test
    @DisplayName("generate accepts custom zoneId and stamps it into inlined report data")
    fun stampsCustomZoneId() {
        val outputDir = tempDir.resolve("zoned-report").toString()

        generator.generate(
            records = emptyList(),
            repoPath = "/repo",
            outputDir = outputDir,
            zoneId = ZoneId.of("Europe/Moscow")
        )

        val content = File(outputDir, "report.html").readText(Charsets.UTF_8)
        // ISO_OFFSET_DATE_TIME for Europe/Moscow ends with offset like +03:00 or +02:00 (DST).
        // Проверяем наличие смещения, а не конкретного значения, чтобы тест был детерминирован
        // в любой момент года.
        assertTrue(Regex(""""generatedAt":"[^"]+[+-]\d{2}:\d{2}"""").containsMatchIn(content),
            "generatedAt должен содержать offset, got: $content")
    }

    @Test
    @DisplayName("report.js is wrapped in IIFE to avoid leaking globals")
    fun reportJsIsIifeWrapped() {
        val outputDir = tempDir.resolve("js-report").toString()
        generator.generate(
            records = emptyList(),
            repoPath = "/repo",
            outputDir = outputDir
        )

        val content = File(outputDir, "report.html").readText(Charsets.UTF_8)
        // IIFE opening
        assertTrue(content.contains("(function() {"), "JS IIFE opening should be present")
        // Inner setup init still wrapped
        assertTrue(content.contains("(function init() {"), "init IIFE preserved")
        // Balanced outer IIFE close
        assertTrue(content.contains("})();"), "JS IIFE close should be present")
        // use strict present
        assertTrue(content.contains("'use strict';"))
    }

    @Test
    @DisplayName("report.html is self-contained: no external scripts or styles")
    fun reportIsSelfContained() {
        val outputDir = tempDir.resolve("self-contained").toString()
        generator.generate(
            records = emptyList(),
            repoPath = "/repo",
            outputDir = outputDir
        )

        val content = File(outputDir, "report.html").readText(Charsets.UTF_8)

        // Старая схема assets/
        assertFalse(content.contains("src=\"assets/"), "should not reference external assets/ scripts")
        assertFalse(content.contains("href=\"assets/"), "should not reference external assets/ styles")

        // Любые удалённые источники — случайно возвращённый CDN/https тоже вредят
        // автономности отчёта. Ловим любые абсолютные URL в src/href.
        assertFalse(
            Regex("""(?:src|href)\s*=\s*"(?:https?:|//)""").containsMatchIn(content),
            "should not reference remote (http/https/protocol-relative) URLs in src/href"
        )

        // Инлайн-<style> и инлайн-<script> не должны иметь атрибутов src/href
        // (то есть это всегда именно инлайн, а не внешние ресурсы).
        assertFalse(
            Regex("""<script\s+[^>]*\bsrc\b""").containsMatchIn(content),
            "<script> tags must not use src attribute (inline only)"
        )
        assertFalse(
            Regex("""<link\s+[^>]*\bhref\b""").containsMatchIn(content),
            "<link> must not be used with external href"
        )
    }

    @Test
    @DisplayName("</script> in test names and file paths is escaped and cannot break HTML parsing")
    fun escapesClosingScriptTagInData() {
        // Регрессионный тест на главную фичу escapeScriptData(): литерал </script>
        // в данных не должен попадать в HTML как есть, иначе HTML-парсер закроет
        // <script> преждевременно и отчёт не загрузится.
        val malicious = "</script><script>alert(1)</script>"
        val records = listOf(
            TestRecord(
                "a@x.com",
                "shouldWork$malicious",
                "src/test/kotlin/Evil</script>Test.kt",
                "2026-04-01T10:00:00+03:00",
                "CI001"
            )
        )
        val outputDir = tempDir.resolve("escape-report").toString()

        generator.generate(
            records = records,
            repoPath = "/repo",
            outputDir = outputDir
        )

        val content = File(outputDir, "report.html").readText(Charsets.UTF_8)

        // Буквального </script внутри блока с данными быть не должно — экранирование
        // должно превратить < в \u003c. Проверяем весь файл: инлайновые Chart.js
        // и report.js "</script>" заведомо не содержат, поэтому любое вхождение
        // означало бы провал экранирования данных.
        assertFalse(
            content.contains("</script><script>alert(1)</script>"),
            "raw </script> payload must not appear verbatim in report.html"
        )

        // Экранированная форма должна присутствовать в данных
        assertTrue(
            content.contains("\\u003c/script"),
            "data payload must escape </script via \\u003c, got: $content"
        )

        // Файл по-прежнему корректно закрывается: ровно один</body> и один </html>
        assertEquals(
            1, content.split("</body>").size - 1,
            "exactly one </body> expected — HTML not prematurely closed by payload"
        )
        assertEquals(
            1, content.split("</html>").size - 1,
            "exactly one </html> expected — HTML not prematurely closed by payload"
        )
    }

    @Test
    @DisplayName("generate removes legacy index.html and assets/ when regenerating into existing dir")
    fun removesLegacyArtifactsOnRegeneration() {
        val outputDir = tempDir.resolve("legacy-report").toFile()
        outputDir.mkdirs()

        // Имитируем_old-style артефакты от предыдущей версии
        File(outputDir, "index.html").writeText("old index", Charsets.UTF_8)
        val assets = File(outputDir, "assets").apply { mkdirs() }
        File(assets, "report.css").writeText("old css", Charsets.UTF_8)
        File(assets, "chart.umd.js").writeText("old chart", Charsets.UTF_8)

        // Также положим сторонний файл — он не должен удаляться
        val userFile = File(outputDir, "notes.txt").apply { writeText("keep", Charsets.UTF_8) }

        generator.generate(
            records = emptyList(),
            repoPath = "/repo",
            outputDir = outputDir.absolutePath
        )

        assertFalse(File(outputDir, "index.html").isFile, "legacy index.html should be removed")
        assertFalse(File(outputDir, "assets").isDirectory, "legacy assets/ directory should be removed")
        assertTrue(File(outputDir, "report.html").isFile, "report.html should be written")
        assertTrue(userFile.isFile, "unrelated user files must be preserved")
    }
}
