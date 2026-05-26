package com.hrm.diagram.render.theme

import com.hrm.diagram.core.draw.Color
import com.hrm.diagram.core.theme.DiagramTheme

internal object ThemeResolver {
    fun resolveJourney(theme: DiagramTheme): ResolvedJourneyColors {
        val palette = categoricalPalette(theme)
        return ResolvedJourneyColors(
            background = theme.colors.canvas,
            text = theme.colors.textPrimary,
            secondaryText = theme.colors.textSecondary,
            axis = theme.colors.border,
            line = theme.colors.accentSecondary,
            scoreFills = listOf(
                theme.colors.danger.withAlpha(0.34f),
                theme.colors.warning.withAlpha(0.30f),
                theme.colors.accentTertiary.withAlpha(0.26f),
                theme.colors.success.withAlpha(0.22f),
                theme.colors.accent.withAlpha(0.20f),
            ),
            accentPalette = palette,
        )
    }

    fun resolveKanban(theme: DiagramTheme): ResolvedKanbanColors =
        ResolvedKanbanColors(
            background = theme.colors.canvas,
            columnFill = theme.colors.surfaceAlt,
            columnStroke = theme.colors.border,
            headerFill = theme.colors.accent.withAlpha(0.14f),
            cardFill = theme.colors.surface,
            cardStroke = theme.colors.border,
            text = theme.colors.textPrimary,
            metaText = theme.colors.textSecondary,
            priorityVeryHigh = theme.colors.danger,
            priorityHigh = theme.colors.warning,
            priorityLow = theme.colors.success,
            priorityVeryLow = theme.colors.accent,
            priorityDefault = theme.colors.textSecondary,
            badgeText = Color.White,
        )

    fun resolveSankey(theme: DiagramTheme): ResolvedSankeyColors =
        ResolvedSankeyColors(
            background = theme.colors.canvas,
            text = theme.colors.textPrimary,
            border = theme.colors.border,
            nodePalette = categoricalPalette(theme),
            flowAlpha = 110,
        )

    fun resolveGitGraph(theme: DiagramTheme): ResolvedGitGraphColors =
        ResolvedGitGraphColors(
            background = theme.colors.canvas,
            text = theme.colors.textPrimary,
            lane = theme.colors.border,
            border = theme.colors.textSecondary,
            tagFill = theme.colors.accentTertiary.withAlpha(0.32f),
            tagText = theme.colors.textPrimary,
            branchPalette = categoricalPalette(theme) + listOf(
                theme.colors.textSecondary,
                theme.colors.accent.withAlpha(0.6f),
            ),
        )

    fun resolveXYChart(theme: DiagramTheme): ResolvedXYChartColors {
        val timeSeries = resolveTimeSeries(theme)
        return ResolvedXYChartColors(
            background = theme.colors.canvas,
            xAxis = timeSeries.axis,
            yAxis = timeSeries.axis,
            text = timeSeries.titleText,
            xAxisLabel = timeSeries.labelText,
            yAxisLabel = timeSeries.labelText,
            xAxisTitle = timeSeries.titleText,
            yAxisTitle = timeSeries.titleText,
            dataLabel = timeSeries.labelText,
            plotPalette = categoricalPalette(theme).ifEmpty {
                listOf(theme.colors.accent)
            },
            areaAlpha = 90,
        )
    }

    fun resolvePlantUmlUsecase(theme: DiagramTheme): ResolvedPlantUmlUsecaseColors {
        val graph = resolveGraph(theme)
        return ResolvedPlantUmlUsecaseColors(
            canvas = theme.colors.canvas,
            clusterFill = graph.clusterFill,
            clusterStroke = graph.clusterStroke,
            clusterText = theme.colors.textPrimary,
            actorFill = Color.Transparent,
            actorStroke = theme.colors.textSecondary,
            actorText = theme.colors.textPrimary,
            usecaseFill = theme.colors.accent.withAlpha(0.12f),
            usecaseStroke = theme.colors.accent,
            usecaseText = theme.colors.textPrimary,
            noteFill = graph.noteFill,
            noteStroke = graph.noteStroke,
            noteText = graph.noteText,
            edge = theme.colors.textSecondary,
            edgeLabelText = theme.colors.textPrimary,
        )
    }

