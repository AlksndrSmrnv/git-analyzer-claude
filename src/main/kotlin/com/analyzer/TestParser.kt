package com.analyzer

data class NewTestInfo(
    val functionName: String,
    val filePath: String,
    val systemId: String? = null
)

class TestParser {

    private val testAnnotations = setOf("@Test", "@ParameterizedTest", "@RepeatedTest")
    private val systemAnnotationRegex = Regex("""@System\("([^"]+)"\)""")
    private val classDeclarationRegex = Regex("""\bclass\s+\w+""")

    /**
     * Находит по-настоящему НОВЫЕ тесты в diff-выводе git.
     *
     * Алгоритм: в каждом hunk'е собираем тесты из добавленных строк (+)
     * и тесты из удалённых строк (-). Новыми считаются только те добавленные
     * тесты, для которых нет соответствующего удалённого теста в том же hunk'е.
     * Это исключает переименования и рефакторинги.
     *
     * Также извлекает @System("...") аннотацию — на уровне класса (наследуется
     * всеми тестами) или на уровне конкретного теста (переопределяет класс).
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

        // Состояние для @System
        var currentClassSystem: String? = null
        var lastSeenSystem: String? = null
        var pendingTestSystem: String? = null

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
            pendingTestSystem = null
            lastSeenSystem = null
        }

        for (line in diffOutput.lines()) {
            // Новый файл
            if (line.startsWith("+++ b/")) {
                flushHunk()
                currentFile = line.removePrefix("+++ b/")
                currentClassSystem = null
                lastSeenSystem = null
                pendingTestSystem = null
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

                // Проверяем @System на этой строке
                val systemMatch = systemAnnotationRegex.find(content)
                if (systemMatch != null) {
                    lastSeenSystem = systemMatch.groupValues[1]
                }

                // Проверяем объявление класса
                if (classDeclarationRegex.containsMatchIn(content)) {
                    if (lastSeenSystem != null) {
                        currentClassSystem = lastSeenSystem
                        lastSeenSystem = null
                    }
                }

                // @Test fun foo() на одной строке
                if (hasTestAnnotationAndFun(content)) {
                    val funName = extractFunctionName(content)
                    if (funName != null && currentFile != null) {
                        val resolvedSystem = pendingTestSystem ?: lastSeenSystem ?: currentClassSystem
                        addedTests.add(NewTestInfo(funName, currentFile, resolvedSystem))
                        lastSeenSystem = null
                    }
                    addedPendingAnnotation = false
                    pendingTestSystem = null
                    continue
                }

                if (isTestAnnotation(content)) {
                    addedPendingAnnotation = true
                    if (lastSeenSystem != null) {
                        pendingTestSystem = lastSeenSystem
                        lastSeenSystem = null
                    }
                    continue
                }

                // Промежуточные аннотации (@DisplayName, @System и т.п.) — флаг сохраняется
                if (addedPendingAnnotation && content.startsWith("@")) {
                    if (systemMatch != null) {
                        pendingTestSystem = systemMatch.groupValues[1]
                        lastSeenSystem = null
                    }
                    continue
                }

                if (addedPendingAnnotation && containsFunDeclaration(content)) {
                    val funName = extractFunctionName(content)
                    if (funName != null && currentFile != null) {
                        val resolvedSystem = pendingTestSystem ?: currentClassSystem
                        addedTests.add(NewTestInfo(funName, currentFile, resolvedSystem))
                    }
                    addedPendingAnnotation = false
                    pendingTestSystem = null
                    continue
                }

                if (addedPendingAnnotation && content.isBlank()) {
                    continue
                }

                if (addedPendingAnnotation) {
                    addedPendingAnnotation = false
                    pendingTestSystem = null
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
            val contextContent = line.trim()

            // Проверяем @System на контекстных строках (неизменённый класс)
            val contextSystemMatch = systemAnnotationRegex.find(contextContent)
            if (contextSystemMatch != null) {
                lastSeenSystem = contextSystemMatch.groupValues[1]
            }

            // Проверяем объявление класса на контекстных строках
            if (classDeclarationRegex.containsMatchIn(contextContent)) {
                if (lastSeenSystem != null) {
                    currentClassSystem = lastSeenSystem
                    lastSeenSystem = null
                }
            }

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
