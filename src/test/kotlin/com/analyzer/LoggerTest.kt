package com.analyzer

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class LoggerTest {

    @org.junit.jupiter.api.BeforeEach
    fun resetState() {
        Logger.resetForTests()
    }

    @Test
    @DisplayName("warnGitErrorOnce prints the first occurrence of each distinct message")
    fun printsFirstOccurrenceOfEachDistinctMessage() {
        val err = captureErr {
            Logger.warnGitErrorOnce("fatal: not a git repository")
            Logger.warnGitErrorOnce("fatal: bad object ref")
            Logger.warnGitErrorOnce("fatal: bad object ref")  // suppress
        }
        assertTrue(err.contains("[WARN] (git) fatal: not a git repository"))
        assertTrue(err.contains("[WARN] (git) fatal: bad object ref"),
            "second distinct message must also be printed: $err")
        // Flush суммарно подаёт только счётчик для повторов
        val summary = captureErr { Logger.flushSummary() }
        assertTrue(summary.contains("[fatal: bad object ref] — ещё 1 повтор подавлено"),
            "expected summary '(1 повтор)' for repeated 'fatal: bad object ref', got: $summary")
    }

    @Test
    @DisplayName("warnGitErrorOnce suppresses only exact duplicates")
    fun suppressesExactDuplicates() {
        val err = captureErr {
            repeat(5) { Logger.warnGitErrorOnce("locked") }
            Logger.warnGitErrorOnce("different")
            Logger.warnGitErrorOnce("different")
        }
        val lockedCount = err.split("locked").size - 1
        val differentCount = err.split("different").size - 1
        assertEquals(1, lockedCount, "первое появление 'locked' печатается один раз: $err")
        assertEquals(1, differentCount, "первое появление 'different' печатается один раз")
    }

    @Test
    @DisplayName("flushSummary omits the summary line when there were no duplicates")
    fun flushSummarySilentWhenNoDuplicates() {
        // Single unique message: первая строка уже напечатана в warnGitErrorOnce,
        // в flushSummary счётчик == 1 → нет повтора → нет summary.
        val err = captureErr {
            Logger.warnGitErrorOnce("only once")
            Logger.flushSummary()
        }
        assertTrue(err.contains("[WARN] (git) only once"))
        assertFalse(err.contains("повтор подавлено"),
            "однократное сообщение не должно считаться повтором: $err")
    }

    @Test
    @DisplayName("flushSummary uses plural 'повторов' when repeats > 1")
    fun flushSummaryPluralForm() {
        val err = captureErr {
            repeat(4) { Logger.warnGitErrorOnce("dup") }  // 3 повтора
            Logger.flushSummary()
        }
        assertTrue(err.contains("ещё 3 повторов подавлено"),
            "expected plural '3 повторов' for 3 repeats, got: $err")
        assertTrue(!err.contains("ещё 3 повтор подавлено"),
            "singular form must not be used for 3 repeats: $err")
    }

    private inline fun captureErr(block: () -> Unit): String {
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
