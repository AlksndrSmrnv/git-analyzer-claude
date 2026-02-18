package com.analyzer

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
    @DisplayName("Counts renamed test as new when function name changes")
    fun countsRenamedTestAsNew() {
        val diff = """
+++ b/src/test/kotlin/MyTest.kt
@@ -10,4 +10,4 @@
-    @Test
-    fun oldTestName() {
-        assertEquals(1, 1)
-    }
+    @Test
+    fun newTestName() {
+        assertEquals(2, 2)
+    }
        """.trimIndent()

        val results = parser.findNewTests(diff)
        assertEquals(1, results.size)
        assertEquals("newTestName", results[0].functionName)
    }

    @Test
    @DisplayName("Does NOT count test rewrite when function name stays the same")
    fun doesNotCountRewriteWithSameName() {
        val diff = """
+++ b/src/test/kotlin/MyTest.kt
@@ -10,4 +10,4 @@
-    @Test
-    fun sameTestName() {
-        assertEquals(1, 1)
-    }
+    @Test
+    fun sameTestName() {
+        assertEquals(2, 2)
+    }
        """.trimIndent()

        val results = parser.findNewTests(diff)
        assertEquals(0, results.size)
    }

    @Test
    @DisplayName("Counts all truly new tests when removed test has different name")
    fun countsNetNewTests() {
        val diff = """
+++ b/src/test/kotlin/MyTest.kt
@@ -10,4 +10,8 @@
-    @Test
-    fun oldTest() {
-    }
+    @Test
+    fun replacementTest() {
+    }
+    @Test
+    fun brandNewTest() {
+    }
        """.trimIndent()

        val results = parser.findNewTests(diff)
        assertEquals(2, results.size)
        assertEquals("replacementTest", results[0].functionName)
        assertEquals("brandNewTest", results[1].functionName)
    }

    @Test
    @DisplayName("Does NOT count test body-only changes with re-added annotation")
    fun doesNotCountBodyChangeWithAnnotationRewrite() {
        val diff = """
+++ b/src/test/kotlin/MyTest.kt
@@ -10,5 +10,5 @@
-    @Test
-    @DisplayName("old name")
-    fun myTest() {
-        assertEquals(1, 1)
-    }
+    @Test
+    @DisplayName("new name")
+    fun myTest() {
+        assertEquals(2, 2)
+    }
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
    @DisplayName("Does NOT count tests from a purely renamed file (similarity 100%)")
    fun doesNotCountPureRename() {
        // With -M flag, a pure rename produces only headers, no +/- content lines
        val diff = """
diff --git a/src/test/kotlin/OldTest.kt b/src/test/kotlin/NewTest.kt
similarity index 100%
rename from src/test/kotlin/OldTest.kt
rename to src/test/kotlin/NewTest.kt
        """.trimIndent()

        val results = parser.findNewTests(diff)
        assertEquals(0, results.size)
    }

    @Test
    @DisplayName("Does NOT count existing tests from renamed file with minor edits")
    fun doesNotCountRenamedFileWithMinorEdits() {
        // With -M flag, git shows only the changed lines, not the whole file
        val diff = """
diff --git a/src/test/kotlin/OldTest.kt b/src/test/kotlin/NewTest.kt
similarity index 95%
rename from src/test/kotlin/OldTest.kt
rename to src/test/kotlin/NewTest.kt
index abc1234..def5678 100644
--- a/src/test/kotlin/OldTest.kt
+++ b/src/test/kotlin/NewTest.kt
@@ -1,4 +1,4 @@
-package old.pkg
+package new.pkg

 class NewTest {
     @Test
        """.trimIndent()

        val results = parser.findNewTests(diff)
        assertEquals(0, results.size)
    }

    @Test
    @DisplayName("Counts only truly new test added in a renamed file")
    fun countsNewTestInRenamedFile() {
        // File renamed + one new test actually added
        val diff = """
diff --git a/src/test/kotlin/OldTest.kt b/src/test/kotlin/NewTest.kt
similarity index 80%
rename from src/test/kotlin/OldTest.kt
rename to src/test/kotlin/NewTest.kt
index abc1234..def5678 100644
--- a/src/test/kotlin/OldTest.kt
+++ b/src/test/kotlin/NewTest.kt
@@ -1,4 +1,4 @@
-package old.pkg
+package new.pkg
@@ -10,0 +10,4 @@
+    @Test
+    fun brandNewTestInRenamedFile() {
+        assertTrue(true)
+    }
        """.trimIndent()

        val results = parser.findNewTests(diff)
        assertEquals(1, results.size)
        assertEquals("brandNewTestInRenamedFile", results[0].functionName)
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

    @Test
    @DisplayName("Tests in nested class inherit @System from parent class (added lines)")
    fun nestedClassInheritsSystemFromParentAddedLines() {
        val diff = """
+++ b/src/test/kotlin/MyTest.kt
@@ -0,0 +1,10 @@
+@System("CI01337")
+class MyTest {
+    @Test
+    fun outerTest() {}
+    inner class Inner {
+        @Test
+        fun innerTest() {}
+    }
+}
        """.trimIndent()

        val results = parser.findNewTests(diff)
        assertEquals(2, results.size)
        assertEquals("CI01337", results[0].systemId)
        assertEquals("CI01337", results[1].systemId)
    }

    @Test
    @DisplayName("Tests in nested class inherit @System from parent class (context lines)")
    fun nestedClassInheritsSystemFromParentContextLines() {
        val diff = """
+++ b/src/test/kotlin/MyTest.kt
@@ -1,8 +1,12 @@
 @System("CI01337")
 class MyTest {
     @Nested
     inner class Inner {
+        @Test
+        fun newInnerTest() {}
     }
 }
        """.trimIndent()

        val results = parser.findNewTests(diff)
        assertEquals(1, results.size)
        assertEquals("newInnerTest", results[0].functionName)
        assertEquals("CI01337", results[0].systemId)
    }

    @Test
    @DisplayName("Nested class with own @System overrides parent @System")
    fun nestedClassOwnSystemOverridesParent() {
        val diff = """
+++ b/src/test/kotlin/MyTest.kt
@@ -0,0 +1,12 @@
+@System("CI01337")
+class MyTest {
+    @Test
+    fun outerTest() {}
+    @System("CI02000")
+    inner class Inner {
+        @Test
+        fun innerTest() {}
+    }
+}
        """.trimIndent()

        val results = parser.findNewTests(diff)
        assertEquals(2, results.size)
        assertEquals("CI01337", results[0].systemId)
        assertEquals("CI02000", results[1].systemId)
    }

    @Test
    @DisplayName("Top-level class without @System correctly resets system to null")
    fun topLevelClassWithoutSystemResetsToNull() {
        val diff = """
+++ b/src/test/kotlin/MyTest.kt
@@ -0,0 +1,8 @@
+@System("CI01337")
+class FirstTest {
+    @Test
+    fun test1() {}
+}
+class SecondTest {
+    @Test
+    fun test2() {}
+}
        """.trimIndent()

        val results = parser.findNewTests(diff)
        assertEquals(2, results.size)
        assertEquals("CI01337", results[0].systemId)
        assertNull(results[1].systemId)
    }
}
