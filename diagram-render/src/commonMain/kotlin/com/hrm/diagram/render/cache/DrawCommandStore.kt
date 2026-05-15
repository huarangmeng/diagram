package com.hrm.diagram.render.cache

import com.hrm.diagram.core.draw.DrawCommand

/**
 * Session-local store for draw commands.
 *
 * The public snapshot still carries a complete draw list for repaint-friendly UI backends, while
 * SessionPatch.addedDrawCommands must only contain commands that entered the stream in this
 * advance. Existing renderers can start with [updateFullFrame]; keyed renderers should use
 * [updateEntities] so updates to an existing node/edge do not look like newly appended commands.
 */
internal class DrawCommandStore {
    private val seenCommands: MutableSet<DrawCommand> = HashSet()
    private val entityCommands: LinkedHashMap<String, List<DrawCommand>> = LinkedHashMap()
    private var latestFullFrame: List<DrawCommand> = emptyList()

    val snapshot: List<DrawCommand>
        get() = latestFullFrame

    /**
     * Compatibility seam for existing renderers that still rebuild a complete frame. It prevents
     * the patch from carrying the whole frame on every append, but commands changed by reflow may
     * still appear as new because there is no stable entity key.
     */
    fun updateFullFrame(commands: List<DrawCommand>): DrawCommandDelta {
        val added = ArrayList<DrawCommand>()
        for (command in commands) {
            if (seenCommands.add(command)) added += command
        }
        latestFullFrame = commands
        return DrawCommandDelta(fullFrame = commands, addedCommands = added)
    }

    /**
     * Preferred seam for incremental renderers. New entity keys produce added commands; existing
     * keys update the full-frame snapshot without polluting addedDrawCommands.
     */
    fun updateEntities(entities: List<DrawEntity>): DrawCommandDelta {
        val added = ArrayList<DrawCommand>()
        val next = LinkedHashMap<String, List<DrawCommand>>(entities.size)
        for (entity in entities) {
            if (entity.key !in entityCommands) added += entity.commands
            next[entity.key] = entity.commands
        }
        entityCommands.clear()
        entityCommands.putAll(next)
        latestFullFrame = entities.flatMap { it.commands }
        for (command in latestFullFrame) seenCommands.add(command)
        return DrawCommandDelta(fullFrame = latestFullFrame, addedCommands = added)
    }

    fun clear() {
        seenCommands.clear()
        entityCommands.clear()
        latestFullFrame = emptyList()
    }
}

internal data class DrawEntity(
    val key: String,
    val commands: List<DrawCommand>,
)

internal data class DrawCommandDelta(
    val fullFrame: List<DrawCommand>,
    val addedCommands: List<DrawCommand>,
)
