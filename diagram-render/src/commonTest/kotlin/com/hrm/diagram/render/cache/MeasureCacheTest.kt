package com.hrm.diagram.render.cache

import com.hrm.diagram.core.draw.Color
import com.hrm.diagram.core.draw.DrawCommand
import com.hrm.diagram.core.draw.FontSpec
import com.hrm.diagram.core.draw.Point
import com.hrm.diagram.core.draw.Rect
import com.hrm.diagram.core.draw.Size
import com.hrm.diagram.core.ir.ClassIR
import com.hrm.diagram.core.ir.ClassNode
import com.hrm.diagram.core.ir.ClassRelation
import com.hrm.diagram.core.ir.ClassRelationKind
import com.hrm.diagram.core.ir.GraphIR
import com.hrm.diagram.core.ir.Node
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.Participant
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.ir.SequenceIR
import com.hrm.diagram.core.ir.SequenceMessage
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.core.text.TextMeasurer
import com.hrm.diagram.core.text.TextMetrics
import com.hrm.diagram.layout.LaidOutDiagram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MeasureCacheTest {

    private val font = FontSpec(family = "sans-serif", sizeSp = 14f)

    @Test
    fun get_or_put_returns_cached_value_on_second_call() {
        val cache = MeasureCache<Size>(maxEntries = 4)
        var called = 0
        val key = MeasureKey("hello", font)
        val first = cache.getOrPut(key) { called++; Size(40f, 12f) }
        val second = cache.getOrPut(key) { called++; Size(99f, 99f) }
        assertEquals(Size(40f, 12f), first)
        assertEquals(first, second)
        assertEquals(1, called)
        assertEquals(1L, cache.hits)
        assertEquals(1L, cache.misses)
    }

    @Test
    fun lru_evicts_least_recently_used_entry() {
        val cache = MeasureCache<Size>(maxEntries = 2)
        val k1 = MeasureKey("a", font)
        val k2 = MeasureKey("b", font)
        val k3 = MeasureKey("c", font)
        cache.put(k1, Size(1f, 1f))
        cache.put(k2, Size(2f, 2f))
        // Touch k1 to mark it MRU; k2 becomes LRU.
        cache.get(k1)
        cache.put(k3, Size(3f, 3f))
        assertEquals(2, cache.size)
        assertNull(cache.get(k2), "k2 must have been evicted")
        assertEquals(Size(1f, 1f), cache.get(k1))
        assertEquals(Size(3f, 3f), cache.get(k3))
    }

    @Test
    fun put_overwrites_and_does_not_grow_size() {
        val cache = MeasureCache<Size>(maxEntries = 4)
        val k = MeasureKey("x", font)
        cache.put(k, Size(1f, 1f))
        cache.put(k, Size(2f, 2f))
        assertEquals(1, cache.size)
        assertEquals(Size(2f, 2f), cache.get(k))
    }

    @Test
    fun keys_with_different_max_width_are_distinct() {
        val cache = MeasureCache<Size>()
        val a = MeasureKey("foo", font, maxWidth = 100f)
        val b = MeasureKey("foo", font, maxWidth = 200f)
        val c = MeasureKey("foo", font, maxWidth = null)
        cache.put(a, Size(80f, 12f))
        cache.put(b, Size(180f, 12f))
        cache.put(c, Size(40f, 12f))
        assertEquals(3, cache.size)
        assertEquals(Size(80f, 12f), cache.get(a))
        assertEquals(Size(180f, 12f), cache.get(b))
        assertEquals(Size(40f, 12f), cache.get(c))
    }

    @Test
    fun clear_resets_counters() {
        val cache = MeasureCache<Size>()
        val k = MeasureKey("x", font)
        cache.put(k, Size(1f, 1f)); cache.get(k); cache.get(MeasureKey("y", font))
        assertTrue(cache.hits > 0); assertTrue(cache.misses > 0)
        cache.clear()
        assertEquals(0, cache.size)
        assertEquals(0L, cache.hits)
        assertEquals(0L, cache.misses)
    }

    @Test
    fun rejects_zero_max_entries() {
        assertFailsWith<IllegalArgumentException> { MeasureCache<Size>(maxEntries = 0) }
    }

    @Test
    fun heavy_churn_stays_within_cap() {
        val cache = MeasureCache<Size>(maxEntries = 10)
        repeat(10_000) { i ->
            cache.put(MeasureKey("k$i", font), Size(i.toFloat(), 1f))
        }
        assertEquals(10, cache.size)
    }

    @Test
    fun cached_text_measurer_delegates_only_on_miss() {
        var calls = 0
        val delegate = object : TextMeasurer {
            override fun measure(text: String, font: FontSpec, maxWidth: Float?): TextMetrics {
                calls++
                return TextMetrics(width = text.length * 10f, height = 12f, ascent = 9f)
            }
        }
        val cached = CachedTextMeasurer(delegate, maxEntries = 4)

        val first = cached.measure("hello", font, maxWidth = 100f)
        val second = cached.measure("hello", font, maxWidth = 100f)

        assertEquals(first, second)
        assertEquals(1, calls)
        assertEquals(1L, cached.hits)
        assertEquals(1L, cached.misses)
    }

    @Test
    fun structured_draw_entities_use_ir_entity_keys() {
        val commands = listOf(
            DrawCommand.DrawText(
                text = "Alpha Node",
                origin = Point(10f, 10f),
                font = font,
                color = Color.Black,
            ),
            DrawCommand.DrawText(
                text = "Alpha Node",
                origin = Point(80f, 80f),
                font = font,
                color = Color.Black,
            ),
        )
        val ir = GraphIR(
            nodes = listOf(Node(id = NodeId("A"), label = RichLabel.Plain("Alpha Node"))),
            sourceLanguage = SourceLanguage.MERMAID,
        )

        val entities = structuredDrawEntities("mermaid", ir, laidOut = null, commands)

        assertEquals(1, entities.size)
        assertEquals("mermaid.node.A", entities.single().key)
        assertEquals(commands, entities.single().commands)
        assertTrue(entities.none { ".text." in it.key || ".command." in it.key })
    }

    @Test
    fun structured_draw_entities_cover_sequence_and_class_families() {
        val commands = listOf(
            DrawCommand.DrawText("participant", Point(0f, 0f), font, Color.Black),
            DrawCommand.DrawText("message", Point(0f, 20f), font, Color.Black),
            DrawCommand.DrawText("class", Point(0f, 40f), font, Color.Black),
        )
        val sequence = SequenceIR(
            participants = listOf(Participant(NodeId("Alice")), Participant(NodeId("Bob"))),
            messages = listOf(SequenceMessage(NodeId("Alice"), NodeId("Bob"), RichLabel.Plain("hi"))),
            sourceLanguage = SourceLanguage.MERMAID,
        )
        val classIr = ClassIR(
            classes = listOf(ClassNode(NodeId("User"), "User")),
            relations = listOf(ClassRelation(NodeId("User"), NodeId("Repo"), ClassRelationKind.Association)),
            sourceLanguage = SourceLanguage.PLANTUML,
        )

        val sequenceKeys = structuredDrawEntities("mermaid", sequence, laidOut = null, commands).map { it.key }
        val classKeys = structuredDrawEntities("plantuml", classIr, laidOut = null, commands).map { it.key }

        assertEquals(listOf("mermaid.participant.Alice", "mermaid.participant.Bob", "mermaid.message.0.Alice->Bob"), sequenceKeys)
        assertEquals(listOf("plantuml.class.relation.0.User->Repo", "plantuml.class.node.User"), classKeys)
    }

    @Test
    fun structured_draw_entities_prefer_layout_bounds_when_available() {
        val nodeA = NodeId("A")
        val nodeB = NodeId("B")
        val ir = GraphIR(
            nodes = listOf(
                Node(id = nodeA, label = RichLabel.Plain("A")),
                Node(id = nodeB, label = RichLabel.Plain("B")),
            ),
            sourceLanguage = SourceLanguage.MERMAID,
        )
        val laidOut = LaidOutDiagram(
            source = ir,
            nodePositions = mapOf(
                nodeA to Rect.ltrb(0f, 0f, 50f, 50f),
                nodeB to Rect.ltrb(100f, 0f, 150f, 50f),
            ),
            edgeRoutes = emptyList(),
            bounds = Rect.ltrb(0f, 0f, 150f, 50f),
        )
        val commands = listOf(
            DrawCommand.FillRect(Rect.ltrb(102f, 2f, 148f, 48f), Color.Black),
            DrawCommand.FillRect(Rect.ltrb(2f, 2f, 48f, 48f), Color.Black),
        )

        val entities = structuredDrawEntities("mermaid", ir, laidOut, commands)

        assertEquals(listOf("mermaid.node.B", "mermaid.node.A"), entities.map { it.key })
        assertEquals(commands[0], entities[0].commands.single())
        assertEquals(commands[1], entities[1].commands.single())
    }
}
