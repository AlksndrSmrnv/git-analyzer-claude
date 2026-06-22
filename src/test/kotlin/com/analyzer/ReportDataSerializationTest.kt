package com.analyzer

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class ReportDataSerializationTest {

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    @Test
    @DisplayName("TestRecord serializes with JSON field names: author/test/file/date/system")
    fun serializesFieldNames() {
        val record = TestRecord(
            authorEmail = "user@example.com",
            functionName = "shouldWork",
            filePath = "src/test/kotlin/MyTest.kt",
            date = "2026-04-01T10:00:00+03:00",
            systemId = "CI001"
        )
        val output = json.encodeToString(TestRecord.serializer(), record)
        assertTrue(output.contains("\"author\":\"user@example.com\""))
        assertTrue(output.contains("\"test\":\"shouldWork\""))
        assertTrue(output.contains("\"file\":\"src/test/kotlin/MyTest.kt\""))
        assertTrue(output.contains("\"date\":\"2026-04-01T10:00:00+03:00\""))
        assertTrue(output.contains("\"system\":\"CI001\""))
    }

    @Test
    @DisplayName("TestRecord null systemId serializes as explicit null, not omitted")
    fun nullSystemSerializedExplicitly() {
        val record = TestRecord(
            authorEmail = "user@example.com",
            functionName = "shouldWork",
            filePath = "src/test/kotlin/MyTest.kt",
            date = "2026-04-01T10:00:00+03:00",
            systemId = null
        )
        val output = json.encodeToString(TestRecord.serializer(), record)
        assertTrue(output.contains("\"system\":null"), "system:null must be present, got: $output")
    }

    @Test
    @DisplayName("ReportData serializes records, name maps and generatedAt together")
    fun reportDataRoundTrip() {
        val data = ReportData(
            records = listOf(
                TestRecord("a@x.com", "t1", "f1.kt", "2026-01-01T00:00:00Z", "CI1"),
                TestRecord("b@x.com", "t2", "f2.kt", "2026-01-02T00:00:00Z", null)
            ),
            systemNames = mapOf("CI1" to "Платежи"),
            authorNames = mapOf("a@x.com" to "Иван"),
            generatedAt = "2026-04-01T12:00:00+03:00"
        )
        val serialized = json.encodeToString(ReportData.serializer(), data)
        val parsed = json.decodeFromString(ReportData.serializer(), serialized)

        assertEquals(2, parsed.records.size)
        assertEquals("t1", parsed.records[0].functionName)
        assertEquals("CI1", parsed.records[0].systemId)
        assertNull(parsed.records[1].systemId)
        assertEquals("Платежи", parsed.systemNames["CI1"])
        assertEquals("Иван", parsed.authorNames["a@x.com"])
        assertEquals("2026-04-01T12:00:00+03:00", parsed.generatedAt)
    }

    @Test
    @DisplayName("Special characters in test names are properly JSON-escaped")
    fun escapesSpecialCharacters() {
        val record = TestRecord(
            authorEmail = "user@example.com",
            functionName = """test with "quotes" and \ backslash""",
            filePath = "src/test/kotlin/MyTest.kt",
            date = "2026-04-01T10:00:00+03:00",
            systemId = null
        )
        val output = json.encodeToString(TestRecord.serializer(), record)
        val parsed = json.decodeFromString(TestRecord.serializer(), output)
        assertEquals(record.functionName, parsed.functionName)
    }
}
