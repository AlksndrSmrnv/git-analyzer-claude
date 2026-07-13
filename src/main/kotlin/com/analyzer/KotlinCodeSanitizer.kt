package com.analyzer

/**
 * Stateful line-by-line sanitizer for the Kotlin lexical constructs that can
 * contain test-looking text. Non-code characters are replaced with spaces so
 * indexes in the sanitized line still match indexes in the original source.
 */
internal class KotlinCodeSanitizer {

    private enum class Mode {
        CODE,
        BLOCK_COMMENT,
        RAW_STRING,
        REGULAR_STRING,
        CHAR_LITERAL,
        BACKTICK_IDENTIFIER
    }

    private var mode = Mode.CODE
    private var blockCommentDepth = 0

    fun reset() {
        mode = Mode.CODE
        blockCommentDepth = 0
    }

    fun sanitizeLine(source: String): String {
        val sanitized = CharArray(source.length) { ' ' }
        var index = 0

        while (index < source.length) {
            when (mode) {
                Mode.CODE -> when {
                    source.startsWith("//", index) -> {
                        index = source.length
                    }

                    source.startsWith("/*", index) -> {
                        mode = Mode.BLOCK_COMMENT
                        blockCommentDepth = 1
                        index += 2
                    }

                    source.startsWith("\"\"\"", index) -> {
                        mode = Mode.RAW_STRING
                        index += 3
                    }

                    source[index] == '"' -> {
                        mode = Mode.REGULAR_STRING
                        index++
                    }

                    source[index] == '\'' -> {
                        mode = Mode.CHAR_LITERAL
                        index++
                    }

                    source[index] == '`' -> {
                        sanitized[index] = source[index]
                        mode = Mode.BACKTICK_IDENTIFIER
                        index++
                    }

                    else -> {
                        sanitized[index] = source[index]
                        index++
                    }
                }

                Mode.BLOCK_COMMENT -> when {
                    source.startsWith("/*", index) -> {
                        blockCommentDepth++
                        index += 2
                    }

                    source.startsWith("*/", index) -> {
                        blockCommentDepth--
                        index += 2
                        if (blockCommentDepth == 0) mode = Mode.CODE
                    }

                    else -> index++
                }

                Mode.RAW_STRING -> {
                    if (source.startsWith("\"\"\"", index)) {
                        mode = Mode.CODE
                        index += 3
                    } else {
                        index++
                    }
                }

                Mode.REGULAR_STRING -> when {
                    source[index] == '\\' -> index = (index + 2).coerceAtMost(source.length)
                    source[index] == '"' -> {
                        mode = Mode.CODE
                        index++
                    }

                    else -> index++
                }

                Mode.CHAR_LITERAL -> when {
                    source[index] == '\\' -> index = (index + 2).coerceAtMost(source.length)
                    source[index] == '\'' -> {
                        mode = Mode.CODE
                        index++
                    }

                    else -> index++
                }

                // A backtick identifier is code, not a literal: its content is
                // kept verbatim so the function-name regex still sees it, and
                // quotes or comment markers inside it must not change state
                // (fun `doesn't fail`()). Backticks have no escape sequences.
                Mode.BACKTICK_IDENTIFIER -> {
                    sanitized[index] = source[index]
                    if (source[index] == '`') mode = Mode.CODE
                    index++
                }
            }
        }

        // Regular strings, chars and backtick identifiers cannot legally span
        // Kotlin source lines. Resetting here also prevents one malformed line
        // from hiding the rest of a diff. Block comments and raw strings
        // deliberately remain stateful.
        if (mode == Mode.REGULAR_STRING || mode == Mode.CHAR_LITERAL || mode == Mode.BACKTICK_IDENTIFIER) {
            mode = Mode.CODE
        }

        return String(sanitized)
    }
}
