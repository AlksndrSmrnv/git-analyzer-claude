package com.analyzer

import java.time.LocalDate
import java.time.OffsetDateTime

internal fun deduplicateLatestTests(records: List<TestRecord>): List<TestRecord> {
    val seenFunctionNames = mutableSetOf<String>()
    return records.filter { record -> seenFunctionNames.add(record.functionName) }
}

internal fun filterRecordsWithinDays(
    records: List<TestRecord>,
    days: Int?,
    currentDate: LocalDate = LocalDate.now()
): List<TestRecord> {
    if (days == null) return records

    val cutoff = currentDate.minusDays(days.toLong())
    return records.filter { record ->
        try {
            val commitDate = OffsetDateTime.parse(record.date).toLocalDate()
            !commitDate.isBefore(cutoff)
        } catch (e: Exception) {
            true
        }
    }
}

internal fun buildTestsByAuthor(records: List<TestRecord>): Map<String, List<NewTestInfo>> {
    val testsByAuthor = linkedMapOf<String, MutableList<NewTestInfo>>()

    for (record in records) {
        testsByAuthor.getOrPut(record.authorEmail) { mutableListOf() }
            .add(NewTestInfo(record.functionName, record.filePath, record.systemId))
    }

    return testsByAuthor
}
