package com.hrm.diagram.core.streaming

import com.hrm.diagram.core.ir.Cluster
import com.hrm.diagram.core.ir.Diagnostic
import com.hrm.diagram.core.ir.Edge
import com.hrm.diagram.core.ir.Node
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.NodeStyle

/**
 * Append-only IR delta produced by the parser/lower layer for one streaming chunk.
 *
 * See `docs/streaming.md` §3.3 and `docs/rules.md` §F1.
 *
 * Hard rules:
 * - Patches NEVER remove anything. Once a node/edge/cluster/diagnostic enters the IR it stays
 *   for the lifetime of the `DiagramSession`.
 * - The ONLY in-place mutation allowed is [UpdateAttr], and only against nodes added in the
 *   *same* chunk (parser-internal lowering correction). Layout/render layers MUST NOT depend on
 *   pre-update attribute values.
 * - Adding a [Node]/[Edge]/[Cluster] referencing an unknown [NodeId] is allowed (forward
 *   reference); downstream layers should keep a deferred-resolution buffer and emit a
 *   warning [Diagnostic] on `finish()` if still unresolved.
 */
public sealed interface IrPatch {
    public data class AddNode(val node: Node) : IrPatch
    public data class AddEdge(val edge: Edge) : IrPatch
    public data class AddCluster(val cluster: Cluster) : IrPatch
    /** In-chunk attribute update for a node added by an earlier patch *in the same chunk*. */
    public data class UpdateAttr(val target: NodeId, val style: NodeStyle) : IrPatch
    public data class AddDiagnostic(val diagnostic: Diagnostic) : IrPatch
}

/**
 * A monotonically-versioned batch of patches produced by one parser advance.
 *
 * `seq` is strictly increasing per session; consumers may use it to dedupe / order
 * cross-thread deliveries.
 */
public data class IrPatchBatch(
    val seq: Long,
    val patches: List<IrPatch>,
) {
    public val isEmpty: Boolean get() = patches.isEmpty()
}
