package com.hrm.diagram.core.streaming

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class NodeIdsTest {

    @Test
    fun explicit_uses_source_name_verbatim() {
        assertEquals("foo", NodeIds.explicit("foo").value)
    }

    @Test
    fun anonymous_format_is_stable_across_runs() {
        // Same offset MUST always produce the same id (layout coord reuse depends on this).
        assertEquals(NodeIds.anonymous(42), NodeIds.anonymous(42))
        assertEquals("\$anon@42", NodeIds.anonymous(42).value)
    }

    @Test
    fun anonymous_distinguishes_offsets() {
        assertNotEquals(NodeIds.anonymous(10), NodeIds.anonymous(11))
    }

    @Test
    fun anonymous_indexed_appends_ordinal() {
        assertEquals("\$anon@7#0", NodeIds.anonymousIndexed(7, 0).value)
        assertEquals("\$anon@7#1", NodeIds.anonymousIndexed(7, 1).value)
        assertNotEquals(NodeIds.anonymous(7), NodeIds.anonymousIndexed(7, 0))
    }

    @Test
    fun isAnonymous_classifies_correctly() {
        assertTrue(NodeIds.isAnonymous(NodeIds.anonymous(0)))
        assertTrue(NodeIds.isAnonymous(NodeIds.anonymousIndexed(0, 3)))
        assertEquals(false, NodeIds.isAnonymous(NodeIds.explicit("foo")))
    }

    @Test
    fun rejects_negative_offset() {
        assertFailsWith<IllegalArgumentException> { NodeIds.anonymous(-1) }
        assertFailsWith<IllegalArgumentException> { NodeIds.anonymousIndexed(-1, 0) }
        assertFailsWith<IllegalArgumentException> { NodeIds.anonymousIndexed(0, -1) }
    }
}
