package com.analyzer

data class NewTestInfo(
    val functionName: String,
    val filePath: String,
    val systemId: String? = null,
    val date: String? = null
)

class TestParser {

    private val testAnnotations = setOf("@Test", "@ParameterizedTest", "@RepeatedTest")
    private val systemAnnotationRegex = Regex("""@System\("([^"]+)"\)""")
    private val systemAnnotationTokenRegex = Regex("""@System\s*\(""")
    private val classDeclarationRegex = Regex("""\bclass\s+\w+|\bobject\s+\w+""")

    private data class ClassScope(val depth: Int, val systemId: String?)
    private data class RemovedTestCandidate(val functionName: String, val filePath: String?)
    private data class TestLocationKey(val functionName: String, val filePath: String)

    /**
     * Находит по-настоящему НОВЫЕ тесты в diff-выводе git.
     *
     * Алгоритм: собираем тесты из добавленных и удалённых строк всего commit diff.
     * Затем сопоставляем их как мультимножества: сначала по имени и файлу,
     * затем по имени между файлами. Так изменения и переносы не считаются
     * новыми тестами, а одно удаление подавляет ровно одно добавление.
     *
     * Также извлекает @System("...") аннотацию — на уровне класса (наследуется
     * всеми тестами) или на уровне конкретного теста (переопределяет класс).
     */
    fun findNewTests(diffOutput: String): List<NewTestInfo> {
        var currentFile: String? = null
        var currentOldFile: String? = null

        // Состояние для добавленных строк (+)
        var addedPendingAnnotation = false
        val addedTests = mutableListOf<NewTestInfo>()

        // Состояние для удалённых строк (-)
        var removedPendingAnnotation = false
        val removedTests = mutableListOf<RemovedTestCandidate>()

        // Состояние для @System
        val classScopes = mutableListOf<ClassScope>()
        var braceDepth = 0
        var pendingClassDeclaration = false
        var pendingClassSystem: String? = null
        var lastSeenSystem: String? = null
        var pendingTestSystem: String? = null
        val addedSanitizer = KotlinCodeSanitizer()
        val removedSanitizer = KotlinCodeSanitizer()

        fun currentClassSystem(): String? = classScopes.lastOrNull()?.systemId

        fun popClosedClasses() {
            while (classScopes.isNotEmpty() && braceDepth < classScopes.last().depth) {
                classScopes.removeAt(classScopes.lastIndex)
            }
        }

        fun updateBraceDepth(content: String) {
            val opens = content.count { it == '{' }
            val closes = content.count { it == '}' }
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
            val opensClassBody = content.contains('{')
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

        fun resetPendingState() {
            addedPendingAnnotation = false
            removedPendingAnnotation = false
            pendingTestSystem = null
            lastSeenSystem = null
        }

        for (line in diffOutput.lines()) {
            if (line.startsWith("diff --git ")) {
                resetPendingState()
                currentFile = null
                currentOldFile = null
                addedSanitizer.reset()
                removedSanitizer.reset()
                continue
            }

            // Старый путь нужен, чтобы сопоставлять удаления из удалённых файлов.
            if (line.startsWith("--- a/") || line == "--- /dev/null") {
                currentOldFile = parseDiffPath(line.removePrefix("--- "), "a/")
                continue
            }

            // Новый файл. /dev/null означает, что файл был удалён.
            if (line.startsWith("+++ b/") || line == "+++ /dev/null") {
                resetPendingState()
                currentFile = parseDiffPath(line.removePrefix("+++ "), "b/")
                classScopes.clear()
                braceDepth = 0
                pendingClassDeclaration = false
                pendingClassSystem = null
                lastSeenSystem = null
                pendingTestSystem = null
                addedSanitizer.reset()
                removedSanitizer.reset()
                continue
            }

            // Пропускаем метаданные diff
            if (line.startsWith("index ")) {
                continue
            }

            // Новый hunk — сбрасываем только локальное состояние аннотаций.
            if (line.startsWith("@@")) {
                resetPendingState()
                continue
            }

            // === Добавленные строки (+) ===
            if (line.startsWith("+")) {
                val source = line.substring(1)
                val sanitized = addedSanitizer.sanitizeLine(source)
                val content = sanitized.trim()

                // Проверяем @System на этой строке
                val systemId = extractSystemId(source, sanitized)
                if (systemId != null) {
                    lastSeenSystem = systemId
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
                    if (systemId != null) {
                        pendingTestSystem = systemId
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
                if (systemId == null && !content.startsWith("@") && content.isNotBlank()) {
                    lastSeenSystem = null
                }
                updateBraceDepth(content)
                continue
            }

            // === Удалённые строки (-) ===
            if (line.startsWith("-")) {
                val source = line.substring(1)
                val content = removedSanitizer.sanitizeLine(source).trim()

                if (hasTestAnnotationAndFun(content)) {
                    val funName = extractFunctionName(content)
                    if (funName != null) {
                        removedTests.add(RemovedTestCandidate(funName, currentOldFile ?: currentFile))
                    }
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
                    if (funName != null) {
                        removedTests.add(RemovedTestCandidate(funName, currentOldFile ?: currentFile))
                    }
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
            val contextSource = if (line.startsWith(" ")) line.substring(1) else line
            val sanitizedContext = addedSanitizer.sanitizeLine(contextSource)
            removedSanitizer.sanitizeLine(contextSource)
            val contextContent = sanitizedContext.trim()

            // Проверяем @System на контекстных строках (неизменённый класс)
            val contextSystemId = extractSystemId(contextSource, sanitizedContext)
            if (contextSystemId != null) {
                lastSeenSystem = contextSystemId
            }

            // Проверяем объявление класса на контекстных строках.
            // Вложенные классы (с отступом) без собственной @System
            // наследуют currentClassSystem от родителя.
            // companion object не считается новым классом-контейнером.
            val isContextClassDeclaration = handleClassDeclaration(contextContent, line, hasDiffPrefix = true)
            if (isContextClassDeclaration) {
                continue
            }

            // Пустые строки и комментарии не разрывают последовательность
            // аннотаций перед добавленной/удалённой функцией.
            if (contextContent.isBlank()) {
                updateBraceDepth(contextContent)
                continue
            }

            if (contextSystemId == null && !isContextClassDeclaration
                && !contextContent.startsWith("@") && contextContent.isNotBlank()) {
                lastSeenSystem = null
            }

            // Сбрасываем оба флага — аннотация уже существовала
            addedPendingAnnotation = false
            pendingTestSystem = null
            removedPendingAnnotation = false
            updateBraceDepth(contextContent)
        }

        return reconcileAddedAndRemovedTests(addedTests, removedTests)
    }

    /**
     * Сопоставляет изменения сначала в том же файле, затем во всём коммите.
     * Счётчики важны: одно удаление подавляет ровно одно добавление.
     */
    private fun reconcileAddedAndRemovedTests(
        addedTests: List<NewTestInfo>,
        removedTests: List<RemovedTestCandidate>
    ): List<NewTestInfo> {
        val remainingByLocation = removedTests
            .mapNotNull { removed ->
                removed.filePath?.let { TestLocationKey(removed.functionName, it) }
            }
            .groupingBy { it }
            .eachCount()
            .toMutableMap()
        val remainingByName = removedTests
            .groupingBy { it.functionName }
            .eachCount()
            .toMutableMap()

        fun <K> consume(counter: MutableMap<K, Int>, key: K): Boolean {
            val count = counter[key] ?: return false
            if (count == 1) counter.remove(key) else counter[key] = count - 1
            return true
        }

        val candidatesForCommitWideMatch = mutableListOf<NewTestInfo>()
        for (added in addedTests) {
            val location = TestLocationKey(added.functionName, added.filePath)
            if (consume(remainingByLocation, location)) {
                consume(remainingByName, added.functionName)
            } else {
                candidatesForCommitWideMatch.add(added)
            }
        }

        return candidatesForCommitWideMatch.filter { added ->
            !consume(remainingByName, added.functionName)
        }
    }

    private fun parseDiffPath(rawPath: String, sidePrefix: String): String? {
        if (rawPath == "/dev/null") return null
        return rawPath.removePrefix(sidePrefix)
    }

    /**
     * Находит @System только если сама аннотация находится в Kotlin-коде.
     * Значение читается из исходной строки, потому что sanitizer скрывает
     * содержимое строковых литералов, сохраняя их позиции.
     */
    private fun extractSystemId(source: String, sanitized: String): String? {
        val annotation = systemAnnotationTokenRegex.find(sanitized) ?: return null
        val match = systemAnnotationRegex.find(source, annotation.range.first) ?: return null
        return match.takeIf { it.range.first == annotation.range.first }?.groupValues?.get(1)
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
        val sanitizer = KotlinCodeSanitizer()

        fun currentClassSystem(): String? = classScopes.lastOrNull()?.systemId

        fun popClosedClasses() {
            while (classScopes.isNotEmpty() && braceDepth < classScopes.last().depth) {
                classScopes.removeAt(classScopes.lastIndex)
            }
        }

        fun updateBraceDepth(content: String) {
            val opens = content.count { it == '{' }
            val closes = content.count { it == '}' }
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
            val sanitized = sanitizer.sanitizeLine(line)
            val content = sanitized.trim()

            val systemId = extractSystemId(line, sanitized)
            if (systemId != null) {
                lastSeenSystem = systemId
            }

            if (classDeclarationRegex.containsMatchIn(content) && !content.contains("companion object")) {
                val isNested = braceDepth > 0 || line.firstOrNull()?.isWhitespace() == true
                val resolvedSystem = if (!isNested) lastSeenSystem else lastSeenSystem ?: currentClassSystem()
                if (content.contains('{')) {
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
                if (systemId != null) {
                    pendingTestSystem = systemId
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

            if (systemId == null && !content.startsWith("@") && content.isNotBlank()) {
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

    /**
     * Имя тестовой функции. Поддерживает:
     * - обычные имена: `fun foo()`;
     * - backtick-имена: `fun `should work`()`;
     * - extension-функции: `fun String.foo()` (имя без receiver-типа);
     * - generic-функции: `fun <T> foo()` и `fun foo<T>()`.
     *
     * Ограничение: extension-функции с generic-типом receiver
     * (`fun List<Int>.foo()`) не поддерживаются — парсер возьмёт имя типа.
     */
    private val functionNameRegex = Regex(
        """fun\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?(`[^`]+`|\w+)\s*[(<]"""
    )

    private fun extractFunctionName(content: String): String? {
        return functionNameRegex.find(content)?.groupValues?.get(1)
    }
}
