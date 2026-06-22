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
    @DisplayName("generate produces index.html, assets and report-data.js with valid JSON payload")
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

        val indexHtml = File(outputDir, "index.html")
        assertTrue(indexHtml.isFile, "index.html should be written")

        val reportData = File(outputDir, "assets/report-data.js")
        assertTrue(reportData.isFile, "assets/report-data.js should be written")

        val content = reportData.readText(Charsets.UTF_8)
        assertTrue(content.startsWith("window.REPORT_DATA = "), "data file should start with window.REPORT_DATA assignment")
        assertTrue(content.contains("\"author\":\"a@x.com\""))
        assertTrue(content.contains("\"system\":\"CI001\""))
        assertTrue(content.contains("\"system\":null"), "null systemId must appear as null")
        assertTrue(content.contains("\"systemNames\":{\"CI001\":\"Платежи\"}"))
        assertTrue(content.contains("\"authorNames\":{\"a@x.com\":\"Иван\"}"))

        val css = File(outputDir, "assets/report.css")
        assertTrue(css.isFile, "assets/report.css should be written")

        val js = File(outputDir, "assets/report.js")
        assertTrue(js.isFile, "assets/report.js should be written")

        val chartJs = File(outputDir, "assets/chart.umd.js")
        assertTrue(chartJs.isFile && chartJs.length() > 0, "assets/chart.umd.js should be copied and non-empty")
    }

    @Test
    @DisplayName("generate with empty records produces valid empty report")
    fun generatesEmptyReport() {
        val outputDir = tempDir.resolve("empty-report").toString()

        generator.generate(
            records = emptyList(),
            repoPath = "/repo",
            outputDir = outputDir
        )

        val reportData = File(outputDir, "assets/report-data.js").readText(Charsets.UTF_8)
        assertTrue(reportData.contains("\"records\":[]"))
        assertTrue(reportData.contains("\"systemNames\":{}"))
        assertTrue(reportData.contains("\"authorNames\":{}"))
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
    @DisplayName("generate accepts custom zoneId and stamps it into report-data.js")
    fun stampsCustomZoneId() {
        val outputDir = tempDir.resolve("zoned-report").toString()

        generator.generate(
            records = emptyList(),
            repoPath = "/repo",
            outputDir = outputDir,
            zoneId = ZoneId.of("Europe/Moscow")
        )

        val content = File(outputDir, "assets/report-data.js").readText(Charsets.UTF_8)
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

        val js = File(outputDir, "assets/report.js").readText(Charsets.UTF_8)
        // IIFE opening
        assertTrue(js.trim().startsWith("(function() {"), "JS should start with IIFE, got: ${js.take(50)}")
        // Inner setup init still wrapped
        assertTrue(js.contains("(function init() {"), "init IIFE preserved")
        // Balanced outer IIFE close
        assertTrue(js.trim().endsWith("})();"), "JS should end with IIFE close, got: ${js.takeLast(50)}")
        // use strict present
        assertTrue(js.contains("'use strict';"))
    }
}