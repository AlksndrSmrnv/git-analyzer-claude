package com.analyzer

class ReportPrinter {

    fun printReport(
        testsByAuthor: Map<String, List<NewTestInfo>>,
        period: String,
        repoPath: String,
        authorNames: Map<String, String> = emptyMap(),
        systemNames: Map<String, String> = emptyMap()
    ) {
        val separator = "=".repeat(60)

        println(separator)
        println("  Git Test Analyzer Report")
        println("  Repository: $repoPath")
        println("  Period: $period")
        println(separator)
        println()

        if (testsByAuthor.isEmpty()) {
            println("  No new tests found for the specified period.")
            println()
            println(separator)
            return
        }

        val sorted = testsByAuthor.entries.sortedByDescending { it.value.size }

        // Сводная таблица: динамическая ширина колонки автора по самой длинной строке.
        val authorLabels = sorted.map { (email, _) -> authorLabel(email, authorNames) }
        val authorColWidth = (authorLabels.maxOf { it.length } + 2).coerceAtLeast(14)
        val headerLine = "  ${"Author".padEnd(authorColWidth)} New Tests"
        val underline = "  " + "-".repeat(authorColWidth + " New Tests".length)

        println(headerLine)
        println(underline)
        var total = 0
        for ((label, entry) in authorLabels.zip(sorted)) {
            println("  ${label.padEnd(authorColWidth)} ${entry.value.size}")
            total += entry.value.size
        }
        println(underline)
        println("  ${"TOTAL".padEnd(authorColWidth)} $total")
        println()

        println("  Details:")
        println()
        for ((email, tests) in sorted) {
            val label = authorLabel(email, authorNames)
            println("  $label:")
            for (test in tests) {
                val systemSuffix = test.systemId?.let { " [${systemLabel(it, systemNames)}]" } ?: ""
                val dateSuffix = test.date?.let { " @ $it" } ?: ""
                println("    - ${test.functionName}${systemSuffix}${dateSuffix} (${test.filePath})")
            }
            println()
        }

        println(separator)
    }

    /**
     * Резолвит e-mail автора в читаемое имя. Если в [authorNames] есть запись,
     * показывает "Имя (email)"; иначе просто e-mail.
     */
    private fun authorLabel(email: String, authorNames: Map<String, String>): String {
        return authorNames[email]?.let { "$it ($email)" } ?: email
    }

    /**
     * Резолвит ID системы в читаемый label "Название (ID)"; иначе ID как есть.
     */
    private fun systemLabel(systemId: String, systemNames: Map<String, String>): String {
        return systemNames[systemId]?.let { "$it ($systemId)" } ?: systemId
    }
}