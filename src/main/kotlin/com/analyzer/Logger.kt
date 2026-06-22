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
    // Подсчёт идёт отдельно для каждого уникального текста ошибки git: так
    // разные ошибки не маскируют друг друга (старая версия считала первую
    // встретившуюся и подавляла все остальные как «аналогичные»).
    private val suppressedByMessage = linkedMapOf<String, Int>()

    fun debug(message: String) = log(Level.DEBUG, message)
    fun info(message: String) = log(Level.INFO, message)
    fun warn(message: String) = log(Level.WARN, message)
    fun error(message: String) = log(Level.ERROR, message)

    /**
     * Точечно-тестовая точка сброса: молча очищает накопленное состояние,
     * чтобы тесты не зависели от порядка выполнения. Не печатает ничего.
     */
    internal fun resetForTests() {
        synchronized(lock) {
            suppressedByMessage.clear()
        }
    }

    /**
     * Дедуплицирует идентичные git-предупреждения: первый раз печатает, далее
     * для этого же текста только инкрементирует счётчик. Разные тексты
     * печатаются независимо. Сбросить накопленное можно через [flushSummary].
     */
    fun warnGitErrorOnce(message: String) {
        synchronized(lock) {
            val count = suppressedByMessage[message] ?: 0
            if (count == 0) {
                System.err.println("[WARN] (git) $message")
            }
            suppressedByMessage[message] = count + 1
        }
    }

    /**
     * Выводит суммарный счётчик подавленных дубликатов (для каждого текста
     * отдельно, без самой первой напечатанной строки).
     */
    fun flushSummary() {
        synchronized(lock) {
            suppressedByMessage.forEach { (message, count) ->
                if (count > 1) {
                    val repeats = count - 1
                    val word = if (repeats == 1) "повтор" else "повторов"
                    System.err.println("[WARN] (git) [$message] — ещё $repeats $word подавлено")
                }
            }
            suppressedByMessage.clear()
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
