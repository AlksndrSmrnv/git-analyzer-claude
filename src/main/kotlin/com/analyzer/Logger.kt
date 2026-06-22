package com.analyzer

/**
 * Минимальный потокобезопасный логгер для анализатора.
 *
 * Заменяет сырые `System.err.println`/`println` в hot-path и параллельных
 * корутинах. Печатает на stdout (INFO/DEBUG) и stderr (WARN/ERROR),
 * с единым префиксом, чтобы отличать шум git-процессов от своих сообщений.
 *
 * Уровень задаётся через свойство `-Danalyzer.log=DEBUG|INFO|WARN|ERROR`
 * (по умолчанию INFO).
 */
internal object Logger {
    enum class Level { DEBUG, INFO, WARN, ERROR }

    private val threshold: Level = run {
        val raw = System.getProperty("analyzer.log") ?: "INFO"
        runCatching { Level.valueOf(raw.uppercase()) }.getOrDefault(Level.INFO)
    }

    private val lock = Any()
    private var suppressedCount = 0

    fun debug(message: String) = log(Level.DEBUG, message)
    fun info(message: String) = log(Level.INFO, message)
    fun warn(message: String) = log(Level.WARN, message)
    fun error(message: String) = log(Level.ERROR, message)

    /**
     * Заменяет дублирующиеся предупреждения git: вместо N одинаковых строк
     * печатает первую и увеличивает счётчик подавленных, который вывозится
     * отдельным [flushSummary].
     */
    fun warnGitErrorOnce(message: String) {
        synchronized(lock) {
            if (suppressedCount == 0) {
                System.err.println("[WARN] (git) $message")
            }
            suppressedCount++
        }
    }

    fun flushSummary() {
        synchronized(lock) {
            if (suppressedCount > 0) {
                System.err.println("[WARN] (git) ... ещё ${suppressedCount - 1} аналогичных сообщений подавлено")
                suppressedCount = 0
            }
        }
    }

    private fun log(level: Level, message: String) {
        if (level.ordinal < threshold.ordinal) return
        val tag = "[${level.name}]"
        synchronized(lock) {
            val stream = if (level == Level.ERROR || level == Level.WARN) System.err else System.out
            stream.println("$tag $message")
        }
    }
}