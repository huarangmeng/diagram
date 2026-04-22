package com.hrm.diagram.core.ir

/**
 * The original DSL a [DiagramModel] was parsed from.
 * Used by downstream layers to apply language-specific cosmetic defaults.
 */
@DiagramApiAlias
enum class SourceLanguage { MERMAID, PLANTUML, DOT }

/** Suggested layout direction. Concrete layouts may override. */
enum class Direction { TB, BT, LR, RL, RADIAL }

/**
 * Free-form, source-language-flavoured hints. Layout/render layers consume opportunistically;
 * unknown keys MUST be ignored, never error out.
 */
data class StyleHints(
    val direction: Direction? = null,
    val theme: String? = null,
    val extras: Map<String, String> = emptyMap(),
)

/**
 * Root contract for every parsed diagram. All concrete IRs are immutable [data class]es
 * implementing this interface (see [com.hrm.diagram.core.ir] families).
 */
sealed interface DiagramModel {
    val title: String?
    val sourceLanguage: SourceLanguage
    val styleHints: StyleHints
}
