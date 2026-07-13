package com.analyzer

import java.time.OffsetDateTime

internal fun deduplicateLatestTests(records: List<TestRecord>): List<TestRecord> {
    val seenFunctionNames = mutableSetOf<String>()
    return records.withIndex()
        .sortedWith(
            compareByDescending<IndexedValue<TestRecord>> { parseRecordDate(it.value.date) }
                .thenBy { it.index }
        )
        .map { it.value }
        .filter { record -> seenFunctionNames.add(record.functionName) }
}

private fun parseRecordDate(value: String): OffsetDateTime? {
    return try {
        OffsetDateTime.parse(value)
    } catch (e: Exception) {
        null
    }
}
