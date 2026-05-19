package com.hrm.diagram.core.streaming

import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.streaming.NodeIds.anonymous
import com.hrm.diagram.core.streaming.NodeIds.anonymousIndexed
import com.hrm.diagram.core.streaming.NodeIds.explicit

/**
 * Stable [NodeId] derivation helpers.
 *
 * Streaming layouts re-use coordinates by keying on [NodeId]; therefore IDs MUST be stable across
 * incremental re-runs of the same source prefix. The rules (also in `docs/streaming.md` §3.3 and
 * `docs/rules.md` §F3):
 *
 * 1. If the source declares an explicit identifier, use it verbatim → [explicit].
 * 2. For anonymous nodes (e.g. inline `[Foo]` in a Mermaid edge), derive a deterministic id from
 *    the absolute source offset → [anonymous]. Format: `"$anon@<offset>"`.
 * 3. For nested anonymous nodes that share an offset (rare; same line, multiple unnamed shapes),
 *    use [anonymousIndexed] which appends a 0-based ordinal: `"$anon@<offset>#<i>"`.
 *
 * Parsers MUST go through these helpers; hand-rolled `NodeId(...)` for anonymous nodes is forbidden
 * by `docs/rules.md` §F3.
 */
object NodeIds {
    private const val ANON_PREFIX = "\$anon@"

    fun explicit(name: String): NodeId = NodeId(name)

    fun anonymous(absoluteOffset: Int): NodeId {
        require(absoluteOffset >= 0) { "offset must be non-negative, got $absoluteOffset" }
        return NodeId("$ANON_PREFIX$absoluteOffset")
    }

    fun anonymousIndexed(absoluteOffset: Int, ordinal: Int): NodeId {
        require(absoluteOffset >= 0) { "offset must be non-negative, got $absoluteOffset" }
        require(ordinal >= 0) { "ordinal must be non-negative, got $ordinal" }
        return NodeId("$ANON_PREFIX$absoluteOffset#$ordinal")
    }

    /** True IFF [id] was produced by [anonymous] / [anonymousIndexed]. */
    fun isAnonymous(id: NodeId): Boolean = id.value.startsWith(ANON_PREFIX)
}
