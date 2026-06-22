package com.analyzer

import java.util.concurrent.CompletableFuture

data class CommitInfo(
    val hash: String,
    val authorEmail: String,
    val date: String
)

/**
 * Разбирает вывод `git log --format=%H%x00%aE%x00%aI` (NUL-разделитель)
 * в список [CommitInfo]. Вынесен отдельно для unit-тестирования парсинга
 * без запуска реального git.
 */
internal fun parseCommitLines(output: String): List<CommitInfo> {
    if (output.isBlank()) return emptyList()
    return output.lines().mapNotNull { line ->
        val parts = line.split('\u0000')
        if (parts.size == 3) {
            CommitInfo(hash = parts[0], authorEmail = parts[1], date = parts[2])
        } else null
    }
}

class GitClient(private val repoPath: String) : GitOperations {

    override fun validateRepo(): Boolean {
        return try {
            val output = runGit("rev-parse", "--is-inside-work-tree")
            output.trim() == "true"
        } catch (e: Exception) {
            false
        }
    }

    override fun getCommits(): List<CommitInfo> {
        // Используем NUL-разделитель %x00 вместо пробела, чтобы корректно
        // обрабатывать пустой e-mail автора и адреса с пробелами (mailmap
        // может вернуть "Name <user@example.com>"). Пробел разорвал бы поля.
        val output = runGit("log", "--format=%H%x00%aE%x00%aI", "--no-merges", "--", "*.kt")
        return parseCommitLines(output)
    }

    /**
     * Определяет все root-коммиты репозитория одной командой git.
     * Возвращает Set хэшей root-коммитов.
     */
    override fun findRootCommits(): Set<String> {
        return try {
            val output = runGit("rev-list", "--max-parents=0", "HEAD")
            output.lines().map { it.trim() }.filter { it.isNotBlank() }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    /**
     * Получает diff коммита с полным контекстом файла (-U999999),
     * чтобы парсер всегда видел @System на уровне класса,
     * даже если новый тест добавлен далеко от объявления класса.
     */
    override fun getDiffForCommit(commitHash: String, isRoot: Boolean): String {
        return if (isRoot) {
            runGit("diff-tree", "--root", "-M", "-U999999", "-p", commitHash, "--", "*.kt")
        } else {
            runGit("diff-tree", "-M", "-U999999", "-p", commitHash, "--", "*.kt")
        }
    }

    /**
     * Возвращает хэши коммитов (от старого к новому), в которых
     * в данном файле добавлялась/менялась аннотация @System(.
     * Использует pickaxe-поиск, чтобы находить изменения даже если
     * тест уже удалён из файла.
     */
    override fun findCommitsTouchingSystemAnnotation(filePath: String): List<String> {
        return try {
            val output = runGit(
                "log", "--all", "--reverse", "--format=%H",
                "-S", "@System(", "--", filePath
            )
            output.lines().map { it.trim() }.filter { it.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Возвращает полное содержимое файла на момент указанного коммита.
     * Возвращает пустую строку, если файл не существовал в этом коммите.
     */
    override fun getFileContentAtCommit(commitHash: String, filePath: String): String {
        return try {
            runGit("show", "$commitHash:$filePath", suppressErrors = true)
        } catch (e: Exception) {
            ""
        }
    }

    private fun runGit(vararg args: String, suppressErrors: Boolean = false): String {
        val command = listOf("git", "-C", repoPath) + args.toList()
        val process = ProcessBuilder(command)
            .redirectErrorStream(false)
            .apply {
                // Предотвращаем типичные зависания git: pager и интерактивные
                // запросы учётных данных, которые блокируют чтение потоков.
                environment()["GIT_PAGER"] = "cat"
                environment()["GIT_TERMINAL_PROMPT"] = "0"
            }
            .start()
        try {
            val stderrFuture = CompletableFuture.supplyAsync {
                process.errorStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            }
            val stdout = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val exitCode = process.waitFor()
            val stderr = stderrFuture.get()
            if (!suppressErrors && exitCode != 0 && stderr.isNotBlank()) {
                Logger.warnGitErrorOnce(stderr.lines().firstOrNull() ?: stderr)
            }
            return stdout.trim()
        } finally {
            process.destroyForcibly()
        }
    }
}
