package com.hrm.diagram.parser.plantuml

internal data class PlantUmlTreeStyleRule(
    var nodeBackground: String? = null,
    var branchBackground: String? = null,
    var nodeLineColor: String? = null,
    var branchLineColor: String? = null,
    var nodeFontColor: String? = null,
    var branchFontColor: String? = null,
    var nodeRoundCorner: String? = null,
    var branchRoundCorner: String? = null,
)

internal data class PlantUmlTreeResolvedStyle(
    val className: String,
    val nodeBackground: String?,
    val branchBackground: String?,
    val nodeLineColor: String?,
    val branchLineColor: String?,
    val nodeFontColor: String?,
    val branchFontColor: String?,
    val nodeRoundCorner: String?,
    val branchRoundCorner: String?,
)

internal class PlantUmlTreeStyleSupport(
    private val diagramSelector: String,
) {
    private sealed interface Frame {
        data class Diagram(val name: String) : Frame

        data class ClassSelector(val name: String, val branch: Boolean) : Frame

        data object Other : Frame
    }

    private val blockStack: MutableList<Frame> = ArrayList()
    private val rulesByClass: LinkedHashMap<String, PlantUmlTreeStyleRule> = LinkedHashMap()
    private var insideStyleBlock: Boolean = false

    fun acceptLine(trimmed: String): Boolean {
        if (!insideStyleBlock) {
            if (trimmed.equals("<style>", ignoreCase = true) || trimmed.startsWith("<style ", ignoreCase = true)) {
                insideStyleBlock = true
                blockStack.clear()
                return true
            }
            return false
        }
        if (trimmed.equals("</style>", ignoreCase = true)) {
            insideStyleBlock = false
            blockStack.clear()
            return true
        }
        if (trimmed.isEmpty() || trimmed.startsWith("'") || trimmed.startsWith("//")) return true
        if (trimmed == "}") {
            if (blockStack.isNotEmpty()) blockStack.removeAt(blockStack.lastIndex)
            return true
        }
        if (trimmed.endsWith("{")) {
            val header = trimmed.removeSuffix("{").trim()
            blockStack += when {
                header.equals(diagramSelector, ignoreCase = true) -> Frame.Diagram(header)
                currentDiagramActive() -> parseClassSelector(header) ?: Frame.Other
                else -> Frame.Other
            }
            return true
        }
        val selector = blockStack.lastOrNull() as? Frame.ClassSelector ?: return true
        val rule = rulesByClass.getOrPut(selector.name) { PlantUmlTreeStyleRule() }
        when {
            BACKGROUND_COLOR.matches(trimmed) -> setRuleValue(rule, selector.branch, StyleField.Background, extractValue(trimmed, BACKGROUND_COLOR))
            LINE_COLOR.matches(trimmed) -> setRuleValue(rule, selector.branch, StyleField.LineColor, extractValue(trimmed, LINE_COLOR))
            FONT_COLOR.matches(trimmed) -> setRuleValue(rule, selector.branch, StyleField.FontColor, extractValue(trimmed, FONT_COLOR))
            ROUND_CORNER.matches(trimmed) -> setRuleValue(rule, selector.branch, StyleField.RoundCorner, extractValue(trimmed, ROUND_CORNER))
        }
        return true
    }

    fun resolve(stereotype: String?): PlantUmlTreeResolvedStyle? {
        val name = stereotype?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val rule = rulesByClass[name] ?: return null
        return PlantUmlTreeResolvedStyle(
            className = name,
            nodeBackground = rule.nodeBackground,
            branchBackground = rule.branchBackground,
            nodeLineColor = rule.nodeLineColor,
            branchLineColor = rule.branchLineColor,
            nodeFontColor = rule.nodeFontColor,
            branchFontColor = rule.branchFontColor,
            nodeRoundCorner = rule.nodeRoundCorner,
            branchRoundCorner = rule.branchRoundCorner,
        )
    }

    private fun extractValue(raw: String, regex: Regex): String =
        regex.matchEntire(raw)?.groupValues?.getOrNull(1)?.trim().orEmpty()

    private fun currentDiagramActive(): Boolean = blockStack.any { it is Frame.Diagram }

    private fun parseClassSelector(raw: String): Frame.ClassSelector? {
        val match = CLASS_SELECTOR.matchEntire(raw) ?: return null
        val className = match.groupValues[1].trim()
        val branch = match.groupValues[2].isNotEmpty()
        return Frame.ClassSelector(className, branch)
    }

    private fun setRuleValue(rule: PlantUmlTreeStyleRule, branch: Boolean, field: StyleField, value: String) {
        when (field) {
            StyleField.Background -> if (branch) rule.branchBackground = value else rule.nodeBackground = value
            StyleField.LineColor -> if (branch) rule.branchLineColor = value else rule.nodeLineColor = value
            StyleField.FontColor -> if (branch) rule.branchFontColor = value else rule.nodeFontColor = value
            StyleField.RoundCorner -> if (branch) rule.branchRoundCorner = value else rule.nodeRoundCorner = value
        }
    }

    private enum class StyleField {
        Background,
        LineColor,
        FontColor,
        RoundCorner,
    }

    private companion object {
        val CLASS_SELECTOR = Regex("""^\.([A-Za-z0-9_-]+)\s*(\*)?$""")
        val BACKGROUND_COLOR = Regex("""^backgroundcolor(?:\s*:)?\s*(.+)$""", RegexOption.IGNORE_CASE)
        val LINE_COLOR = Regex("""^linecolor(?:\s*:)?\s*(.+)$""", RegexOption.IGNORE_CASE)
        val FONT_COLOR = Regex("""^fontcolor(?:\s*:)?\s*(.+)$""", RegexOption.IGNORE_CASE)
        val ROUND_CORNER = Regex("""^roundcorner(?:\s*:)?\s*(.+)$""", RegexOption.IGNORE_CASE)
    }
}
