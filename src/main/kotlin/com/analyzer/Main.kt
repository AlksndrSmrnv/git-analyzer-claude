package com.analyzer

import java.time.OffsetDateTime
import java.time.LocalDate

fun main() {
    val repoPath = AnalyzerConfig.REPO_PATH
    val days = AnalyzerConfig.DAYS
    val generateHtml = AnalyzerConfig.GENERATE_HTML

    val gitClient = GitClient(repoPath)

    if (!gitClient.validateRepo()) {
        System.err.println("Error: '$repoPath' is not a valid git repository.")
        return
    }

    val commits = if (generateHtml) {
        gitClient.getCommits(sinceDays = null)
    } else {
        gitClient.getCommits(sinceDays = days)
    }

    if (commits.isEmpty()) {
        println("No commits found.")
        return
    }
    println("Found ${commits.size} commits to analyze...")

    val parser = TestParser()
    val allTestRecords = mutableListOf<TestRecord>()
    val testsByAuthor = mutableMapOf<String, MutableList<NewTestInfo>>()

    for ((index, commit) in commits.withIndex()) {
        if ((index + 1) % 50 == 0) {
            println("  Processing commit ${index + 1}/${commits.size}...")
        }

        val diff = gitClient.getDiffForCommit(commit.hash)
        if (diff.isBlank()) continue

        val newTests = parser.findNewTests(diff)
        if (newTests.isNotEmpty()) {
            for (test in newTests) {
                allTestRecords.add(
                    TestRecord(
                        authorEmail = commit.authorEmail,
                        functionName = test.functionName,
                        filePath = test.filePath,
                        date = commit.date,
                        systemId = test.systemId
                    )
                )
            }

            val includeInConsole = if (days != null && generateHtml) {
                isWithinDays(commit.date, days)
            } else {
                true
            }

            if (includeInConsole) {
                testsByAuthor.getOrPut(commit.authorEmail) { mutableListOf() }
                    .addAll(newTests)
            }
        }
    }

    println()
    val periodLabel = if (days != null) "Last $days days" else "All time"
    val printer = ReportPrinter()
    printer.printReport(testsByAuthor, periodLabel, repoPath)

    if (generateHtml) {
        val outputPath = AnalyzerConfig.HTML_REPORT_PATH
        val htmlGenerator = HtmlReportGenerator()
        htmlGenerator.generate(
            allTestRecords, repoPath, outputPath,
            systemNames = AnalyzerConfig.SYSTEM_NAMES,
            authorNames = AnalyzerConfig.AUTHOR_NAMES
        )
        println("HTML report generated: $outputPath")
    }
}

private fun isWithinDays(isoDate: String, days: Int): Boolean {
    return try {
        val commitDate = OffsetDateTime.parse(isoDate).toLocalDate()
        val cutoff = LocalDate.now().minusDays(days.toLong())
        !commitDate.isBefore(cutoff)
    } catch (e: Exception) {
        true
    }
}
