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
        assertTrue(
            content.trim().contains("(function() {") && content.contains("'use strict';"),
            "report.js IIFE should be inlined"
        )
        assertTrue(content.contains("(function init() {"), "init IIFE preserved")

        // Chart.js встроен: проверяем по сигнатуре лицензионного заголовка библиотеки
        assertTrue(
            content.contains("Chart.js") || content.contains("chart.js"),
            "Chart.js source should be inlined"
        )
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
    @DisplayName("report.html is self-contained: no external src/href references")
    fun reportIsSelfContained() {
        val outputDir = tempDir.resolve("self-contained").toString()
        generator.generate(
            records = emptyList(),
            repoPath = "/repo",
            outputDir = outputDir
        )

        val content = File(outputDir, "report.html").readText(Charsets.UTF_8)
        assertFalse(content.contains("src=\"assets/"), "should not reference external assets/ scripts")
        assertFalse(content.contains("href=\"assets/"), "should not reference external assets/ styles")
    }
}