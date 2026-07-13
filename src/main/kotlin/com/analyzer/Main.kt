package com.analyzer

import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * Результат обработки одного коммита.
 */
private data class CommitResult(
    val records: List<TestRecord>
)

fun main() {
    try {
        val repoPath = AnalyzerConfig.REPO_PATH
        val outputDir = AnalyzerConfig.HTML_REPORT_DIR
        val threadCount = AnalyzerConfig.THREAD_COUNT

        val gitClient: GitOperations = GitClient(repoPath)
        runAnalysis(
            gitClient = gitClient,
            repoPath = repoPath,
            outputDir = outputDir,
            threadCount = threadCount,
            systemNames = AnalyzerConfig.SYSTEM_NAMES,
            authorNames = AnalyzerConfig.AUTHOR_NAMES,
            excludedTesters = AnalyzerConfig.EXCLUDED_TESTERS
        )
    } catch (e: Exception) {
        Logger.error(e.message ?: e::class.simpleName ?: "Unexpected error")
    } finally {
        // Вывод суммарного счётчика подавленных git-дубликатов: в finally,
        // чтобы он не терялся при ранних return и при исключении.
        Logger.flushSummary()
    }
}

/**
 * Точка запуска анализа, отделённая от `main()` для тестируемости.
 * Принимает [gitClient] как [GitOperations], что позволяет подставлять
 * in-memory fake-репозиторий в интеграционных тестах. Настройки HTML-отчёта
 * также передаются явно, чтобы результат не зависел от глобального конфига.
 */
internal fun runAnalysis(
    gitClient: GitOperations,
    repoPath: String,
    outputDir: String,
    threadCount: Int,
    systemNames: Map<String, String>,
    authorNames: Map<String, String>,
    excludedTesters: Set<String>
) {
    if (!gitClient.validateRepo()) {
        Logger.error("'$repoPath' is not a valid git repository.")
        return
    }

    val commits = gitClient.getCommits()

    if (commits.isEmpty()) {
        Logger.info("No commits found.")
    } else {
        Logger.info("Found ${commits.size} commits to analyze (threads: $threadCount)...")
    }

    // Определяем root-коммиты одной командой вместо проверки каждого.
    // Для пустой истории не обращаемся к отсутствующему HEAD.
    val rootCommits = if (commits.isEmpty()) emptySet() else gitClient.findRootCommits()

    val allTestRecords = mutableListOf<TestRecord>()
    val processed = AtomicInteger(0)
    val total = commits.size

    // TestParser stateless на уровне класса (все состояния локальны в вызове),
    // поэтому один экземпляр безопасно переиспользуется между корутинами.
    val parser = TestParser()

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
                        Logger.info("  Processing commit $count/$total...")
                    }

                    if (diff.isBlank()) {
                        return@async CommitResult(emptyList())
                    }

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

                    CommitResult(records)
                }
            }.awaitAll()

            // Собираем результаты последовательно: порядок git log нужен,
            // чтобы финальный dedup оставлял самую позднюю запись.
            for (result in results) {
                if (result.records.isNotEmpty()) {
                    allTestRecords.addAll(result.records)
                }
            }
        }
    }

    enrichSystemIds(allTestRecords, gitClient, parser)
    val dedupedRecords = deduplicateLatestTests(allTestRecords)
    val htmlGenerator = HtmlReportGenerator()
    htmlGenerator.generate(
        dedupedRecords, repoPath, outputDir,
        systemNames = systemNames,
        authorNames = authorNames,
        excludedTesters = excludedTesters
    )
    Logger.info("HTML report generated: $outputDir/report.html")
}

/**
 * Второй проход: обогащает записи с systemId == null.
 *
 * Для каждого файла, в котором есть тесты без systemId, ищет коммиты,
 * добавлявшие @System к классам в этом файле (через pickaxe-поиск).
 * Для каждого такого коммита читает полное содержимое файла через git show
 * и парсит маппинг functionName -> systemId. Обогащает записи по имени функции.
 *
 * Это решает случай, когда тест добавлен в одном коммите, а @System на классе —
 * в другом. Работает даже если тест впоследствии был удалён.
 */
private fun enrichSystemIds(
    records: MutableList<TestRecord>,
    gitClient: GitOperations,
    parser: TestParser
) {
    val nullRecordsByFile = records
        .mapIndexedNotNull { idx, r -> if (r.systemId == null) idx to r else null }
        .groupBy { (_, r) -> r.filePath }

    if (nullRecordsByFile.isEmpty()) return

    for ((filePath, indexedRecords) in nullRecordsByFile) {
        val commits = gitClient.findCommitsTouchingSystemAnnotation(filePath)
        if (commits.isEmpty()) continue

        for (commitHash in commits) {
            val stillNull = indexedRecords.filter { (idx, _) -> records[idx].systemId == null }
            if (stillNull.isEmpty()) break

            val content = gitClient.getFileContentAtCommit(commitHash, filePath)
            if (content.isBlank()) continue

            val mapping = parser.extractSystemMapping(content)
            if (mapping.isEmpty()) continue

            for ((idx, record) in stillNull) {
                val systemId = mapping[record.functionName]
                if (systemId != null) {
                    records[idx] = records[idx].copy(systemId = systemId)
                }
            }
        }
    }
}