    fun resolvePlantUmlDeployment(theme: DiagramTheme): ResolvedPlantUmlDeploymentColors {
        val graph = resolveGraph(theme)
        return ResolvedPlantUmlDeploymentColors(
            canvas = theme.colors.canvas,
            clusterFill = graph.clusterFill,
            clusterStroke = graph.clusterStroke,
            clusterText = theme.colors.textPrimary,
            clusterChipFill = theme.colors.surface,
            nodeFill = theme.colors.success.withAlpha(0.12f),
            nodeStroke = theme.colors.success,
            nodeText = theme.colors.textPrimary,
            noteFill = graph.noteFill,
            noteStroke = graph.noteStroke,
            noteText = graph.noteText,
            edge = theme.colors.textSecondary,
            edgeLabelText = theme.colors.textPrimary,
        )
    }

    fun resolvePlantUmlActivity(theme: DiagramTheme): ResolvedPlantUmlActivityColors =
        ResolvedPlantUmlActivityColors(
            actionFill = theme.colors.accent.withAlpha(0.12f),
            actionStroke = theme.colors.accent,
            actionText = theme.colors.textPrimary,
            decisionFill = theme.colors.success.withAlpha(0.12f),
            decisionStroke = theme.colors.success,
            decisionText = theme.colors.textPrimary,
            noteFill = theme.colors.warning.withAlpha(0.12f),
            noteStroke = theme.colors.warning,
            noteText = theme.colors.textPrimary,
            barFill = theme.colors.textPrimary,
            barText = theme.colors.surface,
            startFill = theme.colors.textPrimary,
            stopStroke = theme.colors.textPrimary,
            laneFill = theme.colors.accent.withAlpha(0.08f),
            laneStroke = theme.colors.accent.withAlpha(0.40f),
            laneText = theme.colors.accent,
            edge = theme.colors.textSecondary,
        )

    fun resolvePlantUmlClass(theme: DiagramTheme): ResolvedPlantUmlClassColors {
        val palette = categoricalPalette(theme)
        return ResolvedPlantUmlClassColors(
            base = ClassStereotypePalette(
                boxFill = theme.colors.surface,
                headerFill = theme.colors.surfaceAlt,
                stroke = theme.colors.border,
                text = theme.colors.textPrimary,
            ),
            entity = ClassStereotypePalette(
                boxFill = palette[0].withAlpha(0.12f),
                headerFill = palette[0].withAlpha(0.22f),
                stroke = palette[0],
                text = theme.colors.textPrimary,
            ),
            boundary = ClassStereotypePalette(
                boxFill = palette[1].withAlpha(0.12f),
                headerFill = palette[1].withAlpha(0.22f),
                stroke = palette[1],
                text = theme.colors.textPrimary,
            ),
            control = ClassStereotypePalette(
                boxFill = palette[2].withAlpha(0.12f),
                headerFill = palette[2].withAlpha(0.22f),
                stroke = palette[2],
                text = theme.colors.textPrimary,
            ),
            noteFill = theme.colors.warning.withAlpha(0.12f),
            noteStroke = theme.colors.warning,
            noteText = theme.colors.textPrimary,
            namespaceFill = theme.colors.surfaceAlt,
            namespaceChipFill = theme.colors.surface,
            namespaceStroke = theme.colors.border,
            namespaceText = theme.colors.textPrimary,
            edgeColor = theme.colors.textSecondary,
            commonTextColor = theme.colors.textPrimary,
        )
    }

    fun resolvePlantUmlState(theme: DiagramTheme): ResolvedPlantUmlStateColors =
        ResolvedPlantUmlStateColors(
            stateFill = theme.colors.accent.withAlpha(0.12f),
            stateStroke = theme.colors.accent,
            stateText = theme.colors.textPrimary,
            compositeFill = theme.colors.surfaceAlt,
            compositeStroke = theme.colors.border,
            noteFill = theme.colors.warning.withAlpha(0.12f),
            noteStroke = theme.colors.warning,
            noteText = theme.colors.textPrimary,
            pseudoFill = theme.colors.textPrimary,
            edgeColor = theme.colors.textSecondary,
        )

    fun resolvePlantUmlSalt(theme: DiagramTheme): ResolvedPlantUmlSaltColors =
        ResolvedPlantUmlSaltColors(
            rootFill = theme.colors.surfaceAlt,
            rootStroke = theme.colors.border,
            titleText = theme.colors.textSecondary,
            panelFill = theme.colors.surface,
            panelStroke = theme.colors.border,
            mutedStroke = theme.colors.border.withAlpha(0.75f),
            buttonFill = theme.colors.accent.withAlpha(0.12f),
            buttonStroke = theme.colors.accent.withAlpha(0.55f),
            inputText = theme.colors.textSecondary,
            accentFill = theme.colors.accent.withAlpha(0.16f),
            text = theme.colors.textPrimary,
            mutedText = theme.colors.textSecondary,
        )

