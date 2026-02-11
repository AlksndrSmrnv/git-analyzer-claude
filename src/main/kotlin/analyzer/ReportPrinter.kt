package analyzer

class ReportPrinter {

    fun printReport(
        testsByAuthor: Map<String, List<NewTestInfo>>,
        period: String,
        repoPath: String
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

        println("  %-40s %s".format("Author", "New Tests"))
        println("  " + "-".repeat(50))
        var total = 0
        for ((author, tests) in sorted) {
            println("  %-40s %d".format(author, tests.size))
            total += tests.size
        }
        println("  " + "-".repeat(50))
        println("  %-40s %d".format("TOTAL", total))
        println()

        println("  Details:")
        println()
        for ((author, tests) in sorted) {
            println("  $author:")
            for (test in tests) {
                println("    - ${test.functionName} (${test.filePath})")
            }
            println()
        }

        println(separator)
    }
}
