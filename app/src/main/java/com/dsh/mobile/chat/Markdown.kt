package com.dsh.mobile.chat

/**
 * A parsed markdown block.
 *
 * Deliberately block-level only. Agent output is mostly prose, headings and
 * fenced code; parsing inline emphasis eagerly is how markdown renderers grow a
 * grammar nobody asked for. Inline spans are resolved at draw time.
 */
sealed interface MarkdownBlock {
    data class Header(val level: Int, val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class Bullet(val text: String, val depth: Int) : MarkdownBlock
    data class Numbered(val number: String, val text: String, val depth: Int) : MarkdownBlock
    data class CodeBlock(val language: String, val code: String) : MarkdownBlock
    data object Divider : MarkdownBlock
}

/**
 * A small, predictable markdown parser for agent replies.
 *
 * Agent output is streamed and frequently UNTERMINATED - a code fence is often
 * open when the text is first parsed and only closes several deltas later. That
 * single fact drives the design: an unclosed fence is treated as code to the
 * end rather than being swallowed or rendered as literal backticks, so the user
 * watches code appear inside a code block instead of seeing a broken paragraph
 * that snaps into shape when the fence finally arrives.
 */
object Markdown {

    /** Longer inputs are clipped before parsing; a chat bubble is not a document. */
    const val MAX_INPUT_CHARS: Int = 200_000

    private val HEADER = Regex("^(#{1,6})\\s+(.*)$")
    private val BULLET = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val NUMBERED = Regex("^(\\s*)(\\d{1,9})[.)]\\s+(.*)$")
    private val FENCE = Regex("^\\s*```\\s*([^`]*)$")
    private val THEMATIC = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")

    fun parse(markdown: String): List<MarkdownBlock> {
        if (markdown.isEmpty()) return emptyList()
        val text = if (markdown.length > MAX_INPUT_CHARS) markdown.take(MAX_INPUT_CHARS) else markdown
        val lines = text.split('\n')
        val out = ArrayList<MarkdownBlock>()
        val paragraph = StringBuilder()
        var inFence = false
        var fenceLang = ""
        val code = StringBuilder()

        fun flushParagraph() {
            if (paragraph.isNotEmpty()) {
                out += MarkdownBlock.Paragraph(paragraph.toString().trim())
                paragraph.setLength(0)
            }
        }
        fun flushCode() {
            // Drop one trailing newline; the newline before a closing fence is
            // syntax, not content, and keeping it adds a blank line to every
            // code block.
            var body = code.toString()
            if (body.endsWith("\n")) body = body.dropLast(1)
            out += MarkdownBlock.CodeBlock(fenceLang, body)
            code.setLength(0)
        }

        for (line in lines) {
            val fence = FENCE.find(line)
            if (fence != null) {
                if (inFence) {
                    flushCode()
                    inFence = false
                    fenceLang = ""
                } else {
                    flushParagraph()
                    inFence = true
                    fenceLang = fence.groupValues[1].trim().substringBefore(' ')
                }
                continue
            }
            if (inFence) {
                code.append(line).append('\n')
                continue
            }
            when {
                line.isBlank() -> flushParagraph()
                THEMATIC.matches(line) -> { flushParagraph(); out += MarkdownBlock.Divider }
                else -> {
                    val header = HEADER.find(line)
                    val bullet = BULLET.find(line)
                    val numbered = NUMBERED.find(line)
                    when {
                        header != null -> {
                            flushParagraph()
                            out += MarkdownBlock.Header(
                                header.groupValues[1].length,
                                header.groupValues[2].trim(),
                            )
                        }
                        bullet != null -> {
                            flushParagraph()
                            // Two spaces per indent level, the common convention.
                            out += MarkdownBlock.Bullet(
                                bullet.groupValues[2].trim(),
                                indentDepth(bullet.groupValues[1]),
                            )
                        }
                        numbered != null -> {
                            flushParagraph()
                            out += MarkdownBlock.Numbered(
                                numbered.groupValues[2],
                                numbered.groupValues[3].trim(),
                                indentDepth(numbered.groupValues[1]),
                            )
                        }
                        else -> {
                            if (paragraph.isNotEmpty()) paragraph.append('\n')
                            paragraph.append(line)
                        }
                    }
                }
            }
        }
        // Streaming: an unterminated fence is still code.
        if (inFence) flushCode() else flushParagraph()
        return out
    }

    private fun indentDepth(whitespace: String): Int {
        var spaces = 0
        for (c in whitespace) spaces += if (c == '\t') 4 else 1
        return (spaces / 2).coerceAtMost(6)
    }

    /** Plain text of [blocks], for copy and for previews. */
    fun toPlainText(blocks: List<MarkdownBlock>): String = blocks.joinToString("\n\n") { block ->
        when (block) {
            is MarkdownBlock.Header -> block.text
            is MarkdownBlock.Paragraph -> block.text
            is MarkdownBlock.Bullet -> "  ".repeat(block.depth) + "- " + block.text
            is MarkdownBlock.Numbered -> "  ".repeat(block.depth) + block.number + ". " + block.text
            is MarkdownBlock.CodeBlock -> block.code
            MarkdownBlock.Divider -> "---"
        }
    }

    /**
     * Whether [markdown] currently ends inside an open code fence.
     *
     * The chat surface uses this to decide whether a streaming reply is
     * mid-code-block, which is exactly when a "still writing" affordance
     * belongs.
     */
    fun hasOpenFence(markdown: String): Boolean =
        markdown.lineSequence().count { FENCE.containsMatchIn(it) } % 2 == 1
}