    fun resolveMermaidMindmap(theme: DiagramTheme): ResolvedTreeColors {
        val base = resolveTree(theme)
        return base.copy(
            nodeFill = theme.graphColors.noteFill ?: theme.colors.success.withAlpha(0.12f),
            nodeStroke = theme.graphColors.noteStroke ?: theme.colors.success,
            nodeText = theme.colors.textPrimary,
            rootFill = theme.graphColors.nodeFill ?: theme.colors.accent.withAlpha(0.12f),
            rootStroke = theme.graphColors.nodeStroke ?: theme.colors.accent,
            rootText = theme.colors.textPrimary,
            edge = theme.colors.textSecondary,
        )
    }

    fun resolvePlantUmlMindmap(theme: DiagramTheme): ResolvedTreeColors {
        val base = resolveTree(theme)
        return base.copy(
            nodeFill = theme.colors.success.withAlpha(0.12f),
            nodeStroke = theme.colors.success,
            nodeText = theme.colors.textPrimary,
            rootFill = theme.colors.accent.withAlpha(0.12f),
            rootStroke = theme.colors.accent,
            rootText = theme.colors.textPrimary,
            edge = theme.colors.textSecondary,
        )
    }

    fun resolvePlantUmlWbs(theme: DiagramTheme): ResolvedTreeColors {
        val base = resolveTree(theme)
        return base.copy(
            nodeFill = theme.colors.warning.withAlpha(0.12f),
            nodeStroke = theme.colors.warning,
            nodeText = theme.colors.textPrimary,
            rootFill = theme.colors.warning.withAlpha(0.22f),
            rootStroke = theme.colors.warning,
            rootText = theme.colors.textPrimary,
            edge = theme.colors.textSecondary,
        )
    }

    fun categoricalPalette(theme: DiagramTheme): List<Color> = listOf(
        theme.colors.accent,
        theme.colors.success,
        theme.colors.warning,
        theme.colors.danger,
        theme.colors.accentSecondary,
        theme.colors.accentTertiary,
    )

    fun resolveMermaidFlowchart(theme: DiagramTheme): ResolvedGraphColors {
        val scope = theme.graphColors
        return ResolvedGraphColors(
            background = scope.background ?: theme.colors.canvas,
            nodeFill = scope.nodeFill ?: theme.colors.accent.withAlpha(0.12f),
            nodeStroke = scope.nodeStroke ?: theme.colors.accent,
            nodeText = scope.nodeText ?: theme.colors.accent,
            edge = scope.edge ?: theme.colors.textSecondary,
            edgeLabelText = scope.edgeLabelText ?: theme.colors.textPrimary,
            edgeLabelBackground = scope.edgeLabelBackground ?: theme.colors.surface.withAlpha(0.94f),
            clusterFill = scope.clusterFill ?: theme.colors.surfaceAlt.withAlpha(0.92f),
            clusterStroke = scope.clusterStroke ?: theme.colors.border,
            noteFill = scope.noteFill ?: theme.colors.warning.withAlpha(0.12f),
            noteStroke = scope.noteStroke ?: theme.colors.warning,
            noteText = scope.noteText ?: theme.colors.textPrimary,
        )
    }

    fun resolveGraph(theme: DiagramTheme): ResolvedGraphColors {
        val scope = theme.graphColors
        return ResolvedGraphColors(
            background = scope.background ?: theme.colors.canvas,
            nodeFill = scope.nodeFill ?: theme.nodeDefaults.fill?.toColor() ?: theme.colors.surface,
            nodeStroke = scope.nodeStroke ?: theme.nodeDefaults.stroke?.toColor() ?: theme.colors.border,
            nodeText = scope.nodeText ?: theme.nodeDefaults.textColor?.toColor() ?: theme.colors.textPrimary,
            edge = scope.edge ?: theme.edgeDefaults.color?.toColor() ?: theme.colors.textPrimary,
            edgeLabelText = scope.edgeLabelText ?: theme.colors.textPrimary,
            edgeLabelBackground = scope.edgeLabelBackground ?: theme.colors.surface.withAlpha(0.94f),
            clusterFill = scope.clusterFill ?: theme.clusterDefaults.fill?.toColor() ?: theme.colors.surfaceAlt.withAlpha(0.9f),
            clusterStroke = scope.clusterStroke ?: theme.clusterDefaults.stroke?.toColor() ?: theme.colors.border,
            noteFill = scope.noteFill ?: theme.colors.warning.withAlpha(0.12f),
            noteStroke = scope.noteStroke ?: theme.colors.warning,
            noteText = scope.noteText ?: theme.colors.textPrimary,
        )
    }

