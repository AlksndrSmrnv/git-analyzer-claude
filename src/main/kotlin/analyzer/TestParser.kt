package analyzer

data class NewTestInfo(
    val functionName: String,
    val filePath: String
)

class TestParser {

    private val testAnnotations = setOf("@Test", "@ParameterizedTest", "@RepeatedTest")

    fun findNewTests(diffOutput: String): List<NewTestInfo> {
        val results = mutableListOf<NewTestInfo>()
        var currentFile: String? = null
        var pendingTestAnnotation = false

        for (line in diffOutput.lines()) {
            // Отслеживаем текущий файл
            if (line.startsWith("+++ b/")) {
                currentFile = line.removePrefix("+++ b/")
                pendingTestAnnotation = false
                continue
            }

            // Пропускаем метаданные diff
            if (line.startsWith("---") || line.startsWith("diff ") || line.startsWith("index ")) {
                continue
            }

            // Граница hunk — сбрасываем состояние
            if (line.startsWith("@@")) {
                pendingTestAnnotation = false
                continue
            }

            // Нас интересуют только добавленные строки (начинаются с +)
            if (!line.startsWith("+")) {
                // Контекстная или удалённая строка — сбрасываем флаг,
                // т.к. аннотация @Test уже существовала ранее
                if (pendingTestAnnotation) {
                    pendingTestAnnotation = false
                }
                continue
            }

            // Убираем префикс '+' и пробелы для анализа содержимого
            val content = line.substring(1).trim()

            // Случай: @Test fun foo() на одной строке
            if (hasTestAnnotationAndFun(content)) {
                val funName = extractFunctionName(content)
                if (funName != null && currentFile != null) {
                    results.add(NewTestInfo(funName, currentFile))
                }
                pendingTestAnnotation = false
                continue
            }

            // Строка содержит тестовую аннотацию
            if (isTestAnnotation(content)) {
                pendingTestAnnotation = true
                continue
            }

            // Строка с объявлением функции при активном флаге — новый тест найден.
            // Используем contains вместо startsWith, чтобы поддержать модификаторы
            // перед fun: internal fun, suspend fun, override fun и т.д.
            if (pendingTestAnnotation && containsFunDeclaration(content)) {
                val funName = extractFunctionName(content)
                if (funName != null && currentFile != null) {
                    results.add(NewTestInfo(funName, currentFile))
                }
                pendingTestAnnotation = false
                continue
            }

            // Промежуточные аннотации (@DisplayName и т.п.) — флаг сохраняется
            if (pendingTestAnnotation && content.startsWith("@")) {
                continue
            }

            // Пустая добавленная строка — флаг сохраняется
            if (pendingTestAnnotation && content.isBlank()) {
                continue
            }

            // Любая другая добавленная строка — сбрасываем флаг
            if (pendingTestAnnotation) {
                pendingTestAnnotation = false
            }
        }

        return results
    }

    private fun isTestAnnotation(content: String): Boolean {
        return testAnnotations.any { annotation ->
            content.startsWith(annotation) &&
                (content.length == annotation.length ||
                    content[annotation.length] in listOf('(', ' ', '\t'))
        }
    }

    private fun hasTestAnnotationAndFun(content: String): Boolean {
        return testAnnotations.any { annotation ->
            content.startsWith(annotation) &&
                content.length > annotation.length &&
                content[annotation.length] in listOf('(', ' ', '\t') &&
                content.contains(" fun ")
        }
    }

    private fun containsFunDeclaration(content: String): Boolean {
        return content.startsWith("fun ") || content.contains(" fun ")
    }

    private fun extractFunctionName(content: String): String? {
        val regex = Regex("""fun\s+(`[^`]+`|\w+)\s*\(""")
        return regex.find(content)?.groupValues?.get(1)
    }
}
