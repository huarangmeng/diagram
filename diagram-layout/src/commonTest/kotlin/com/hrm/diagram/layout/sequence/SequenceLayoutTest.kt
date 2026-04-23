package com.hrm.diagram.layout.sequence

import com.hrm.diagram.core.ir.MessageKind
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.Participant
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.ir.SequenceFragment
import com.hrm.diagram.core.ir.FragmentKind
import com.hrm.diagram.core.ir.SequenceIR
import com.hrm.diagram.core.ir.SequenceMessage
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.core.layout.LayoutOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SequenceLayoutTest {

    private val layout = SequenceIncrementalLayout()
    private val opts = LayoutOptions()

    private fun ir(
        participants: List<Participant>,
        messages: List<SequenceMessage>,
        fragments: List<SequenceFragment> = emptyList(),
    ): SequenceIR = SequenceIR(
        participants = participants,
        messages = messages,
        fragments = fragments,
        sourceLanguage = SourceLanguage.MERMAID,
    )

    @Test fun two_participant_single_message_positions() {
        val a = Participant(NodeId("A"))
        val b = Participant(NodeId("B"))
        val msg = SequenceMessage(NodeId("A"), NodeId("B"), kind = MessageKind.Sync)
        val l = layout.layout(ir(listOf(a, b), listOf(msg)), opts)
        assertEquals(2, l.nodePositions.size)
        assertEquals(1, l.edgeRoutes.size)
        // Headers share a top.
        assertEquals(l.nodePositions.getValue(NodeId("A")).top, l.nodePositions.getValue(NodeId("B")).top)
        // Lane B is to the right of lane A.
        assertTrue(l.nodePositions.getValue(NodeId("B")).left > l.nodePositions.getValue(NodeId("A")).right - 0.01f)
    }

    @Test fun activation_rect_created() {
        val a = Participant(NodeId("A"))
        val b = Participant(NodeId("B"))
        val m1 = SequenceMessage(NodeId("A"), NodeId("B"), kind = MessageKind.Sync, activate = true)
        val m2 = SequenceMessage(NodeId("B"), NodeId("A"), kind = MessageKind.Reply, deactivate = true)
        val l = layout.layout(ir(listOf(a, b), listOf(m1, m2)), opts)
        assertTrue(l.clusterRects.keys.any { it.value.contains("#act#") })
    }

    @Test fun note_over_spans_both_lanes() {
        val a = Participant(NodeId("A"))
        val b = Participant(NodeId("B"))
        val n = SequenceMessage(NodeId("A"), NodeId("B"), kind = MessageKind.Note, label = RichLabel.Plain("hi"))
        val l = layout.layout(ir(listOf(a, b), listOf(n)), opts)
        val noteRect = l.clusterRects.entries.first { it.key.value.startsWith("note#") }.value
        val laneA = l.nodePositions.getValue(NodeId("A"))
        val laneB = l.nodePositions.getValue(NodeId("B"))
        assertTrue(noteRect.left <= (laneA.left + laneA.right) / 2f)
        assertTrue(noteRect.right >= (laneB.left + laneB.right) / 2f)
    }

    @Test fun fragment_cluster_rect() {
        val a = Participant(NodeId("A"))
        val b = Participant(NodeId("B"))
        val msg = SequenceMessage(NodeId("A"), NodeId("B"), kind = MessageKind.Sync)
        val frag = SequenceFragment(FragmentKind.Loop, RichLabel.Plain("loop"), listOf(listOf(msg)))
        val l = layout.layout(ir(listOf(a, b), listOf(msg), listOf(frag)), opts)
        assertTrue(l.clusterRects.keys.any { it.value.startsWith("frag#") })
    }

    @Test fun lane_widths_based_on_participant_labels() {
        val short = Participant(NodeId("X"))
        val long = Participant(NodeId("Y"), label = RichLabel.Plain("VeryLongParticipantLabel"))
        val l = layout.layout(ir(listOf(short, long), emptyList()), opts)
        val xw = l.nodePositions.getValue(NodeId("X")).size.width
        val yw = l.nodePositions.getValue(NodeId("Y")).size.width
        assertTrue(yw >= xw)
    }

    @Test fun incremental_existing_rows_pinned() {
        val a = Participant(NodeId("A"))
        val b = Participant(NodeId("B"))
        val m1 = SequenceMessage(NodeId("A"), NodeId("B"), kind = MessageKind.Sync)
        val l1 = layout.layout(ir(listOf(a, b), listOf(m1)), opts)
        val m2 = SequenceMessage(NodeId("B"), NodeId("A"), kind = MessageKind.Reply)
        val l2 = layout.layout(l1, ir(listOf(a, b), listOf(m1, m2)), opts)
        // First message Y must equal previous.
        assertEquals(l1.edgeRoutes[0].points[0].y, l2.edgeRoutes[0].points[0].y)
        // Header positions pinned.
        assertEquals(l1.nodePositions, l2.nodePositions.filterKeys { it in l1.nodePositions.keys })
    }
}