    fun resolvePlantUmlSequence(theme: DiagramTheme): ResolvedPlantUmlSequenceColors {
        val base = resolveSequence(theme)
        return ResolvedPlantUmlSequenceColors(
            headerFill = base.headerFill,
            headerStroke = base.headerStroke,
            headerText = base.headerText,
            lifeline = base.lifeline,
            message = base.message,
            messageText = base.messageText,
            noteFill = base.noteFill,
            noteStroke = base.noteStroke,
            noteText = base.noteText,
            activationFill = base.activationFill,
            activationStroke = base.activationStroke,
            fragmentStroke = base.fragmentStroke,
            referenceNoteFill = theme.colors.accentSecondary.withAlpha(0.12f),
            referenceNoteStroke = theme.colors.accentSecondary,
            boxFill = theme.colors.accentSecondary.withAlpha(0.12f),
            boxStroke = theme.colors.accentSecondary,
            boxText = theme.colors.textPrimary,
        )
    }

    fun resolvePlantUmlComponent(theme: DiagramTheme): ResolvedPlantUmlComponentColors {
        val graph = resolveGraph(theme)
        return ResolvedPlantUmlComponentColors(
            canvas = theme.colors.canvas,
            clusterFill = graph.clusterFill,
            clusterStroke = graph.clusterStroke,
            clusterText = theme.colors.textPrimary,
            clusterChipFill = theme.colors.surface,
            nodeFill = theme.colors.accentSecondary.withAlpha(0.12f),
            nodeStroke = theme.colors.accentSecondary,
            nodeText = theme.colors.textPrimary,
            noteFill = graph.noteFill,
            noteStroke = graph.noteStroke,
            noteText = graph.noteText,
            edgeColor = theme.colors.textSecondary,
            edgeLabelText = theme.colors.textPrimary,
        )
    }

    fun resolvePie(theme: DiagramTheme): ResolvedPieColors {
        val scope = theme.pieColors
        val fallback = listOf(
            theme.colors.accent,
            theme.colors.success,
            theme.colors.warning,
            theme.colors.danger,
            theme.colors.accentSecondary,
            theme.colors.accentTertiary,
        )
        return ResolvedPieColors(
            titleText = scope.titleText ?: theme.colors.textPrimary,
            legendText = scope.legendText ?: theme.colors.textPrimary,
            border = scope.border ?: theme.colors.border,
            slices = scope.slices.ifEmpty { fallback },
        )
    }

    fun resolveTree(theme: DiagramTheme): ResolvedTreeColors {
        val scope = theme.treeColors
        return ResolvedTreeColors(
            nodeFill = scope.nodeFill ?: theme.nodeDefaults.fill?.toColor() ?: theme.colors.surface,
            nodeStroke = scope.nodeStroke ?: theme.nodeDefaults.stroke?.toColor() ?: theme.colors.border,
            nodeText = scope.nodeText ?: theme.nodeDefaults.textColor?.toColor() ?: theme.colors.textPrimary,
            rootFill = scope.rootFill ?: theme.colors.accent.withAlpha(0.12f),
            rootStroke = scope.rootStroke ?: theme.colors.accent,
            rootText = scope.rootText ?: scope.nodeText ?: theme.nodeDefaults.textColor?.toColor() ?: theme.colors.textPrimary,
            edge = scope.edge ?: theme.colors.border,
        )
    }

    fun resolveSequence(theme: DiagramTheme): ResolvedSequenceColors {
        val scope = theme.sequenceColors
        return ResolvedSequenceColors(
            headerFill = scope.headerFill ?: theme.colors.accent.withAlpha(0.12f),
            headerStroke = scope.headerStroke ?: theme.colors.accent,
            headerText = scope.headerText ?: theme.colors.textPrimary,
            lifeline = scope.lifeline ?: theme.colors.textSecondary,
            message = scope.message ?: theme.edgeDefaults.color?.toColor() ?: theme.colors.textPrimary,
            messageText = scope.messageText ?: scope.headerText ?: theme.colors.textPrimary,
            noteFill = scope.noteFill ?: theme.colors.warning.withAlpha(0.12f),
            noteStroke = scope.noteStroke ?: theme.colors.warning,
            noteText = scope.noteText ?: scope.messageText ?: theme.colors.textPrimary,
            activationFill = scope.activationFill ?: theme.colors.surface,
            activationStroke = scope.activationStroke ?: theme.colors.accent,
            fragmentStroke = scope.fragmentStroke ?: theme.colors.accentSecondary,
        )
    }

