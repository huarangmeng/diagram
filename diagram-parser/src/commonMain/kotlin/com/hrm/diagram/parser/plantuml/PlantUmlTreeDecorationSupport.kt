package com.hrm.diagram.parser.plantuml

internal data class PlantUmlTreeDecorations(
    val cleanedText: String,
    val inlineColor: String?,
    val stereotype: String?,
)

internal enum class PlantUmlTreeLeadingVisualKind {
    Icon,
    Emoji,
}

internal data class PlantUmlTreeLeadingVisual(
    val kind: PlantUmlTreeLeadingVisualKind,
    val name: String,
    val color: String? = null,
)

internal object PlantUmlTreeDecorationSupport {
    private val inlineColor = Regex("""^\[(#[A-Za-z0-9]+|[A-Za-z][A-Za-z0-9]*)\]\s*""")
    private val stereotype = Regex("""\s*<<([^>]+)>>\s*""")
    private val icon = Regex("""^<&([^>]+)>\s*""")
    private val emoji = Regex("""^<(?:#([^:>]+))?:([A-Za-z0-9_+\-]+):>\s*""")

    fun parse(text: String): PlantUmlTreeDecorations {
        val trimmed = text.trim()
        return PlantUmlTreeDecorations(
            cleanedText = trimmed.replace(inlineColor, "").replace(stereotype, " ").trim(),
            inlineColor = inlineColor.find(trimmed)?.groupValues?.getOrNull(1),
            stereotype = stereotype.find(trimmed)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    fun displayLabel(label: String, stereotype: String?): String =
        stereotype?.takeIf { it.isNotEmpty() }?.let { "\u00AB$it\u00BB\n$label" } ?: label

    fun parseLeadingVisual(text: String): Pair<String, PlantUmlTreeLeadingVisual?> {
        val trimmed = text.trim()
        icon.find(trimmed)?.let { match ->
            val remainder = trimmed.removeRange(match.range.first, match.range.last + 1).trim()
            return remainder to PlantUmlTreeLeadingVisual(
                kind = PlantUmlTreeLeadingVisualKind.Icon,
                name = match.groupValues[1].trim(),
            )
        }
        emoji.find(trimmed)?.let { match ->
            val remainder = trimmed.removeRange(match.range.first, match.range.last + 1).trim()
            return remainder to PlantUmlTreeLeadingVisual(
                kind = PlantUmlTreeLeadingVisualKind.Emoji,
                name = match.groupValues[2].trim(),
                color = match.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() },
            )
        }
        return trimmed to null
    }

    fun leadingVisualFallbackLabel(leadingVisual: PlantUmlTreeLeadingVisual): String {
        val raw = leadingVisual.name.substringAfterLast('/').substringAfterLast(':').replace('-', ' ').replace('_', ' ').trim()
        val parts = raw.split(' ').filter { it.isNotEmpty() }
        return when {
            parts.isEmpty() -> if (leadingVisual.kind == PlantUmlTreeLeadingVisualKind.Icon) "I" else "E"
            parts.size == 1 -> parts[0].take(2).uppercase()
            else -> (parts[0].take(1) + parts[1].take(1)).uppercase()
        }
    }

    fun displayLabel(
        label: String,
        stereotype: String?,
        leadingVisual: PlantUmlTreeLeadingVisual?,
    ): String {
        val body = leadingVisual?.let { "${leadingVisualFallbackLabel(it)} $label".trim() } ?: label
        return displayLabel(body, stereotype)
    }

    fun encodeLeadingVisual(leadingVisual: PlantUmlTreeLeadingVisual): String =
        "${leadingVisual.kind.name.lowercase()},${leadingVisual.color.orEmpty()},${leadingVisual.name}"
}
