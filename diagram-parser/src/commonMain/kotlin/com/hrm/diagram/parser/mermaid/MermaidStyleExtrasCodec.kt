package com.hrm.diagram.parser.mermaid

import com.hrm.diagram.core.ir.ArgbColor

/**
 * Encode Mermaid style data into `DiagramModel.styleHints.extras` using a simple line protocol.
 *
 * Rationale:
 * - `extras` is `Map<String, String>` so we need a compact, deterministic representation.
 * - Avoids bringing JSON/YAML dependencies into core parsing.
 *
 * Format:
 * - Theme tokens: `key=value` per line, no trailing newline.
 * - ClassDefs: `className|k=v;k=v` per line, no trailing newline.
 *
 * Escaping:
 * - Backslash, newline, carriage return, '=', ';', '|' are escaped with a preceding backslash.
 */
object MermaidStyleExtrasCodec {
    fun encodeThemeTokens(tokens: MermaidThemeTokens): String {
        val lines = ArrayList<String>()

        // Typed normalized values (stable contract).
        tokens.darkMode?.let { lines += "darkMode=${it}" }
        tokens.background?.let { lines += "background=${encodeColor(it)}" }
        tokens.fontFamily?.let { lines += "fontFamily=${escKV(it)}" }
        tokens.fontSizePx?.let { lines += "fontSizePx=${formatFloat(it)}" }
        tokens.primaryColor?.let { lines += "primaryColor=${encodeColor(it)}" }
        tokens.primaryTextColor?.let { lines += "primaryTextColor=${encodeColor(it)}" }
        tokens.primaryBorderColor?.let { lines += "primaryBorderColor=${encodeColor(it)}" }
        tokens.lineColor?.let { lines += "lineColor=${encodeColor(it)}" }
        tokens.textColor?.let { lines += "textColor=${encodeColor(it)}" }

        // Flowchart variables.
        tokens.nodeBorder?.let { lines += "nodeBorder=${encodeColor(it)}" }
        tokens.clusterBkg?.let { lines += "clusterBkg=${encodeColor(it)}" }
        tokens.clusterBorder?.let { lines += "clusterBorder=${encodeColor(it)}" }
        tokens.defaultLinkColor?.let { lines += "defaultLinkColor=${encodeColor(it)}" }
        tokens.titleColor?.let { lines += "titleColor=${encodeColor(it)}" }
        tokens.edgeLabelBackground?.let { lines += "edgeLabelBackground=${encodeColor(it)}" }
        tokens.nodeTextColor?.let { lines += "nodeTextColor=${encodeColor(it)}" }

        // Sequence variables (minimum).
        tokens.actorBkg?.let { lines += "actorBkg=${encodeColor(it)}" }
        tokens.actorBorder?.let { lines += "actorBorder=${encodeColor(it)}" }
        tokens.actorTextColor?.let { lines += "actorTextColor=${encodeColor(it)}" }
        tokens.signalColor?.let { lines += "signalColor=${encodeColor(it)}" }
        tokens.signalTextColor?.let { lines += "signalTextColor=${encodeColor(it)}" }

        // Raw tokens, preserved for debugging/future support. Raw values are escaped.
        // Use stable ordering so snapshots are deterministic.
        for (k in tokens.raw.keys.sorted()) {
            val v = tokens.raw[k] ?: continue
            lines += "raw.${escKey(k)}=${escKV(v)}"
        }

        return lines.joinToString("\n")
    }

    fun encodeClassDefs(classDefs: Map<String, MermaidStyleDecl>): String {
        val lines = ArrayList<String>(classDefs.size)
        for (name in classDefs.keys.sorted()) {
            val decl = classDefs[name] ?: continue
            lines += buildString {
                append(escKey(name))
                append('|')
                append(encodeStyleDeclInline(decl))
            }
        }
        return lines.joinToString("\n")
    }

    private fun encodeStyleDeclInline(decl: MermaidStyleDecl): String {
        val parts = ArrayList<String>()
        decl.fill?.let { parts += "fill=${encodeColor(it)}" }
        decl.stroke?.let { parts += "stroke=${encodeColor(it)}" }
        decl.strokeWidthPx?.let { parts += "strokeWidthPx=${formatFloat(it)}" }
        decl.strokeDashArrayPx?.let { parts += "strokeDashArrayPx=${it.joinToString(",", transform = ::formatFloat)}" }
        decl.textColor?.let { parts += "textColor=${encodeColor(it)}" }
        decl.fontFamily?.let { parts += "fontFamily=${escKV(it)}" }
        decl.fontSizePx?.let { parts += "fontSizePx=${formatFloat(it)}" }
        decl.fontWeight?.let { parts += "fontWeight=${it}" }
        decl.italic?.let { parts += "italic=${it}" }

        // Extras are preserved, stable ordering.
        for (k in decl.extras.keys.sorted()) {
            val v = decl.extras[k] ?: continue
            parts += "extra.${escKey(k)}=${escKV(v)}"
        }
        return parts.joinToString(";")
    }

    private fun encodeColor(c: ArgbColor): String {
        // Keep ARGB explicit for stability, even if alpha is always FF in most Mermaid vars.
        val hex = c.argb.toUInt().toString(16).padStart(8, '0').uppercase()
        return "#$hex"
    }

    private fun formatFloat(v: Float): String {
        // Stable compact formatting: avoid scientific notation for typical sizes.
        val s = v.toString()
        return if (s.endsWith(".0")) s.dropLast(2) else s
    }

    private fun escKey(s: String): String = escape(s)
    private fun escKV(s: String): String = escape(s)

    private fun escape(s: String): String {
        val out = StringBuilder(s.length + 8)
        for (ch in s) {
            when (ch) {
                '\\', '\n', '\r', '=', ';', '|' -> {
                    out.append('\\')
                    out.append(ch)
                }
                else -> out.append(ch)
            }
        }
        return out.toString()
    }
}

