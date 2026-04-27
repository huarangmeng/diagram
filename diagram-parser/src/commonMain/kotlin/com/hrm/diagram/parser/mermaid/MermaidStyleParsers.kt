package com.hrm.diagram.parser.mermaid

import com.hrm.diagram.core.ir.ArgbColor
import com.hrm.diagram.core.ir.Diagnostic
import com.hrm.diagram.core.ir.Severity

/**
 * Core parsing/normalization utilities for Mermaid styling.
 *
 * Scope (requested): themeVariables (frontmatter config) + classDef.
 *
 * Design:
 * - String-based parsing (no reliance on Mermaid lexer tokenization), because Mermaid style keys
 *   contain '-' and values contain '#', which are not uniformly tokenized across diagram modes.
 * - Never throws on user input; returns diagnostics instead.
 */
object MermaidStyleParsers {

    // ---- Public entry points ----

    /**
     * Parse Mermaid YAML frontmatter (only the subset needed for theming).
     *
     * Expected shape (subset):
     * ---
     * config:
     *   theme: 'base'
     *   themeVariables:
     *     primaryColor: '#BB2528'
     *     fontFamily: 'trebuchet ms, verdana, arial'
     * ---
     *
     * If the block isn't a valid frontmatter config, returns null (no diagnostics).
     */
    fun parseFrontmatterThemeConfig(frontmatter: String): ParseThemeConfigResult? {
        val lines = frontmatter
            .lines()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }
        if (lines.isEmpty()) return null
        if (lines.first() != "---") return null
        if (lines.last() != "---") return null

        // Extremely small YAML subset parser: only handles `key: value` and indentation blocks.
        // This is intentionally limited; unsupported syntax should be ignored gracefully.
        var inConfig = false
        var inThemeVars = false
        var configIndent = -1
        var themeVarsIndent = -1

        var themeNameRaw: String? = null
        val themeVars = LinkedHashMap<String, String>()

