package com.analyzer

import java.time.LocalDate
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

internal fun filterRecordsWithinDays(
    records: List<TestRecord>,
    days: Int?,
    currentDate: LocalDate = LocalDate.now()
): List<TestRecord> {
    if (days == null) return records

    // "Последние N дней" = сегодня + (N-1) предыдущих дней = ровно N
    // календарных дней включительно. Без -1 получилось бы N полных дней
    // назад плюс сегодня, т.е. N+1 календарный день — типичный off-by-one.
    val cutoff = currentDate.minusDays((days - 1).toLong())
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
            .add(NewTestInfo(record.functionName, record.filePath, record.systemId, record.date))
    }

    return testsByAuthor
}

private fun parseRecordDate(value: String): OffsetDateTime? {
    return try {
        OffsetDateTime.parse(value)
    } catch (e: Exception) {
        null
    }
}
