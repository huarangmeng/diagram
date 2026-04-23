package com.hrm.diagram.layout.sugiyama

import com.hrm.diagram.core.draw.Size
import com.hrm.diagram.core.ir.GraphIR
import com.hrm.diagram.layout.IncrementalLayout

/**
 * Public entry point for the Sugiyama layered layout. Hides the implementation class so callers
 * only see the [IncrementalLayout] contract.
 */
object SugiyamaLayouts {
    fun forGraph(
        defaultNodeSize: Size = Size(120f, 48f),
        nodeSizeOf: ((com.hrm.diagram.core.ir.NodeId) -> Size)? = null,
    ): IncrementalLayout<GraphIR> = SugiyamaIncrementalLayout(
        defaultNodeSize = defaultNodeSize,
        nodeSizeOf = nodeSizeOf ?: { defaultNodeSize },
    )
}
