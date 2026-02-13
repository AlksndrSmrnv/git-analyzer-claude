package com.analyzer

import kotlinx.coroutines.*
import java.time.OffsetDateTime
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

/**
 * Результат обработки одного коммита.
 */
private data class CommitResult(
    val records: List<TestRecord>,
    val commit: CommitInfo
)

fun main() {
    val repoPath = AnalyzerConfig.REPO_PATH
    val days = AnalyzerConfig.DAYS
    val generateHtml = AnalyzerConfig.GENERATE_HTML
    val threadCount = AnalyzerConfig.THREAD_COUNT

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
    println("Found ${commits.size} commits to analyze (threads: $threadCount)...")

    // Определяем root-коммиты одной командой вместо проверки каждого
    val rootCommits = gitClient.findRootCommits()

    val allTestRecords = mutableListOf<TestRecord>()
    val testsByAuthor = mutableMapOf<String, MutableList<NewTestInfo>>()

    val processed = AtomicInteger(0)
    val total = commits.size

    runBlocking {
        // Фиксированный диспатчер с заданным числом потоков
        val dispatcher = Dispatchers.IO.limitedParallelism(threadCount)

        // Обрабатываем коммиты пакетами для ограничения количества одновременных git-процессов
        val batchSize = threadCount * 4
        val batches = commits.chunked(batchSize)

        for (batch in batches) {
            val results = batch.map { commit ->
                async(dispatcher) {
                    val isRoot = commit.hash in rootCommits
                    val diff = gitClient.getDiffForCommit(commit.hash, isRoot)

                    val count = processed.incrementAndGet()
                    if (count % 100 == 0 || count == total) {
                        println("  Processing commit $count/$total...")
                    }

                    if (diff.isBlank()) {
                        return@async CommitResult(emptyList(), commit)
                    }

                    // TestParser — каждый вызов findNewTests() работает
                    // только с локальными переменными, создаём свой экземпляр
                    val parser = TestParser()
                    val newTests = parser.findNewTests(diff)

                    val records = newTests.map { test ->
                        TestRecord(
                            authorEmail = commit.authorEmail,
                            functionName = test.functionName,
                            filePath = test.filePath,
                            date = commit.date,
                            systemId = test.systemId
                        )
                    }

                    CommitResult(records, commit)
                }
            }.awaitAll()

            // Собираем результаты последовательно (порядок не важен для статистики,
            // но коллекции не thread-safe)
            for (result in results) {
                if (result.records.isNotEmpty()) {
                    allTestRecords.addAll(result.records)

                    val includeInConsole = if (days != null && generateHtml) {
                        isWithinDays(result.commit.date, days)
                    } else {
                        true
                    }

                    if (includeInConsole) {
                        val newTests = result.records.map { r ->
                            NewTestInfo(r.functionName, r.filePath, r.systemId)
                        }
                        testsByAuthor.getOrPut(result.commit.authorEmail) { mutableListOf() }
                            .addAll(newTests)
                    }
                }
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
