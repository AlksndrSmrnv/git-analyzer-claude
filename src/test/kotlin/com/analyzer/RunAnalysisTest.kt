package com.analyzer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Path

class RunAnalysisTest {

    @Test
    @DisplayName("runAnalysis prints error and returns early for invalid repository")
    fun invalidRepo(@TempDir tmp: Path) {
        val git = FakeGitRepository().setValid(false)
        val outputDir = tmp.resolve("report").toString()
        val err = catchSystemErr {
            runAnalysis(
                gitClient = git,
                repoPath = "/no/such/repo",
                outputDir = outputDir,
                threadCount = 1,
                systemNames = emptyMap(),
                authorNames = emptyMap(),
                excludedTesters = emptySet()
            )
        }
        assertTrue(err.contains("not a valid git repository"))
        assertFalse(File(outputDir, "report.html").exists())
    }

    @Test
    @DisplayName("runAnalysis generates an empty HTML report when no commits are found")
    fun emptyCommits(@TempDir tmp: Path) {
        val git = FakeGitRepository().setValid(true)
        val outputDir = tmp.resolve("report").toString()
        val out = catchSystemOut {
            runAnalysis(
                gitClient = git,
                repoPath = tmp.toString(),
                outputDir = outputDir,
                threadCount = 1,
                systemNames = emptyMap(),
                authorNames = emptyMap(),
                excludedTesters = emptySet()
            )
        }

        assertTrue(out.contains("No commits found."))
        val report = File(outputDir, "report.html")
        assertTrue(report.isFile, "report.html should be generated for an empty repository")
        assertTrue(readReportData(report).getValue("records").jsonArray.isEmpty())
    }

    @Test
    @DisplayName("runAnalysis generates HTML without printing the legacy console report")
    fun generatesOnlyHtmlReport(@TempDir tmp: Path) {
        val git = FakeGitRepository().addCommit(
            hash = "abc123",
            authorEmail = "qa@example.com",
            date = "2026-07-01T10:00:00+03:00",
            isRoot = true,
            diff = """
                +++ b/src/test/kotlin/SampleTest.kt
                @@ -0,0 +1,5 @@
                +@Test
                +@System("CI001")
                +fun shouldGenerateHtml() {
                +    assertTrue(true)
                +}
            """.trimIndent()
        )
        val outputDir = tmp.resolve("report").toString()

        val out = catchSystemOut {
            runAnalysis(
                gitClient = git,
                repoPath = tmp.toString(),
                outputDir = outputDir,
                threadCount = 1,
                systemNames = mapOf("CI001" to "Платежи"),
                authorNames = mapOf("qa@example.com" to "QA Engineer"),
                excludedTesters = setOf("former@example.com")
            )
        }

        val report = File(outputDir, "report.html")
        assertTrue(report.isFile, "report.html should be generated")
        val reportData = readReportData(report)
        val record = reportData.getValue("records").jsonArray.single().jsonObject
        assertEquals("shouldGenerateHtml", record.getValue("test").jsonPrimitive.content)
        assertEquals("CI001", record.getValue("system").jsonPrimitive.content)
        assertEquals(
            "Платежи",
            reportData.getValue("systemNames").jsonObject.getValue("CI001").jsonPrimitive.content
        )
        assertEquals(
            "QA Engineer",
            reportData.getValue("authorNames").jsonObject.getValue("qa@example.com").jsonPrimitive.content
        )
        assertEquals(
            listOf("former@example.com"),
            reportData.getValue("excludedTesters").jsonArray.map { it.jsonPrimitive.content }
        )
        assertEquals(
            listOf(
                "[INFO] Found 1 commits to analyze (threads: 1)...",
                "[INFO]   Processing commit 1/1...",
                "[INFO] HTML report generated: $outputDir/report.html"
            ),
            out.lineSequence().filter(String::isNotBlank).toList()
        )
    }

    private fun readReportData(report: File): JsonObject {
        val html = report.readText(Charsets.UTF_8)
        val marker = "window.REPORT_DATA = "
        val jsonStart = html.indexOf(marker)
        assertTrue(jsonStart >= 0, "REPORT_DATA assignment should be present")
        val valueStart = jsonStart + marker.length
        val scriptEnd = html.indexOf("</script>", valueStart)
        assertTrue(scriptEnd >= 0, "REPORT_DATA script should be closed")
        val json = html.substring(valueStart, scriptEnd).trim().removeSuffix(";")
        return Json.parseToJsonElement(json).jsonObject
    }

    private inline fun catchSystemOut(block: () -> Unit): String {
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer, true, Charsets.UTF_8))
        try {
            block()
        } finally {
            System.setOut(original)
        }
        return buffer.toString(Charsets.UTF_8)
    }

    private inline fun catchSystemErr(block: () -> Unit): String {
        val original = System.err
        val buffer = ByteArrayOutputStream()
        System.setErr(PrintStream(buffer, true, Charsets.UTF_8))
        try {
            block()
        } finally {
            System.setErr(original)
        }
        return buffer.toString(Charsets.UTF_8)
    }
}
