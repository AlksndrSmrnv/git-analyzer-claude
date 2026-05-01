package com.analyzer

data class NewTestInfo(
    val functionName: String,
    val filePath: String,
    val systemId: String? = null
)

class TestParser {

    private val testAnnotations = setOf("@Test", "@ParameterizedTest", "@RepeatedTest")
    private val systemAnnotationRegex = Regex("""@System\("([^"]+)"\)""")
    private val classDeclarationRegex = Regex("""\bclass\s+\w+|\bobject\s+\w+""")

    private data class ClassScope(val depth: Int, val systemId: String?)

    /**
     * Находит по-настоящему НОВЫЕ тесты в diff-выводе git.
     *
     * Алгоритм: собираем имена тестов из добавленных строк (+)
     * и имена тестов из удалённых строк (-). Новыми считаются только те
     * добавленные тесты, имя которых не встречается среди удалённых.
     * Это исключает переименования и рефакторинги.
     *
     * Сопоставление ведётся по имени функции, а не по счётчику,
     * что корректно работает при полном контексте файла (-U999999),
     * когда все изменения оказываются в одном hunk'е.
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
        val removedTestNames = mutableSetOf<String>()

        // Состояние для @System
        val classScopes = mutableListOf<ClassScope>()
        var braceDepth = 0
        var pendingClassDeclaration = false
        var pendingClassSystem: String? = null
        var lastSeenSystem: String? = null
        var pendingTestSystem: String? = null

        fun currentClassSystem(): String? = classScopes.lastOrNull()?.systemId

        fun popClosedClasses() {
            while (classScopes.isNotEmpty() && braceDepth < classScopes.last().depth) {
                classScopes.removeAt(classScopes.lastIndex)
            }
        }

        fun countCharOutsideStrings(content: String, target: Char): Int {
            var count = 0
            var inString = false
            var escaped = false
            for (ch in content) {
                if (escaped) {
                    escaped = false
                    continue
                }
                if (ch == '\\' && inString) {
                    escaped = true
                    continue
                }
                if (ch == '"') {
                    inString = !inString
                    continue
                }
                if (!inString && ch == target) count++
            }
            return count
        }

        fun updateBraceDepth(content: String) {
            val opens = countCharOutsideStrings(content, '{')
            val closes = countCharOutsideStrings(content, '}')
            val previousDepth = braceDepth
            braceDepth = (braceDepth + opens - closes).coerceAtLeast(0)
            if (pendingClassDeclaration && opens > 0) {
                classScopes.add(ClassScope(previousDepth + 1, pendingClassSystem))
                pendingClassDeclaration = false
                pendingClassSystem = null
            }
            popClosedClasses()
        }

        fun handleClassDeclaration(content: String, line: String, hasDiffPrefix: Boolean): Boolean {
            val isClassDeclaration = classDeclarationRegex.containsMatchIn(content)
                && !content.contains("companion object")
            if (!isClassDeclaration) return false

            val indentIndex = if (hasDiffPrefix) 1 else 0
            val isNested = braceDepth > 0 || line.getOrNull(indentIndex)?.isWhitespace() == true
            val resolvedSystem = if (!isNested) lastSeenSystem else lastSeenSystem ?: currentClassSystem()
            val opensClassBody = countCharOutsideStrings(content, '{') > 0
            if (opensClassBody) {
                classScopes.add(ClassScope(braceDepth + 1, resolvedSystem))
            } else {
                pendingClassDeclaration = true
                pendingClassSystem = resolvedSystem
            }
            lastSeenSystem = null
            addedPendingAnnotation = false
            pendingTestSystem = null
            updateBraceDepth(content)
            return true
        }

        fun flushHunk() {
            // Новыми считаются добавленные тесты, имя которых
            // не встречается среди удалённых (исключаем переименования/перемещения)
            for (test in addedTests) {
                if (test.functionName !in removedTestNames) {
                    results.add(test)
                }
            }
            addedTests.clear()
            removedTestNames.clear()
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
                classScopes.clear()
                braceDepth = 0
                pendingClassDeclaration = false
                pendingClassSystem = null
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

                // Проверяем объявление класса.
                // Вложенные классы (с отступом) без собственной @System
                // наследуют currentClassSystem от родителя.
                // companion object не считается новым классом-контейнером.
                if (handleClassDeclaration(content, line, hasDiffPrefix = true)) {
                    continue
                }

                // @Test fun foo() на одной строке
                if (hasTestAnnotationAndFun(content)) {
                    val funName = extractFunctionName(content)
                    if (funName != null && currentFile != null) {
                        val resolvedSystem = pendingTestSystem ?: lastSeenSystem ?: currentClassSystem()
                        addedTests.add(NewTestInfo(funName, currentFile, resolvedSystem))
                        lastSeenSystem = null
                    }
                    addedPendingAnnotation = false
                    pendingTestSystem = null
                    updateBraceDepth(content)
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
                        val resolvedSystem = pendingTestSystem ?: currentClassSystem()
                        addedTests.add(NewTestInfo(funName, currentFile, resolvedSystem))
                    }
                    addedPendingAnnotation = false
                    pendingTestSystem = null
                    updateBraceDepth(content)
                    continue
                }

                if (addedPendingAnnotation && content.isBlank()) {
                    continue
                }

                if (addedPendingAnnotation) {
                    addedPendingAnnotation = false
                    pendingTestSystem = null
                }

                // Сбрасываем lastSeenSystem на обычных строках кода
                // (не аннотация, не пустая строка), чтобы @System на поле/свойстве
                // не утекал к последующим тестам
                if (systemMatch == null && !content.startsWith("@") && content.isNotBlank()) {
                    lastSeenSystem = null
                }
                updateBraceDepth(content)
                continue
            }

            // === Удалённые строки (-) ===
            if (line.startsWith("-")) {
                val content = line.substring(1).trim()

                if (hasTestAnnotationAndFun(content)) {
                    val funName = extractFunctionName(content)
                    if (funName != null) removedTestNames.add(funName)
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
                    val funName = extractFunctionName(content)
                    if (funName != null) removedTestNames.add(funName)
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

            // Проверяем объявление класса на контекстных строках.
            // Вложенные классы (с отступом) без собственной @System
            // наследуют currentClassSystem от родителя.
            // companion object не считается новым классом-контейнером.
            val isContextClassDeclaration = classDeclarationRegex.containsMatchIn(contextContent)
                && !contextContent.contains("companion object")
            if (handleClassDeclaration(contextContent, line, hasDiffPrefix = true)) {
                continue
            }

            if (contextSystemMatch == null && !isContextClassDeclaration
                && !contextContent.startsWith("@") && contextContent.isNotBlank()) {
                lastSeenSystem = null
            }

            // Сбрасываем оба флага — аннотация уже существовала
            addedPendingAnnotation = false
            pendingTestSystem = null
            removedPendingAnnotation = false
            updateBraceDepth(contextContent)
        }

        // Последний hunk
        flushHunk()

        return results
    }

    /**
     * Парсит полное содержимое Kotlin-файла (не diff) и возвращает
     * отображение functionName -> systemId для всех тестов, у которых
     * есть @System (на уровне класса или метода). Тесты без @System
     * в результат не включаются.
     *
     * Используется для обогащения записей, у которых systemId == null,
     * когда @System был добавлен к классу в другом коммите.
     */
    fun extractSystemMapping(fileContent: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val classScopes = mutableListOf<ClassScope>()
        var braceDepth = 0
        var pendingClassDeclaration = false
        var pendingClassSystem: String? = null
        var lastSeenSystem: String? = null
        var pendingTestSystem: String? = null
        var pendingAnnotation = false

        fun currentClassSystem(): String? = classScopes.lastOrNull()?.systemId

        fun popClosedClasses() {
            while (classScopes.isNotEmpty() && braceDepth < classScopes.last().depth) {
                classScopes.removeAt(classScopes.lastIndex)
            }
        }

        fun countCharOutsideStrings(content: String, target: Char): Int {
            var count = 0
            var inString = false
            var escaped = false
            for (ch in content) {
                if (escaped) {
                    escaped = false
                    continue
                }
                if (ch == '\\' && inString) {
                    escaped = true
                    continue
                }
                if (ch == '"') {
                    inString = !inString
                    continue
                }
                if (!inString && ch == target) count++
            }
            return count
        }

        fun updateBraceDepth(content: String) {
            val opens = countCharOutsideStrings(content, '{')
            val closes = countCharOutsideStrings(content, '}')
            val previousDepth = braceDepth
            braceDepth = (braceDepth + opens - closes).coerceAtLeast(0)
            if (pendingClassDeclaration && opens > 0) {
                classScopes.add(ClassScope(previousDepth + 1, pendingClassSystem))
                pendingClassDeclaration = false
                pendingClassSystem = null
            }
            popClosedClasses()
        }

        for (line in fileContent.lines()) {
            val content = line.trim()

            val systemMatch = systemAnnotationRegex.find(content)
            if (systemMatch != null) {
                lastSeenSystem = systemMatch.groupValues[1]
            }

            if (classDeclarationRegex.containsMatchIn(content) && !content.contains("companion object")) {
                val isNested = braceDepth > 0 || line.firstOrNull()?.isWhitespace() == true
                val resolvedSystem = if (!isNested) lastSeenSystem else lastSeenSystem ?: currentClassSystem()
                if (countCharOutsideStrings(content, '{') > 0) {
                    classScopes.add(ClassScope(braceDepth + 1, resolvedSystem))
                } else {
                    pendingClassDeclaration = true
                    pendingClassSystem = resolvedSystem
                }
                lastSeenSystem = null
                pendingAnnotation = false
                pendingTestSystem = null
                updateBraceDepth(content)
                continue
            }

            // @Test fun foo() на одной строке
            if (hasTestAnnotationAndFun(content)) {
                val funName = extractFunctionName(content)
                if (funName != null) {
                    val resolved = pendingTestSystem ?: lastSeenSystem ?: currentClassSystem()
                    if (resolved != null) result[funName] = resolved
                }
                lastSeenSystem = null
                pendingAnnotation = false
                pendingTestSystem = null
                updateBraceDepth(content)
                continue
            }

            if (isTestAnnotation(content)) {
                pendingAnnotation = true
                if (lastSeenSystem != null) {
                    pendingTestSystem = lastSeenSystem
                    lastSeenSystem = null
                }
                continue
            }

            if (pendingAnnotation && content.startsWith("@")) {
                if (systemMatch != null) {
                    pendingTestSystem = systemMatch.groupValues[1]
                    lastSeenSystem = null
                }
                continue
            }

            if (pendingAnnotation && containsFunDeclaration(content)) {
                val funName = extractFunctionName(content)
                if (funName != null) {
                    val resolved = pendingTestSystem ?: currentClassSystem()
                    if (resolved != null) result[funName] = resolved
                }
                pendingAnnotation = false
                pendingTestSystem = null
                updateBraceDepth(content)
                continue
            }

            if (pendingAnnotation && content.isBlank()) continue

            if (pendingAnnotation) {
                pendingAnnotation = false
                pendingTestSystem = null
            }

            if (systemMatch == null && !content.startsWith("@") && content.isNotBlank()) {
                lastSeenSystem = null
            }
            updateBraceDepth(content)
        }

        return result
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
