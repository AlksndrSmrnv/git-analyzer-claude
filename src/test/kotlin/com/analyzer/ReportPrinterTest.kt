package com.analyzer

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class ReportPrinterTest {

    private val printer = ReportPrinter()

    @Test
    @DisplayName("Empty tests produces 'No new tests found' message")
    fun emptyReport() {
        val out = capture {
            printer.printReport(
                testsByAuthor = emptyMap(),
                period = "All time",
                repoPath = "/repo"
            )
        }
        assertTrue(out.contains("No new tests found for the specified period."))
    }

    @Test
    @DisplayName("authorNames resolves email to 'Name (email)' in summary and details")
    fun authorNamesResolved() {
        val tests = listOf(
            NewTestInfo("shouldWork", "src/test/MyTest.kt", "CI001")
        )
        val byAuthor = linkedMapOf("ivanov@company.com" to tests)

        val out = capture {
            printer.printReport(
                testsByAuthor = byAuthor,
                period = "Last 7 days",
                repoPath = "/repo",
                authorNames = mapOf("ivanov@company.com" to "Иванов Иван")
            )
        }
        assertTrue(out.contains("Иванов Иван (ivanov@company.com)"),
            "expected resolved label in output: $out")
        // Динамическая ширина колонки допускает пробелы между label и count.
        assertTrue(Regex("TOTAL\\s+1").containsMatchIn(out),
            "expected 'TOTAL <count>' row, got: $out")
    }

    @Test
    @DisplayName("systemNames adds system label in details list")
    fun systemNamesInDetails() {
        val tests = listOf(
            NewTestInfo("shouldWork", "src/test/MyTest.kt", "CI001")
        )
        val byAuthor = linkedMapOf("qa@x.com" to tests)

        val out = capture {
            printer.printReport(
                testsByAuthor = byAuthor,
                period = "All time",
                repoPath = "/repo",
                systemNames = mapOf("CI001" to "Платежи")
            )
        }
        assertTrue(out.contains("[Платежи (CI001)]"), "expected system label in details: $out")
    }

    @Test
    @DisplayName("Long email broader than fixed 40 width does not break table alignment")
    fun longEmailDoesNotBreakLayout() {
        val tests = listOf(
            NewTestInfo("t1", "f.kt", null),
            NewTestInfo("t2", "f.kt", null)
        )
        val longEmail = "very.long.email.address.that.exceeds.fixed.width@company.com"
        val byAuthor = linkedMapOf(longEmail to tests)

        val out = capture {
            printer.printReport(byAuthor, "All time", "/repo")
        }
        // Сводная строка содержит email и tests.size, и TOTAL корректно.
        // Между label и count может быть несколько пробелов (динамическая ширина),
        // поэтому проверяем через regex.
        assertTrue(Regex(Regex.escape(longEmail) + "\\s+2").containsMatchIn(out),
            "expected '$longEmail <count>=2' row, got: $out")
        assertTrue(Regex("TOTAL\\s+2").containsMatchIn(out),
            "expected 'TOTAL 2' row, got: $out")
    }

    @Test
    @DisplayName("Test date is shown in details when present")
    fun dateShownInDetails() {
        val tests = listOf(
            NewTestInfo("shouldWork", "f.kt", null, "2026-04-01T10:00:00+03:00")
        )
        val byAuthor = linkedMapOf("qa@x.com" to tests)

        val out = capture {
            printer.printReport(byAuthor, "All time", "/repo")
        }
        assertTrue(out.contains("@ 2026-04-01T10:00:00+03:00"), "expected date in details: $out")
    }

    @Test
    @DisplayName("Multiple authors sorted by tests count descending")
    fun sortedByCountDescending() {
        val byAuthor = linkedMapOf(
            "a@x.com" to listOf(NewTestInfo("t1", "f.kt", null)),
            "b@x.com" to listOf(
                NewTestInfo("t2", "f.kt", null),
                NewTestInfo("t3", "f.kt", null)
            )
        )
        val out = capture {
            printer.printReport(byAuthor, "All time", "/repo")
        }
        val aIdx = out.indexOf("a@x.com")
        val bIdx = out.indexOf("b@x.com")
        assertTrue(bIdx < aIdx, "b (2 tests) listed before a (1 test): a=$aIdx b=$bIdx, got=$out")
    }

    private fun capture(block: () -> Unit): String {
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
}