        for (raw in lines.drop(1).dropLast(1)) {
            val indent = raw.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) 0 else it }
            val line = raw.trim()
            if (line.startsWith("#")) continue

            if (line == "config:" && (configIndent < 0 || indent <= configIndent)) {
                inConfig = true
                inThemeVars = false
                configIndent = indent
                continue
            }
            if (!inConfig) continue

            if (line == "themeVariables:" && indent > configIndent) {
                inThemeVars = true
                themeVarsIndent = indent
                continue
            }

            val kv = splitYamlKeyValue(line) ?: continue
            val (k, vRaw) = kv
            val v = unquoteYamlScalar(vRaw)

            if (!inThemeVars && indent > configIndent) {
                if (k == "theme") themeNameRaw = v
                continue
            }

            if (inThemeVars && indent > themeVarsIndent) {
                themeVars[k] = v
            } else if (inThemeVars && indent <= themeVarsIndent) {
                // End of themeVariables block.
                inThemeVars = false
                if (k == "theme") themeNameRaw = v
            }
        }

        val diags = ArrayList<Diagnostic>()
        val theme = themeNameRaw?.let { parseThemeName(it, diags) }
        val tokens = if (themeVars.isEmpty()) null else parseThemeTokens(themeVars, diags)
        if (theme == null && tokens == null) return null
        return ParseThemeConfigResult(MermaidStyleConfig(theme, tokens), diags)
    }

    /**
     * Parse a Mermaid `classDef` statement line.
     *
     * Examples:
     * - `classDef warn fill:#f96,stroke:#333,stroke-width:4px;`
     * - `classDef a,b font-size:12pt;`
     */
    fun parseClassDefLine(line: String): ParseClassDefResult? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("classDef ")) return null

        val rest = trimmed.removePrefix("classDef ").trimStart()
        // Split into `names` + `decl` by first whitespace run.
        val firstSpace = rest.indexOfFirst { it.isWhitespace() }
        if (firstSpace < 0) {
            // No decl; treat as invalid but recoverable.
            val d = warn("Invalid classDef (missing style declaration)", "MERMAID-W012")
            return ParseClassDefResult(emptyList(), listOf(d))
        }
        val namesPart = rest.substring(0, firstSpace).trim()
        val declPart = rest.substring(firstSpace).trim().trimEnd(';').trim()

        val diags = ArrayList<Diagnostic>()
        val classNames = namesPart
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (classNames.isEmpty()) {
            diags += warn("Invalid classDef (missing class name)", "MERMAID-W012")
            return ParseClassDefResult(emptyList(), diags)
        }

        val (decl, declDiags) = parseStyleDecl(declPart)
        diags += declDiags
        val classes = classNames.map { MermaidStyleClass(name = it, decl = decl) }
        return ParseClassDefResult(classes, diags)
    }

    // ---- ThemeVariables parsing ----

    private fun parseThemeName(raw: String, diags: MutableList<Diagnostic>): MermaidThemeName? {
        val v = raw.trim().trim('\'', '"').lowercase()
        return when (v) {
            "default" -> MermaidThemeName.Default
            "neutral" -> MermaidThemeName.Neutral
            "dark" -> MermaidThemeName.Dark
            "forest" -> MermaidThemeName.Forest
            "base" -> MermaidThemeName.Base
            else -> {
                diags += warn("Unknown Mermaid theme '$raw' (ignored)", "MERMAID-W012")
                null
            }
        }
    }

    private fun parseThemeTokens(
        raw: Map<String, String>,
        diags: MutableList<Diagnostic>,
    ): MermaidThemeTokens {
        fun c(key: String): ArgbColor? = raw[key]?.let { parseHexColor(it, diags) }
        fun fPx(key: String): Float? = raw[key]?.let { parseCssPx(it, diags) }
        fun b(key: String): Boolean? = raw[key]?.let { parseBool(it) }
        fun s(key: String): String? = raw[key]?.trim()?.trim('\'', '"')?.takeIf { it.isNotEmpty() }

        return MermaidThemeTokens(
            raw = raw.toMap(),
            darkMode = b("darkMode"),
            background = c("background"),
            fontFamily = s("fontFamily"),
            fontSizePx = fPx("fontSize"),
            primaryColor = c("primaryColor"),
            primaryTextColor = c("primaryTextColor"),
            primaryBorderColor = c("primaryBorderColor"),
            lineColor = c("lineColor"),
            textColor = c("textColor"),
            nodeBorder = c("nodeBorder"),
            clusterBkg = c("clusterBkg"),
            clusterBorder = c("clusterBorder"),
            defaultLinkColor = c("defaultLinkColor"),
            titleColor = c("titleColor"),
            edgeLabelBackground = c("edgeLabelBackground"),
            nodeTextColor = c("nodeTextColor"),
            actorBkg = c("actorBkg"),
            actorBorder = c("actorBorder"),
            actorTextColor = c("actorTextColor"),
            signalColor = c("signalColor"),
            signalTextColor = c("signalTextColor"),
        )
    }

    // ---- StyleDecl parsing ----

    /**
     * Parse a Mermaid style declaration string:
     * `fill:#f9f,stroke:#333,stroke-width:4px,color:#fff,stroke-dasharray: 5 5`
     */
    fun parseStyleDecl(decl: String): Pair<MermaidStyleDecl, List<Diagnostic>> {
        val diags = ArrayList<Diagnostic>()
        val kvs = splitStyleDeclPairs(decl)
        val extras = LinkedHashMap<String, String>()

        var fill: ArgbColor? = null
        var stroke: ArgbColor? = null
        var strokeWidthPx: Float? = null
        var dash: List<Float>? = null
        var textColor: ArgbColor? = null
        var fontFamily: String? = null
        var fontSizePx: Float? = null
        var fontWeight: Int? = null
        var italic: Boolean? = null

        for ((k0, v0) in kvs) {
            val k = k0.trim().lowercase()
            val v = v0.trim()
            if (k.isEmpty()) continue

            when (k) {
                "fill" -> fill = parseHexColor(v, diags)
                "stroke" -> stroke = parseHexColor(v, diags)
                "stroke-width" -> strokeWidthPx = parseCssPx(v, diags)
                "stroke-dasharray" -> dash = parseDashArray(v, diags)
                "color" -> textColor = parseHexColor(v, diags)
                "font-family" -> fontFamily = v.trim('\'', '"')
                "font-size" -> fontSizePx = parseCssPx(v, diags)
                "font-weight" -> fontWeight = parseFontWeight(v, diags)
                "font-style" -> italic = parseFontStyleItalic(v, diags)
                else -> {
                    extras[k] = v
                    diags += warn("Unsupported style key '$k' (ignored)", "MERMAID-W012")
                }
            }
        }

        return MermaidStyleDecl(
            fill = fill,
            stroke = stroke,
            strokeWidthPx = strokeWidthPx,
            strokeDashArrayPx = dash,
            textColor = textColor,
            fontFamily = fontFamily,
            fontSizePx = fontSizePx,
            fontWeight = fontWeight,
            italic = italic,
            extras = extras,
        ) to diags
    }

    // ---- Helpers ----

    data class ParseThemeConfigResult(
        val config: MermaidStyleConfig,
        val diagnostics: List<Diagnostic>,
    )

    data class ParseClassDefResult(
        val classes: List<MermaidStyleClass>,
        val diagnostics: List<Diagnostic>,
    )

    private fun warn(message: String, code: String): Diagnostic =
        Diagnostic(severity = Severity.WARNING, message = message, code = code)

    private fun parseHexColor(text: String, diags: MutableList<Diagnostic>): ArgbColor? {
        val s = text.trim().trim('\'', '"')
        val argb = parseHexToArgbIntOrNull(s)
        if (argb == null) {
            diags += warn("Non-hex color '$text' ignored (hex required)", "MERMAID-W011")
            return null
        }
        return ArgbColor(argb)
    }

    private fun parseHexToArgbIntOrNull(hex: String): Int? {
        if (!hex.startsWith("#")) return null
        val h = hex.substring(1)
        val v: Long = when (h.length) {
            3 -> {
                // RGB -> RRGGBB
                val r = h[0].digitToIntOrNull(16) ?: return null
                val g = h[1].digitToIntOrNull(16) ?: return null
                val b = h[2].digitToIntOrNull(16) ?: return null
                ((r * 17) shl 16 or (g * 17) shl 8 or (b * 17)).toLong()
            }
            6 -> h.toLongOrNull(16) ?: return null
            8 -> {
                // AARRGGBB
                return (h.toLongOrNull(16) ?: return null).toInt()
            }
            else -> return null
        }
        // Avoid unsigned ops so it compiles on all KMP targets (JS/Wasm).
        return (0xFF000000.toInt() or v.toInt())
    }

    private fun parseCssPx(text: String, diags: MutableList<Diagnostic>): Float? {
        val s = text.trim().trimEnd(';')
        val lowered = s.lowercase()
        val rawNum = when {
            lowered.endsWith("px") -> lowered.removeSuffix("px").trim()
            lowered.endsWith("pt") -> {
                val pt = lowered.removeSuffix("pt").trim().toFloatOrNull()
                if (pt == null) {
                    diags += warn("Invalid size '$text' ignored", "MERMAID-W012")
                    return null
                }
                // Approx: 1pt = 1.333px (CSS).
                return pt * 4f / 3f
            }
            else -> lowered
        }
        val v = rawNum.toFloatOrNull()
        if (v == null) {
            diags += warn("Invalid size '$text' ignored", "MERMAID-W012")
            return null
        }
        return v
    }

    private fun parseDashArray(text: String, diags: MutableList<Diagnostic>): List<Float>? {
        val unescaped = text.replace("\\,", ",")
        val parts = unescaped
            .split(',', ' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val out = ArrayList<Float>(parts.size)
        for (p in parts) {
            val v = p.toFloatOrNull()
            if (v == null) {
                diags += warn("Invalid stroke-dasharray '$text' ignored", "MERMAID-W012")
                return null
            }
            out += v
        }
        return out
    }

    private fun parseFontWeight(text: String, diags: MutableList<Diagnostic>): Int? {
        val s = text.trim().trim('\'', '"').lowercase()
        return when {
            s == "bold" -> 700
            s == "normal" -> 400
            else -> s.toIntOrNull()?.also { /* ok */ } ?: run {
                diags += warn("Invalid font-weight '$text' ignored", "MERMAID-W012")
                null
            }
        }
    }

    private fun parseFontStyleItalic(text: String, diags: MutableList<Diagnostic>): Boolean? {
        val s = text.trim().trim('\'', '"').lowercase()
        return when (s) {
            "italic" -> true
            "normal" -> false
            else -> {
                diags += warn("Invalid font-style '$text' ignored", "MERMAID-W012")
                null
            }
        }
    }

    private fun parseBool(text: String): Boolean? {
        val s = text.trim().trim('\'', '"').lowercase()
        return when (s) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

    private fun splitStyleDeclPairs(decl: String): List<Pair<String, String>> {
        // Split on commas not escaped as '\,'.
        val parts = ArrayList<String>()
        val sb = StringBuilder()
        var escape = false
        for (ch in decl) {
            if (escape) {
                sb.append(ch)
                escape = false
                continue
            }
            if (ch == '\\') {
                escape = true
                sb.append(ch)
                continue
            }
            if (ch == ',') {
                parts += sb.toString()
                sb.clear()
                continue
            }
            sb.append(ch)
        }
        if (sb.isNotEmpty()) parts += sb.toString()

        val out = ArrayList<Pair<String, String>>(parts.size)
        for (p in parts) {
            val idx = p.indexOf(':')
            if (idx <= 0) continue
            out += p.substring(0, idx) to p.substring(idx + 1)
        }
        return out
    }

    private fun splitYamlKeyValue(line: String): Pair<String, String>? {
        val idx = line.indexOf(':')
        if (idx <= 0) return null
        val k = line.substring(0, idx).trim()
        val v = line.substring(idx + 1).trim()
        if (k.isEmpty()) return null
        return k to v
    }

    private fun unquoteYamlScalar(v: String): String {
        val s = v.trim()
        if (s.length >= 2 && ((s.first() == '\'' && s.last() == '\'') || (s.first() == '"' && s.last() == '"'))) {
            return s.substring(1, s.length - 1)
        }
        return s
    }
}
