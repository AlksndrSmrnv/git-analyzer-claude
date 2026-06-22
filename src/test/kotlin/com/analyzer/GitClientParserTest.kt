package com.analyzer

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class GitClientParserTest {

    @Test
    @DisplayName("parseCommitLines parses well-formed NUL-separated rows")
    fun parsesWellFormedRows() {
        val output = "abc123\u0000user@example.com\u00002026-04-01T10:00:00+03:00\n" +
            "def456\u0000other@example.com\u00002026-04-02T12:00:00+03:00"
        val result = parseCommitLines(output)
        assertEquals(2, result.size)
        assertEquals("abc123", result[0].hash)
        assertEquals("user@example.com", result[0].authorEmail)
        assertEquals("2026-04-01T10:00:00+03:00", result[0].date)
        assertEquals("def456", result[1].hash)
    }

    @Test
    @DisplayName("parseCommitLines keeps row with empty author email (NUL still separates)")
    fun keepsEmptyEmail() {
        val output = "abc123\u0000\u00002026-04-01T10:00:00+03:00"
        val result = parseCommitLines(output)
        assertEquals(1, result.size)
        assertEquals("abc123", result[0].hash)
        assertEquals("", result[0].authorEmail)
        assertEquals("2026-04-01T10:00:00+03:00", result[0].date)
    }

    @Test
    @DisplayName("parseCommitLines keeps email containing spaces (mailmap Name <email>)")
    fun keepsEmailWithSpaces() {
        val output = "abc123\u0000Ivan Ivanov <ivan@example.com>\u00002026-04-01T10:00:00+03:00"
        val result = parseCommitLines(output)
        assertEquals(1, result.size)
        assertEquals("Ivan Ivanov <ivan@example.com>", result[0].authorEmail)
    }

    @Test
    @DisplayName("parseCommitLines ignores malformed rows with fewer than 3 fields")
    fun ignoresMalformedRows() {
        val output = "abc123\u00002026-04-01T10:00:00+03:00\njustoneword"
        val result = parseCommitLines(output)
        assertTrue(result.isEmpty())
    }

    @Test
    @DisplayName("parseCommitLines returns empty for blank output")
    fun emptyForBlankOutput() {
        assertTrue(parseCommitLines("").isEmpty())
        assertTrue(parseCommitLines("   ").isEmpty())
    }
}