    fun resolveTimeSeries(theme: DiagramTheme): ResolvedTimeSeriesColors {
        val scope = theme.timeSeriesColors
        return ResolvedTimeSeriesColors(
            titleText = scope.titleText ?: theme.colors.textPrimary,
            labelText = scope.labelText ?: theme.colors.textPrimary,
            axis = scope.axis ?: theme.colors.border,
            border = scope.border ?: theme.colors.border,
            alternateRowBackground = scope.alternateRowBackground ?: theme.colors.textPrimary.withAlpha(0.03f),
            normalFill = scope.normalFill ?: theme.colors.textSecondary.withAlpha(0.45f),
            activeFill = scope.activeFill ?: theme.colors.accent.withAlpha(0.8f),
            doneFill = scope.doneFill ?: theme.colors.success.withAlpha(0.8f),
            criticalFill = scope.criticalFill ?: theme.colors.danger.withAlpha(0.82f),
            milestoneFill = scope.milestoneFill ?: theme.colors.warning.withAlpha(0.82f),
            slotFill = scope.slotFill ?: theme.colors.accent.withAlpha(0.05f),
            slotStroke = scope.slotStroke ?: theme.colors.border,
            itemFill = scope.itemFill ?: theme.colors.accent.withAlpha(0.14f),
            itemStroke = scope.itemStroke ?: theme.colors.accent,
        )
    }
}

internal data class ResolvedGraphColors(
    val background: Color,
    val nodeFill: Color,
    val nodeStroke: Color,
    val nodeText: Color,
    val edge: Color,
    val edgeLabelText: Color,
    val edgeLabelBackground: Color,
    val clusterFill: Color,
    val clusterStroke: Color,
    val noteFill: Color,
    val noteStroke: Color,
    val noteText: Color,
)

internal data class ResolvedPieColors(
    val titleText: Color,
    val legendText: Color,
    val border: Color,
    val slices: List<Color>,
)

internal data class ResolvedTreeColors(
    val nodeFill: Color,
    val nodeStroke: Color,
    val nodeText: Color,
    val rootFill: Color,
    val rootStroke: Color,
    val rootText: Color,
    val edge: Color,
)

internal data class ResolvedSequenceColors(
    val headerFill: Color,
    val headerStroke: Color,
    val headerText: Color,
    val lifeline: Color,
    val message: Color,
    val messageText: Color,
    val noteFill: Color,
    val noteStroke: Color,
    val noteText: Color,
    val activationFill: Color,
    val activationStroke: Color,
    val fragmentStroke: Color,
)

internal data class ResolvedPlantUmlSequenceColors(
    val headerFill: Color,
    val headerStroke: Color,
    val headerText: Color,
    val lifeline: Color,
    val message: Color,
    val messageText: Color,
    val noteFill: Color,
    val noteStroke: Color,
    val noteText: Color,
    val activationFill: Color,
    val activationStroke: Color,
    val fragmentStroke: Color,
    val referenceNoteFill: Color,
    val referenceNoteStroke: Color,
    val boxFill: Color,
    val boxStroke: Color,
    val boxText: Color,
)

internal data class ResolvedPlantUmlComponentColors(
    val canvas: Color,
    val clusterFill: Color,
    val clusterStroke: Color,
    val clusterText: Color,
    val clusterChipFill: Color,
    val nodeFill: Color,
    val nodeStroke: Color,
    val nodeText: Color,
    val noteFill: Color,
    val noteStroke: Color,
    val noteText: Color,
    val edgeColor: Color,
    val edgeLabelText: Color,
)

internal data class ResolvedTimeSeriesColors(
    val titleText: Color,
    val labelText: Color,
    val axis: Color,
    val border: Color,
    val alternateRowBackground: Color,
    val normalFill: Color,
    val activeFill: Color,
    val doneFill: Color,
    val criticalFill: Color,
    val milestoneFill: Color,
    val slotFill: Color,
    val slotStroke: Color,
    val itemFill: Color,
    val itemStroke: Color,
)

internal data class ResolvedJourneyColors(
    val background: Color,
    val text: Color,
    val secondaryText: Color,
    val axis: Color,
    val line: Color,
    val scoreFills: List<Color>,
    val accentPalette: List<Color>,
)

