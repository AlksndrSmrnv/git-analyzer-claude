package com.analyzer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName

class AnalysisPostProcessorTest {

    @Test
    @DisplayName("Dedup keeps the most recent record when a test is moved")
    fun dedupKeepsMostRecentMovedTest() {
        val records = listOf(
            TestRecord(
                authorEmail = "new@author.com",
                functionName = "movedTest",
                filePath = "src/test/kotlin/NewClassTest.kt",
                date = "2026-04-02T10:00:00Z",
                systemId = "CI002"
            ),
            TestRecord(
                authorEmail = "old@author.com",
                functionName = "movedTest",
                filePath = "src/test/kotlin/OldClassTest.kt",
                date = "2026-01-10T10:00:00Z",
                systemId = "CI001"
            )
        )

        val result = deduplicateLatestTests(records)

        assertEquals(1, result.size)
        assertEquals("new@author.com", result[0].authorEmail)
        assertEquals("src/test/kotlin/NewClassTest.kt", result[0].filePath)
        assertEquals("2026-04-02T10:00:00Z", result[0].date)
        assertEquals("CI002", result[0].systemId)
    }

    @Test
    @DisplayName("Dedup chooses latest date even when records arrive out of order")
    fun dedupChoosesLatestDateOutOfOrder() {
        val records = listOf(
            TestRecord(
                authorEmail = "old@author.com",
                functionName = "sameNameTest",
                filePath = "src/test/kotlin/OldClassTest.kt",
                date = "2026-01-10T10:00:00Z",
                systemId = "CI001"
            ),
            TestRecord(
                authorEmail = "new@author.com",
                functionName = "sameNameTest",
                filePath = "src/test/kotlin/NewClassTest.kt",
                date = "2026-04-02T10:00:00Z",
                systemId = "CI002"
            )
        )

        val result = deduplicateLatestTests(records)

        assertEquals(1, result.size)
        assertEquals("new@author.com", result[0].authorEmail)
        assertEquals("src/test/kotlin/NewClassTest.kt", result[0].filePath)
        assertEquals("CI002", result[0].systemId)
    }

    @Test
    @DisplayName("Records with different function names are preserved")
    fun keepsDifferentFunctionNames() {
        val records = listOf(
            TestRecord("qa1@author.com", "firstTest", "src/test/kotlin/SampleTest.kt", "2026-04-02T10:00:00Z"),
            TestRecord("qa2@author.com", "secondTest", "src/test/kotlin/SampleTest.kt", "2026-04-01T10:00:00Z")
        )

        val result = deduplicateLatestTests(records)

        assertEquals(2, result.size)
        assertEquals(listOf("firstTest", "secondTest"), result.map { it.functionName })
    }

    @Test
    @DisplayName("Dedup uses only function name even for different files")
    fun dedupUsesFunctionNameOnly() {
        val records = listOf(
            TestRecord(
                authorEmail = "qa1@author.com",
                functionName = "sameNameTest",
                filePath = "src/test/kotlin/FirstClassTest.kt",
                date = "2026-04-02T10:00:00Z",
                systemId = "CI001"
            ),
            TestRecord(
                authorEmail = "qa2@author.com",
                functionName = "sameNameTest",
                filePath = "src/test/kotlin/SecondClassTest.kt",
                date = "2026-04-01T10:00:00Z",
                systemId = "CI999"
            )
        )

        val result = deduplicateLatestTests(records)

        assertEquals(1, result.size)
        assertEquals("src/test/kotlin/FirstClassTest.kt", result[0].filePath)
        assertEquals("CI001", result[0].systemId)
    }

}
