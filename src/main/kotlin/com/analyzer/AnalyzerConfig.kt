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
}
