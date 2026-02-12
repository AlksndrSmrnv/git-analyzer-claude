package com.analyzer

data class NewTestInfo(
    val functionName: String,
    val filePath: String
)

class TestParser {

    private val testAnnotations = setOf("@Test", "@ParameterizedTest", "@RepeatedTest")

    /**
     * Находит по-настоящему НОВЫЕ тесты в diff-выводе git.
     *
     * Алгоритм: в каждом hunk'е собираем тесты из добавленных строк (+)
     * и тесты из удалённых строк (-). Новыми считаются только те добавленные
     * тесты, для которых нет соответствующего удалённого теста в том же hunk'е.
     * Это исключает переименования и рефакторинги.
     */
    fun findNewTests(diffOutput: String): List<NewTestInfo> {
        val results = mutableListOf<NewTestInfo>()
        var currentFile: String? = null

        // Состояние для добавленных строк (+)
        var addedPendingAnnotation = false
        val addedTests = mutableListOf<NewTestInfo>()

        // Состояние для удалённых строк (-)
        var removedPendingAnnotation = false
        var removedTestCount = 0

        fun flushHunk() {
            // Количество действительно новых тестов = added - removed (но не меньше 0)
            val netNew = (addedTests.size - removedTestCount).coerceAtLeast(0)
            if (netNew > 0) {
                // Берём последние netNew тестов из added (новые — те, что «не покрыты» удалениями)
                results.addAll(addedTests.takeLast(netNew))
            }
            addedTests.clear()
            removedTestCount = 0
            addedPendingAnnotation = false
            removedPendingAnnotation = false
        }

        for (line in diffOutput.lines()) {
            // Новый файл
            if (line.startsWith("+++ b/")) {
                flushHunk()
                currentFile = line.removePrefix("+++ b/")
                continue
            }

            // Пропускаем метаданные diff
            if (line.startsWith("---") || line.startsWith("diff ") || line.startsWith("index ")) {
                continue
            }

            // Новый hunk — сбрасываем и сохраняем результаты предыдущего
            if (line.startsWith("@@")) {
                flushHunk()
                continue
            }

            // === Добавленные строки (+) ===
            if (line.startsWith("+")) {
                val content = line.substring(1).trim()

                // @Test fun foo() на одной строке
                if (hasTestAnnotationAndFun(content)) {
                    val funName = extractFunctionName(content)
                    if (funName != null && currentFile != null) {
                        addedTests.add(NewTestInfo(funName, currentFile))
                    }
                    addedPendingAnnotation = false
                    continue
                }

                if (isTestAnnotation(content)) {
                    addedPendingAnnotation = true
                    continue
                }

                // Промежуточные аннотации (@DisplayName и т.п.) — флаг сохраняется
                if (addedPendingAnnotation && content.startsWith("@")) {
                    continue
                }

                if (addedPendingAnnotation && containsFunDeclaration(content)) {
                    val funName = extractFunctionName(content)
                    if (funName != null && currentFile != null) {
                        addedTests.add(NewTestInfo(funName, currentFile))
                    }
                    addedPendingAnnotation = false
                    continue
                }

                if (addedPendingAnnotation && content.isBlank()) {
                    continue
                }

                if (addedPendingAnnotation) {
                    addedPendingAnnotation = false
                }
                continue
            }

            // === Удалённые строки (-) ===
            if (line.startsWith("-")) {
                val content = line.substring(1).trim()

                if (hasTestAnnotationAndFun(content)) {
                    removedTestCount++
                    removedPendingAnnotation = false
                    continue
                }

                if (isTestAnnotation(content)) {
                    removedPendingAnnotation = true
                    continue
                }

                if (removedPendingAnnotation && content.startsWith("@")) {
                    continue
                }

                if (removedPendingAnnotation && containsFunDeclaration(content)) {
                    removedTestCount++
                    removedPendingAnnotation = false
                    continue
                }

                if (removedPendingAnnotation && content.isBlank()) {
                    continue
                }

                if (removedPendingAnnotation) {
                    removedPendingAnnotation = false
                }
                continue
            }

            // === Контекстные строки (без префикса) ===
            // Сбрасываем оба флага — аннотация уже существовала
            addedPendingAnnotation = false
            removedPendingAnnotation = false
        }

        // Последний hunk
        flushHunk()

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
