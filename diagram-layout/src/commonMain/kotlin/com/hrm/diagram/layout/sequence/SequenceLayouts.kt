package com.hrm.diagram.layout.sequence

import com.hrm.diagram.core.ir.SequenceIR
import com.hrm.diagram.core.text.HeuristicTextMeasurer
import com.hrm.diagram.core.text.TextMeasurer
import com.hrm.diagram.layout.IncrementalLayout

/** Factory facade for [SequenceIncrementalLayout]. */
object SequenceLayouts {
    fun forSequence(textMeasurer: TextMeasurer = HeuristicTextMeasurer()): IncrementalLayout<SequenceIR> =
        SequenceIncrementalLayout(textMeasurer)
}
