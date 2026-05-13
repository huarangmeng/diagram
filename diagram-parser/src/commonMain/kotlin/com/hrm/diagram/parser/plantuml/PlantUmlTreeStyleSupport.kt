package com.hrm.diagram.parser.plantuml

internal data class PlantUmlTreeStyleRule(
    var nodeBackground: String? = null,
    var branchBackground: String? = null,
    var nodeLineColor: String? = null,
    var branchLineColor: String? = null,
    var nodeFontColor: String? = null,
    var branchFontColor: String? = null,
    var nodeFontName: String? = null,
    var branchFontName: String? = null,
    var nodeFontSize: String? = null,
    var branchFontSize: String? = null,
    var nodeFontStyle: String? = null,
    var branchFontStyle: String? = null,
    var nodeLineThickness: String? = null,
    var branchLineThickness: String? = null,
    var nodeRoundCorner: String? = null,
    var branchRoundCorner: String? = null,
    var nodeShadowing: String? = null,
    var branchShadowing: String? = null,
    var nodeMaximumWidth: String? = null,
    var branchMaximumWidth: String? = null,
)

internal data class PlantUmlTreeResolvedStyle(
    val className: String,
    val nodeBackground: String?,
    val branchBackground: String?,
    val nodeLineColor: String?,
    val branchLineColor: String?,
    val nodeFontColor: String?,
    val branchFontColor: String?,
    val nodeFontName: String?,
    val branchFontName: String?,
    val nodeFontSize: String?,
    val branchFontSize: String?,
    val nodeFontStyle: String?,
    val branchFontStyle: String?,
    val nodeLineThickness: String?,
    val branchLineThickness: String?,
    val nodeRoundCorner: String?,
    val branchRoundCorner: String?,
    val nodeShadowing: String?,
    val branchShadowing: String?,
    val nodeMaximumWidth: String?,
    val branchMaximumWidth: String?,
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
            FONT_NAME.matches(trimmed) -> setRuleValue(rule, selector.branch, StyleField.FontName, extractValue(trimmed, FONT_NAME))
            FONT_SIZE.matches(trimmed) -> setRuleValue(rule, selector.branch, StyleField.FontSize, extractValue(trimmed, FONT_SIZE))
            FONT_STYLE.matches(trimmed) -> setRuleValue(rule, selector.branch, StyleField.FontStyle, extractValue(trimmed, FONT_STYLE))
            LINE_THICKNESS.matches(trimmed) -> setRuleValue(rule, selector.branch, StyleField.LineThickness, extractValue(trimmed, LINE_THICKNESS))
            ROUND_CORNER.matches(trimmed) -> setRuleValue(rule, selector.branch, StyleField.RoundCorner, extractValue(trimmed, ROUND_CORNER))
            SHADOWING.matches(trimmed) -> setRuleValue(rule, selector.branch, StyleField.Shadowing, extractValue(trimmed, SHADOWING))
            MAXIMUM_WIDTH.matches(trimmed) -> setRuleValue(rule, selector.branch, StyleField.MaximumWidth, extractValue(trimmed, MAXIMUM_WIDTH))
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
            nodeFontName = rule.nodeFontName,
            branchFontName = rule.branchFontName,
            nodeFontSize = rule.nodeFontSize,
            branchFontSize = rule.branchFontSize,
            nodeFontStyle = rule.nodeFontStyle,
            branchFontStyle = rule.branchFontStyle,
            nodeLineThickness = rule.nodeLineThickness,
            branchLineThickness = rule.branchLineThickness,
            nodeRoundCorner = rule.nodeRoundCorner,
            branchRoundCorner = rule.branchRoundCorner,
            nodeShadowing = rule.nodeShadowing,
            branchShadowing = rule.branchShadowing,
            nodeMaximumWidth = rule.nodeMaximumWidth,
            branchMaximumWidth = rule.branchMaximumWidth,
        )
    }

    private fun extractValue(raw: String, regex: Regex): String =
        regex.matchEntire(raw)?.groupValues?.getOrNull(1)?.trim()?.removeSurrounding("\"").orEmpty()

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
            StyleField.FontName -> if (branch) rule.branchFontName = value.trim('"') else rule.nodeFontName = value.trim('"')
            StyleField.FontSize -> if (branch) rule.branchFontSize = value else rule.nodeFontSize = value
            StyleField.FontStyle -> if (branch) rule.branchFontStyle = value else rule.nodeFontStyle = value
            StyleField.LineThickness -> if (branch) rule.branchLineThickness = value else rule.nodeLineThickness = value
            StyleField.RoundCorner -> if (branch) rule.branchRoundCorner = value else rule.nodeRoundCorner = value
            StyleField.Shadowing -> if (branch) rule.branchShadowing = value else rule.nodeShadowing = value
            StyleField.MaximumWidth -> if (branch) rule.branchMaximumWidth = value else rule.nodeMaximumWidth = value
        }
    }

    private enum class StyleField {
        Background,
        LineColor,
        FontColor,
        FontName,
        FontSize,
        FontStyle,
        LineThickness,
        RoundCorner,
        Shadowing,
        MaximumWidth,
    }

    private companion object {
        val CLASS_SELECTOR = Regex("""^\.([A-Za-z0-9_-]+)\s*(\*)?$""")
        val BACKGROUND_COLOR = Regex("""^backgroundcolor(?:\s*:)?\s*(.+)$""", RegexOption.IGNORE_CASE)
        val LINE_COLOR = Regex("""^linecolor(?:\s*:)?\s*(.+)$""", RegexOption.IGNORE_CASE)
        val FONT_COLOR = Regex("""^fontcolor(?:\s*:)?\s*(.+)$""", RegexOption.IGNORE_CASE)
        val FONT_NAME = Regex("""^font(?:name|family)(?:\s*:)?\s*(.+)$""", RegexOption.IGNORE_CASE)
        val FONT_SIZE = Regex("""^fontsize(?:\s*:)?\s*(.+)$""", RegexOption.IGNORE_CASE)
        val FONT_STYLE = Regex("""^fontstyle(?:\s*:)?\s*(.+)$""", RegexOption.IGNORE_CASE)
        val LINE_THICKNESS = Regex("""^linethickness(?:\s*:)?\s*(.+)$""", RegexOption.IGNORE_CASE)
        val ROUND_CORNER = Regex("""^roundcorner(?:\s*:)?\s*(.+)$""", RegexOption.IGNORE_CASE)
        val SHADOWING = Regex("""^shadowing(?:\s*:)?\s*(.+)$""", RegexOption.IGNORE_CASE)
        val MAXIMUM_WIDTH = Regex("""^(?:maximumwidth|maxwidth)(?:\s*:)?\s*(.+)$""", RegexOption.IGNORE_CASE)
    }
}
