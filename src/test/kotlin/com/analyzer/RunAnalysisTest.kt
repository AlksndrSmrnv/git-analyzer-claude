package com.analyzer

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path

class RunAnalysisTest {

    @Test
    @DisplayName("runAnalysis prints error and returns early for invalid repository")
    fun invalidRepo() {
        val git = FakeGitRepository().setValid(false)
        val err = catchSystemErr {
            runAnalysis(
                gitClient = git,
                repoPath = "/no/such/repo",
                days = null,
                generateHtml = false,
                threadCount = 1
            )
        }
        assertTrue(err.contains("not a valid git repository"))
    }

    @Test
    @DisplayName("runAnalysis handles empty commit list gracefully")
    fun emptyCommits(@TempDir tmp: Path) {
        val git = FakeGitRepository().setValid(true)
        val out = catchSystemOut {
            runAnalysis(
                gitClient = git,
                repoPath = tmp.toString(),
                days = null,
                generateHtml = false,
                threadCount = 1
            )
        }
        assertTrue(out.contains("No commits found."))
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
