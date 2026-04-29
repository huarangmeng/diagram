package com.hrm.diagram.parser.mermaid

internal object MermaidTextSanitizer {
    fun toPlain(text: String): String {
        var out = text
            .replace("<br/>", "\n")
            .replace("<br />", "\n")

        // Mermaid requirement/C4 docs allow quoted markdown text. We render a minimal subset as
        // plain text to stay cross-platform and avoid rich text layout dependencies.
        out = stripMarkdownLinks(out)
        out = out.replace("`", "")
        out = out.replace("**", "")
        out = out.replace("__", "")
        out = out.replace("*", "")
        return stripSingleUnderscoreEmphasis(out)
    }

    private fun stripMarkdownLinks(text: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            if (text[i] == '[') {
                val closeBracket = text.indexOf(']', startIndex = i + 1)
                if (closeBracket > i && closeBracket + 1 < text.length && text[closeBracket + 1] == '(') {
                    val closeParen = text.indexOf(')', startIndex = closeBracket + 2)
                    if (closeParen > closeBracket + 1) {
                        out.append(text, i + 1, closeBracket)
                        i = closeParen + 1
                        continue
                    }
                }
            }
            out.append(text[i])
            i++
        }
        return out.toString()
    }

    private fun stripSingleUnderscoreEmphasis(text: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            if (text[i] == '_' && isBoundaryBefore(text, i)) {
                val close = text.indexOf('_', startIndex = i + 1)
                if (close > i + 1 && isBoundaryAfter(text, close)) {
                    out.append(text, i + 1, close)
                    i = close + 1
                    continue
                }
            }
            out.append(text[i])
            i++
        }
        return out.toString()
    }

    private fun isBoundaryBefore(text: String, index: Int): Boolean =
        index == 0 || text[index - 1].isWhitespace() || text[index - 1] in "(["

    private fun isBoundaryAfter(text: String, index: Int): Boolean =
        index == text.lastIndex || text[index + 1].isWhitespace() || text[index + 1] in ").,!?:;]"
}