internal data class ResolvedKanbanColors(
    val background: Color,
    val columnFill: Color,
    val columnStroke: Color,
    val headerFill: Color,
    val cardFill: Color,
    val cardStroke: Color,
    val text: Color,
    val metaText: Color,
    val priorityVeryHigh: Color,
    val priorityHigh: Color,
    val priorityLow: Color,
    val priorityVeryLow: Color,
    val priorityDefault: Color,
    val badgeText: Color,
)

internal data class ResolvedSankeyColors(
    val background: Color,
    val text: Color,
    val border: Color,
    val nodePalette: List<Color>,
    val flowAlpha: Int,
)

internal data class ResolvedGitGraphColors(
    val background: Color,
    val text: Color,
    val lane: Color,
    val border: Color,
    val tagFill: Color,
    val tagText: Color,
    val branchPalette: List<Color>,
)

internal data class ResolvedXYChartColors(
    val background: Color,
    val xAxis: Color,
    val yAxis: Color,
    val text: Color,
    val xAxisLabel: Color,
    val yAxisLabel: Color,
    val xAxisTitle: Color,
    val yAxisTitle: Color,
    val dataLabel: Color,
    val plotPalette: List<Color>,
    val areaAlpha: Int,
)

internal data class ResolvedPlantUmlUsecaseColors(
    val canvas: Color,
    val clusterFill: Color,
    val clusterStroke: Color,
    val clusterText: Color,
    val actorFill: Color,
    val actorStroke: Color,
    val actorText: Color,
    val usecaseFill: Color,
    val usecaseStroke: Color,
    val usecaseText: Color,
    val noteFill: Color,
    val noteStroke: Color,
    val noteText: Color,
    val edge: Color,
    val edgeLabelText: Color,
)

internal data class ResolvedPlantUmlDeploymentColors(
    val canvas: Color,
    val clusterFill: Color,
    val clusterStroke: Color,
    val clusterText: Color,
    val clusterChipFill: Color,
    val nodeFill: Color,
    val nodeStroke: Color,
    val nodeText: Color,
    val noteFill: Color,
    val noteStroke: Color,
    val noteText: Color,
    val edge: Color,
    val edgeLabelText: Color,
)

internal data class ResolvedPlantUmlActivityColors(
    val actionFill: Color,
    val actionStroke: Color,
    val actionText: Color,
    val decisionFill: Color,
    val decisionStroke: Color,
    val decisionText: Color,
    val noteFill: Color,
    val noteStroke: Color,
    val noteText: Color,
    val barFill: Color,
    val barText: Color,
    val startFill: Color,
    val stopStroke: Color,
    val laneFill: Color,
    val laneStroke: Color,
    val laneText: Color,
    val edge: Color,
)

internal data class ClassStereotypePalette(
    val boxFill: Color,
    val headerFill: Color,
    val stroke: Color,
    val text: Color,
)

internal data class ResolvedPlantUmlClassColors(
    val base: ClassStereotypePalette,
    val entity: ClassStereotypePalette,
    val boundary: ClassStereotypePalette,
    val control: ClassStereotypePalette,
    val noteFill: Color,
    val noteStroke: Color,
    val noteText: Color,
    val namespaceFill: Color,
    val namespaceChipFill: Color,
    val namespaceStroke: Color,
    val namespaceText: Color,
    val edgeColor: Color,
    val commonTextColor: Color,
)

internal data class ResolvedPlantUmlStateColors(
    val stateFill: Color,
    val stateStroke: Color,
    val stateText: Color,
    val compositeFill: Color,
    val compositeStroke: Color,
    val noteFill: Color,
    val noteStroke: Color,
    val noteText: Color,
    val pseudoFill: Color,
    val edgeColor: Color,
)

internal data class ResolvedPlantUmlSaltColors(
    val rootFill: Color,
    val rootStroke: Color,
    val titleText: Color,
    val panelFill: Color,
    val panelStroke: Color,
    val mutedStroke: Color,
    val buttonFill: Color,
    val buttonStroke: Color,
    val inputText: Color,
    val accentFill: Color,
    val text: Color,
    val mutedText: Color,
)

private fun com.hrm.diagram.core.ir.ArgbColor.toColor(): Color = Color(argb)

private fun Color.withAlpha(alpha: Float): Color {
    val clamped = alpha.coerceIn(0f, 1f)
    val a = (clamped * 255f).toInt().coerceIn(0, 255)
    return Color((argb and 0x00FFFFFF) or (a shl 24))
}
