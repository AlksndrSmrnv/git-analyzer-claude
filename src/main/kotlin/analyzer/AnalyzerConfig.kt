package analyzer

object AnalyzerConfig {
    /** Путь к анализируемому git-репозиторию */
    val REPO_PATH = "/path/to/your/test-repo"

    /**
     * Количество дней для фильтрации коммитов.
     * null — анализировать за всё время.
     * 7   — за последнюю неделю.
     * 30  — за последний месяц.
     */
    val DAYS: Int? = 7
}
