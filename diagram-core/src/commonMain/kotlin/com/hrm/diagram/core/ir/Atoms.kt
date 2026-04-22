package com.hrm.diagram.core.ir

import kotlin.jvm.JvmInline

@JvmInline
value class NodeId(val value: String) {
    init { require(value.isNotEmpty()) { "NodeId must be non-empty" } }
}

@JvmInline
value class PortId(val value: String)

/** A semantic anchor on a node's perimeter (used by parsers that support node:port syntax). */
data class Port(
    val id: PortId,
    val label: RichLabel? = null,
    val side: PortSide? = null,
)

enum class PortSide { TOP, RIGHT, BOTTOM, LEFT }

sealed interface RichLabel {
    val isEmpty: Boolean

    data class Plain(val text: String) : RichLabel {
        override val isEmpty: Boolean get() = text.isEmpty()
    }

    /** Limited Markdown subset: bold, italic, code, line break, link. Renderers MUST NOT pull external deps. */
    data class Markdown(val source: String) : RichLabel {
        override val isEmpty: Boolean get() = source.isEmpty()
    }

    /** Already-sanitised HTML-like label (PlantUML / DOT html-label). Renderers handle a fixed subset. */
    data class Html(val html: String) : RichLabel {
        override val isEmpty: Boolean get() = html.isEmpty()
    }

    companion object {
        val Empty: RichLabel = Plain("")
        fun of(text: String): RichLabel = if (text.isEmpty()) Empty else Plain(text)
    }
}
