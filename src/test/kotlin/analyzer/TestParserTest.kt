package analyzer

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class TestParserTest {

    private val parser = TestParser()

    @Test
    @DisplayName("Detects new test when both @Test and fun are added lines")
    fun detectsNewTest() {
        val diff = """
+++ b/src/test/kotlin/MyTest.kt
@@ -10,0 +11,4 @@
+    @Test
+    fun newTestFunction() {
+        assertEquals(1, 1)
+    }
        """.trimIndent()

        val results = parser.findNewTests(diff)
        assertEquals(1, results.size)
        assertEquals("newTestFunction", results[0].functionName)
        assertEquals("src/test/kotlin/MyTest.kt", results[0].filePath)
    }

    @Test
    @DisplayName("Does NOT count modified test where @Test is a context line")
    fun doesNotCountModifiedTest() {
        val diff = """
+++ b/src/test/kotlin/MyTest.kt
@@ -10,4 +10,4 @@
     @Test
-    fun oldName() {
+    fun renamedFunction() {
         assertEquals(1, 1)
     }
        """.trimIndent()

        val results = parser.findNewTests(diff)
        assertEquals(0, results.size)
    }

    @Test
    @DisplayName("Handles @Test and fun on the same added line")
    fun handlesInlineAnnotation() {
        val diff = """
+++ b/src/test/kotlin/MyTest.kt
@@ -0,0 +1,3 @@
+class MyTest {
+    @Test fun inlineTest() { assertEquals(1, 1) }
+}
        """.trimIndent()

        val results = parser.findNewTests(diff)
        assertEquals(1, results.size)
        assertEquals("inlineTest", results[0].functionName)
    }

    @Test
    @DisplayName("Handles intermediate annotations between @Test and fun")
    fun handlesIntermediateAnnotations() {
        val diff = """
+++ b/src/test/kotlin/MyTest.kt
@@ -10,0 +11,5 @@
+    @Test
+    @DisplayName("should work")
+    @Timeout(5)
+    fun annotatedTest() {
+    }
        """.trimIndent()

        val results = parser.findNewTests(diff)
        assertEquals(1, results.size)
        assertEquals("annotatedTest", results[0].functionName)
    }

    @Test
    @DisplayName("Counts multiple new tests in one diff")
    fun countsMultipleNewTests() {
        val diff = """
+++ b/src/test/kotlin/MyTest.kt
@@ -0,0 +1,10 @@
+    @Test
+    fun firstTest() {
+    }
+
+    @Test
+    fun secondTest() {
+    }
        """.trimIndent()

        val results = parser.findNewTests(diff)
        assertEquals(2, results.size)
        assertEquals("firstTest", results[0].functionName)
        assertEquals("secondTest", results[1].functionName)
    }

    @Test
    @DisplayName("Handles backtick function names")
    fun handlesBacktickNames() {
        val diff = """
+++ b/src/test/kotlin/MyTest.kt
@@ -0,0 +1,4 @@
+    @Test
+    fun `should handle special case`() {
+        assertTrue(true)
+    }
        """.trimIndent()

        val results = parser.findNewTests(diff)
        assertEquals(1, results.size)
        assertEquals("`should handle special case`", results[0].functionName)
    }

    @Test
    @DisplayName("Returns empty for diff with no test additions")
    fun returnsEmptyForNoTests() {
        val diff = """
+++ b/src/main/kotlin/Service.kt
@@ -5,3 +5,3 @@
-    val name = "old"
+    val name = "new"
        """.trimIndent()

        val results = parser.findNewTests(diff)
        assertEquals(0, results.size)
    }

    @Test
    @DisplayName("Resets state at hunk boundaries")
    fun resetsAtHunkBoundaries() {
        val diff = """
+++ b/src/test/kotlin/MyTest.kt
@@ -10,0 +11,1 @@
+    @Test
@@ -30,0 +32,3 @@
+    fun someFunction() {
+        // not a test - @Test was in another hunk
+    }
        """.trimIndent()

        val results = parser.findNewTests(diff)
        assertEquals(0, results.size)
    }

    @Test
    @DisplayName("Handles @ParameterizedTest annotation")
    fun handlesParameterizedTest() {
        val diff = """
+++ b/src/test/kotlin/MyTest.kt
@@ -0,0 +1,4 @@
+    @ParameterizedTest
+    @ValueSource(ints = [1, 2, 3])
+    fun paramTest(value: Int) {
+    }
        """.trimIndent()

        val results = parser.findNewTests(diff)
        assertEquals(1, results.size)
        assertEquals("paramTest", results[0].functionName)
    }

    @Test
    @DisplayName("Handles @RepeatedTest annotation")
    fun handlesRepeatedTest() {
        val diff = """
+++ b/src/test/kotlin/MyTest.kt
@@ -0,0 +1,3 @@
+    @RepeatedTest(3)
+    fun repeatedTest() {
+    }
        """.trimIndent()

        val results = parser.findNewTests(diff)
        assertEquals(1, results.size)
        assertEquals("repeatedTest", results[0].functionName)
    }

    @Test
    @DisplayName("Does not count when only function body changes")
    fun doesNotCountBodyChanges() {
        val diff = """
+++ b/src/test/kotlin/MyTest.kt
@@ -10,5 +10,5 @@
     @Test
     fun existingTest() {
-        assertEquals(1, 1)
+        assertEquals(2, 2)
     }
        """.trimIndent()

        val results = parser.findNewTests(diff)
        assertEquals(0, results.size)
    }

    @Test
    @DisplayName("Does NOT count @TestFactory as @Test (inline annotation)")
    fun doesNotCountTestFactoryInline() {
        val diff = """
+++ b/src/test/kotlin/MyTest.kt
@@ -0,0 +1,3 @@
+    @TestFactory fun createDynamicTests(): List<DynamicTest> {
+        return listOf()
+    }
        """.trimIndent()

        val results = parser.findNewTests(diff)
        assertEquals(0, results.size)
    }

    @Test
    @DisplayName("Does NOT count @TestFactory as @Test (multi-line annotation)")
    fun doesNotCountTestFactoryMultiLine() {
        val diff = """
+++ b/src/test/kotlin/MyTest.kt
@@ -0,0 +1,4 @@
+    @TestFactory
+    fun createDynamicTests(): List<DynamicTest> {
+        return listOf()
+    }
        """.trimIndent()

        val results = parser.findNewTests(diff)
        assertEquals(0, results.size)
    }

    @Test
    @DisplayName("Does NOT count @TestTemplate as @Test")
    fun doesNotCountTestTemplate() {
        val diff = """
+++ b/src/test/kotlin/MyTest.kt
@@ -0,0 +1,3 @@
+    @TestTemplate fun templateTest() {
+        assertTrue(true)
+    }
        """.trimIndent()

        val results = parser.findNewTests(diff)
        assertEquals(0, results.size)
    }

    @Test
    @DisplayName("Does NOT count @TestMethodOrder as @Test")
    fun doesNotCountTestMethodOrder() {
        val diff = """
+++ b/src/test/kotlin/MyTest.kt
@@ -0,0 +1,1 @@
+    @TestMethodOrder(MethodOrderer.OrderAnnotation::class)
        """.trimIndent()

        val results = parser.findNewTests(diff)
        assertEquals(0, results.size)
    }

    @Test
    @DisplayName("Detects new test with 'internal' modifier")
    fun detectsInternalFun() {
        val diff = """
+++ b/src/test/kotlin/MyTest.kt
@@ -0,0 +1,4 @@
+    @Test
+    internal fun myInternalTest() {
+        assertTrue(true)
+    }
        """.trimIndent()

        val results = parser.findNewTests(diff)
        assertEquals(1, results.size)
        assertEquals("myInternalTest", results[0].functionName)
    }

    @Test
    @DisplayName("Detects new test with 'suspend' modifier")
    fun detectsSuspendFun() {
        val diff = """
+++ b/src/test/kotlin/MyTest.kt
@@ -0,0 +1,4 @@
+    @Test
+    suspend fun mySuspendTest() {
+        assertTrue(true)
+    }
        """.trimIndent()

        val results = parser.findNewTests(diff)
        assertEquals(1, results.size)
        assertEquals("mySuspendTest", results[0].functionName)
    }

    @Test
    @DisplayName("Detects new test with 'override' modifier")
    fun detectsOverrideFun() {
        val diff = """
+++ b/src/test/kotlin/MyTest.kt
@@ -0,0 +1,4 @@
+    @Test
+    override fun overriddenTest() {
+        assertTrue(true)
+    }
        """.trimIndent()

        val results = parser.findNewTests(diff)
        assertEquals(1, results.size)
        assertEquals("overriddenTest", results[0].functionName)
    }

    @Test
    @DisplayName("Detects new test with 'open' modifier")
    fun detectsOpenFun() {
        val diff = """
+++ b/src/test/kotlin/MyTest.kt
@@ -0,0 +1,4 @@
+    @Test
+    open fun openTest() {
+        assertTrue(true)
+    }
        """.trimIndent()

        val results = parser.findNewTests(diff)
        assertEquals(1, results.size)
        assertEquals("openTest", results[0].functionName)
    }

    @Test
    @DisplayName("Detects new test with multiple modifiers")
    fun detectsMultipleModifiers() {
        val diff = """
+++ b/src/test/kotlin/MyTest.kt
@@ -0,0 +1,4 @@
+    @Test
+    protected open fun protectedOpenTest() {
+        assertTrue(true)
+    }
        """.trimIndent()

        val results = parser.findNewTests(diff)
        assertEquals(1, results.size)
        assertEquals("protectedOpenTest", results[0].functionName)
    }

    @Test
    @DisplayName("Detects test when @DisplayName value contains 'fun' substring")
    fun detectsTestWithFunInDisplayName() {
        val diff = """
+++ b/src/test/kotlin/MyTest.kt
@@ -0,0 +1,5 @@
+    @Test
+    @DisplayName("Test fun behavior")
+    fun myTest() {
+        assertTrue(true)
+    }
        """.trimIndent()

        val results = parser.findNewTests(diff)
        assertEquals(1, results.size)
        assertEquals("myTest", results[0].functionName)
    }

    @Test
    @DisplayName("Detects test when intermediate annotation contains 'fun' in various positions")
    fun detectsTestWithFunInAnnotationValue() {
        val diff = """
+++ b/src/test/kotlin/MyTest.kt
@@ -0,0 +1,5 @@
+    @Test
+    @Tag("fun stuff")
+    fun taggedTest() {
+        assertTrue(true)
+    }
        """.trimIndent()

        val results = parser.findNewTests(diff)
        assertEquals(1, results.size)
        assertEquals("taggedTest", results[0].functionName)
    }

    @Test
    @DisplayName("Tracks correct file paths for multiple files in one diff")
    fun tracksFilePaths() {
        val diff = """
+++ b/src/test/kotlin/FirstTest.kt
@@ -0,0 +1,4 @@
+    @Test
+    fun testInFirst() {
+    }
+++ b/src/test/kotlin/SecondTest.kt
@@ -0,0 +1,4 @@
+    @Test
+    fun testInSecond() {
+    }
        """.trimIndent()

        val results = parser.findNewTests(diff)
        assertEquals(2, results.size)
        assertEquals("src/test/kotlin/FirstTest.kt", results[0].filePath)
        assertEquals("src/test/kotlin/SecondTest.kt", results[1].filePath)
    }
}
