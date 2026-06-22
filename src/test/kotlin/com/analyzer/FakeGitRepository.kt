package com.analyzer

/**
 * In-memory реализация [GitOperations] для unit-тестов.
 *
 * Хранит предзаполненный список коммитов и сопутствующие diff'ы,
 * не обращается к `git`. Используется в интеграционных тестах
 * `Main` (`runAnalysis`) и логики post-processing.
 */
class FakeGitRepository : GitOperations {

    private var valid: Boolean = true
    private val commits = mutableListOf<CommitInfo>()
    private val roots = mutableSetOf<String>()
    private val diffs = mutableMapOf<String, String>()
    private val systemCommits = mutableMapOf<String, List<String>>()
    private val fileContents = mutableMapOf<Pair<String, String>, String>()

    fun setValid(value: Boolean): FakeGitRepository = apply { valid = value }

    /** Добавляет коммит. Порядок вызовов = порядок в `getCommits()` (новые → старые). */
    fun addCommit(
        hash: String,
        authorEmail: String,
        date: String,
        diff: String,
        isRoot: Boolean = false
    ): FakeGitRepository = apply {
        commits.add(CommitInfo(hash, authorEmail, date))
        diffs[hash] = diff
        if (isRoot) roots.add(hash)
    }

    fun setSystemCommits(filePath: String, hashes: List<String>): FakeGitRepository = apply {
        systemCommits[filePath] = hashes
    }

    fun setFileContent(commitHash: String, filePath: String, content: String): FakeGitRepository = apply {
        fileContents[commitHash to filePath] = content
    }

    override fun validateRepo(): Boolean = valid

    override fun getCommits(): List<CommitInfo> = commits.toList()

    override fun findRootCommits(): Set<String> = roots.toSet()

    override fun getDiffForCommit(commitHash: String, isRoot: Boolean): String =
        diffs[commitHash] ?: ""

    override fun findCommitsTouchingSystemAnnotation(filePath: String): List<String> =
        systemCommits[filePath] ?: emptyList()

    override fun getFileContentAtCommit(commitHash: String, filePath: String): String =
        fileContents[commitHash to filePath] ?: ""
}
