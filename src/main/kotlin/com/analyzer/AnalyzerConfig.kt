package com.analyzer

object AnalyzerConfig {
    /** Путь к анализируемому git-репозиторию */
    val REPO_PATH = "/path/to/your/test-repo"

    /**
     * Количество дней для фильтрации коммитов (консольный отчёт).
     * null — анализировать за всё время.
     * 7   — за последнюю неделю.
     * 30  — за последний месяц.
     */
    val DAYS: Int? = 7

    /** Генерировать ли HTML-отчёт */
    val GENERATE_HTML = true

    /** Путь для сохранения HTML-отчёта */
    val HTML_REPORT_PATH = "test-report.html"

    /**
     * Количество потоков для параллельного анализа коммитов.
     * По умолчанию — количество доступных процессорных ядер.
     */
    val THREAD_COUNT: Int = Runtime.getRuntime().availableProcessors()

    /**
     * Словарь: ID системы → понятное название.
     * Например: "CI01337" → "Платёжная система"
     */
    val SYSTEM_NAMES: Map<String, String> = mapOf(
        // "CI01337" to "Платёжная система",
        // "CI02000" to "Система авторизации",
    )

    /**
     * Словарь: e-mail → Фамилия Имя.
     * Если несколько e-mail указывают на одного человека,
     * укажите одинаковое имя — данные будут объединены.
     */
    val AUTHOR_NAMES: Map<String, String> = mapOf(
        // "ivanov@company.com" to "Иванов Иван",
        // "ivan.ivanov@company.com" to "Иванов Иван",
    )
}
