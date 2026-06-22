package com.analyzer

/**
 * Источник git-данных для анализатора.
 *
 * Реализации:
 * - [GitClient] — обёртка над локальным `git` через `ProcessBuilder`;
 * - `FakeGitRepository` (в тестах) — in-memory репозиторий.
 */
interface GitOperations {
    /** Возвращает true, если [repoPath] — валидный git working tree. */
    fun validateRepo(): Boolean

    /** Список коммитов, затрагивающих `*.kt`, без merge-коммитов (новые → старые). */
    fun getCommits(): List<CommitInfo>

    /** Множество хэшей root-коммитов репозитория. */
    fun findRootCommits(): Set<String>

    /** Diff коммита с полным контекстом файла (-U999999) для надёжного разбора `@System`. */
    fun getDiffForCommit(commitHash: String, isRoot: Boolean): String

    /**
     * Список хэшей коммитов (от старых к новым), в которых в файле [filePath]
     * добавлялась/менялась аннотация `@System(`. Используется pickaxe-поиском.
     */
    fun findCommitsTouchingSystemAnnotation(filePath: String): List<String>

    /** Полное содержимое файла [filePath] на момент коммита [commitHash] или пустая строка. */
    fun getFileContentAtCommit(commitHash: String, filePath: String): String
}