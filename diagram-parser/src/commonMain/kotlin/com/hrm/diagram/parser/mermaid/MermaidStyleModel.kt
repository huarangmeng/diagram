package com.hrm.diagram.parser.mermaid

import com.hrm.diagram.core.ir.ArgbColor

/**
 * KMP-native Mermaid style model (Mermaid.js semantics aligned).
 *
 * This file contains the core data structures only. Parsing is implemented in
 * [MermaidStyleParsers].
 */

/** Mermaid theme name (diagram-local selection). */
enum class MermaidThemeName { Default, Neutral, Dark, Forest, Base }

/**
 * Mermaid "themeVariables" as typed, validated tokens.
 *
 * Note: Mermaid.js derives some tokens from others. We keep both raw + normalized layers so
 * future derivation logic stays deterministic and debuggable.
 */
data class MermaidThemeTokens(
    val raw: Map<String, String>,
    val darkMode: Boolean? = null,
    val background: ArgbColor? = null,
    val fontFamily: String? = null,
    /** Mermaid uses px; we store the numeric value in px. */
    val fontSizePx: Float? = null,
    val primaryColor: ArgbColor? = null,
    val primaryTextColor: ArgbColor? = null,
    val primaryBorderColor: ArgbColor? = null,
    val lineColor: ArgbColor? = null,
    val textColor: ArgbColor? = null,
    // Flowchart variables
    val nodeBorder: ArgbColor? = null,
    val clusterBkg: ArgbColor? = null,
    val clusterBorder: ArgbColor? = null,
    val defaultLinkColor: ArgbColor? = null,
    val titleColor: ArgbColor? = null,
    val edgeLabelBackground: ArgbColor? = null,
    val nodeTextColor: ArgbColor? = null,
    // Sequence variables (minimum)
    val actorBkg: ArgbColor? = null,
    val actorBorder: ArgbColor? = null,
    val actorTextColor: ArgbColor? = null,
    val signalColor: ArgbColor? = null,
    val signalTextColor: ArgbColor? = null,
)

/** Diagram-level Mermaid styling configuration extracted from the source. */
data class MermaidStyleConfig(
    val theme: MermaidThemeName? = null,
    val themeTokens: MermaidThemeTokens? = null,
)

/**
 * Typed style declaration converted from Mermaid's `key:value` comma-separated strings.
 *
 * We intentionally keep this small and KMP-friendly. Unsupported keys are preserved in
 * [extras] and must not crash parsing.
 */
data class MermaidStyleDecl(
    val fill: ArgbColor? = null,
    val stroke: ArgbColor? = null,
    val strokeWidthPx: Float? = null,
    val strokeDashArrayPx: List<Float>? = null,
    val textColor: ArgbColor? = null,
    val fontFamily: String? = null,
    val fontSizePx: Float? = null,
    val fontWeight: Int? = null,
    val italic: Boolean? = null,
    val extras: Map<String, String> = emptyMap(),
)

data class MermaidStyleClass(
    val name: String,
    val decl: MermaidStyleDecl,
) {
    init { require(name.isNotBlank()) { "style class name must be non-blank" } }
}

/**
 * Minimal style rule types for Phase-1 alignment.
 *
 * Full `style`/`linkStyle` support will be added on top of these.
 */
sealed interface MermaidStyleRule {
    data class ClassDef(val classes: List<MermaidStyleClass>) : MermaidStyleRule
}

