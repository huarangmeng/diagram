package com.hrm.diagram.parser.plantuml

import com.hrm.diagram.core.streaming.IrPatchBatch

internal data class PlantUmlSkinparamScopeKeys(
    val fillKey: String? = null,
    val strokeKey: String? = null,
    val textKey: String? = null,
    val fontSizeKey: String? = null,
    val fontNameKey: String? = null,
    val lineThicknessKey: String? = null,
    val shadowingKey: String? = null,
)

internal class PlantUmlSkinparamSupport(
    private val styleExtras: MutableMap<String, String>,
    private val supportedScopes: Set<String>,
    private val scopeKeys: Map<String, PlantUmlSkinparamScopeKeys>,
    private val directKeys: Map<String, String>,
    private val warnUnsupported: (String) -> IrPatchBatch,
    private val emptyBatch: () -> IrPatchBatch,
) {
    var pendingScope: String? = null
    private val orderedScopes: List<String> = supportedScopes.sortedByDescending { it.length }

    fun acceptDirective(line: String): IrPatchBatch {
        val body = line.substringAfter(" ", "").trim()
        val normalizedScope = body.substringBefore(' ', body).substringBefore('{').trim().lowercase()
        if (body.endsWith("{") && normalizedScope in supportedScopes) {
            pendingScope = normalizedScope
            return emptyBatch()
        }
        if (normalizedScope in supportedScopes && body.length > normalizedScope.length) {
            return acceptScopedEntry(normalizedScope, body.substring(normalizedScope.length).trim())
        }
        val entry = parseEntry(body) ?: return warnUnsupported(line)
        val normalizedEntryKey = entry.first.lowercase()
        val extraKey = directKeys[normalizedEntryKey]
            ?: resolveScopedDirectKey(normalizedEntryKey)
            ?: return warnUnsupported(line)
        return store(extraKey, entry.second, line)
    }

    fun acceptScopedEntry(scope: String, line: String): IrPatchBatch {
        val entry = parseEntry(line) ?: return warnUnsupported("skinparam $scope $line")
        val keys = scopeKeys[scope.lowercase()] ?: return warnUnsupported("skinparam $scope $line")
        val extraKey = when (entry.first.lowercase()) {
            "backgroundcolor" -> keys.fillKey
            "bordercolor" -> keys.strokeKey
            "fontcolor" -> keys.textKey
            "fontsize" -> keys.fontSizeKey
            "fontname" -> keys.fontNameKey
            "linethickness" -> keys.lineThicknessKey
            "shadowing" -> keys.shadowingKey
            else -> null
        } ?: return warnUnsupported("skinparam $scope $line")
        return store(extraKey, entry.second, "skinparam $scope $line")
    }

    private fun resolveScopedDirectKey(entryKey: String): String? {
        for (scope in orderedScopes) {
            if (!entryKey.startsWith(scope)) continue
            val suffix = entryKey.removePrefix(scope)
            val keys = scopeKeys[scope] ?: continue
            return when (suffix) {
                "backgroundcolor" -> keys.fillKey
                "bordercolor" -> keys.strokeKey
                "fontcolor" -> keys.textKey
                "fontsize" -> keys.fontSizeKey
                "fontname" -> keys.fontNameKey
                "linethickness" -> keys.lineThicknessKey
                "shadowing" -> keys.shadowingKey
                else -> null
            }
        }
        return null
    }

    private fun store(key: String, value: String, fallbackWarning: String): IrPatchBatch {
        if (value.isBlank()) return warnUnsupported(fallbackWarning)
        styleExtras[key] = value
        return emptyBatch()
    }

    private fun parseEntry(line: String): Pair<String, String>? {
        val match = ENTRY.matchEntire(line.trim()) ?: return null
        val key = match.groupValues[1].trim()
        val value = match.groupValues[2].trim()
        if (key.isEmpty() || value.isEmpty()) return null
        return key to value
    }

    private companion object {
        val ENTRY = Regex("^([A-Za-z][A-Za-z0-9]*)\\s*:??\\s*(.+)$")
    }
}
