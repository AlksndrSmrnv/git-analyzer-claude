package com.analyzer

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
                threadCount = 1
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
                threadCount = 1
            )
        }

        assertTrue(out.contains("No commits found."))
        val report = File(outputDir, "report.html")
        assertTrue(report.isFile, "report.html should be generated for an empty repository")
        assertTrue(report.readText(Charsets.UTF_8).contains("\"records\":[]"))
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
                @@ -0,0 +1,4 @@
                +@Test
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
                threadCount = 1
            )
        }

        val report = File(outputDir, "report.html")
        assertTrue(report.isFile, "report.html should be generated")
        assertTrue(report.readText(Charsets.UTF_8).contains("\"test\":\"shouldGenerateHtml\""))
        assertTrue(out.contains("HTML report generated:"), "diagnostic logging should remain")
        assertFalse(out.contains("Git Test Analyzer Report"), "legacy console report must not be printed")
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
