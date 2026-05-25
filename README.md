# Diagram

[![Kotlin](https://img.shields.io/badge/Kotlin-2.3.20-blue.svg)](https://kotlinlang.org)
[![Compose Multiplatform](https://img.shields.io/badge/Compose%20Multiplatform-1.10.3-brightgreen.svg)](https://www.jetbrains.com/lp/compose-multiplatform/)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.huarangmeng/diagram-render.svg?color=orange&label=Maven%20Central)](https://central.sonatype.com/search?q=io.github.huarangmeng%3Adiagram-render)
[![Android API](https://img.shields.io/badge/Android%20API-23%2B-brightgreen.svg)](https://android-arsenal.com/api?level=23)
[![Syntax](https://img.shields.io/badge/Syntax-Mermaid%20%7C%20PlantUML%20%7C%20DOT-orange.svg)](./docs/syntax-compat/)

A Kotlin Multiplatform diagram rendering SDK with self-hosted parsing, layout, and rendering for Mermaid, PlantUML, and Graphviz DOT. The project targets Android, iOS, Desktop (JVM), and Web (JS/Wasm), with streaming incremental rendering as a first-class use case.

[中文版本](./README_zh.md)

## Preview

| Mermaid | PlantUML | DOT |
|---|---|---|
| ![Mermaid preview](./images/mermaid.png) | ![PlantUML preview](./images/plantuml.png) | ![DOT preview](./images/dot.png) |

## Key Features

- **Three Syntax Families**: Parses and renders Mermaid, PlantUML, and Graphviz DOT in one unified KMP codebase.
- **Streaming-First Pipeline**: `Diagram.session()` supports append-only incremental parsing, layout, and draw updates for LLM and live-preview scenarios.
- **Self-Hosted Engine**: Parser, IR, layout, and renderer are implemented in Kotlin without relying on ELK, dagre, Graphviz native, or JS interop shortcuts.
- **Compose Multiplatform Rendering**: `DiagramCanvas` renders the same draw-command pipeline on Android, iOS, Desktop, JS, and Wasm.
- **Incremental Performance Hooks**: Includes cached text measurement, stable entity keys, dirty-edge routing, and viewport culling via `DrawCommandIndex`.
- **Broad Syntax Coverage**: The demo gallery already exercises Mermaid, PlantUML, and DOT across dozens of diagram families and official-sample-style cases.

## Current Coverage

- **Mermaid**: flowchart, sequence, class, state, ER, journey, gantt, pie, gauge, gitGraph, mindmap, timeline, requirement, architecture, C4, sankey, xyChart, quadrantChart, block, kanban, packet.
- **PlantUML**: sequence, usecase, class, activity, component, state, object, deployment, ERD, timing, salt/wireframe, Archimate, C4, gantt, mindmap, WBS, ditaa, network, JSON, YAML, pie/chart/xy.
- **DOT**: digraph, graph, clusters, rank/style/color/label subsets, HTML-like label cleanup, ports, and streaming statement-level parsing.

Detailed compatibility notes live in [Mermaid](./docs/syntax-compat/mermaid.md), [PlantUML](./docs/syntax-compat/plantuml.md), and [DOT](./docs/syntax-compat/dot.md).

## Modules

- `:diagram-core`: shared IR, geometry, theme, diagnostics, draw commands, and export-facing primitives.
- `:diagram-layout`: self-hosted layout algorithms such as Sugiyama, tree, timeline, chart, and structural layouts.
- `:diagram-parser`: Mermaid, PlantUML, and DOT parsers plus lowering into the shared IR.
- `:diagram-render`: Compose rendering facade, streaming session API, viewport-aware canvas, and top-level user-facing entry points.
- `:composeApp`: cross-platform demo gallery with built-in samples.
- `:androidApp`: Android host app for local verification.

## Installation

Add `diagram-render` if you want the full parsing, layout, and Compose rendering stack.

```toml
[versions]
diagram = "0.1.0"

[libraries]
diagram-render = { module = "io.github.huarangmeng:diagram-render", version.ref = "diagram" }
```

```kotlin
dependencies {
    implementation(libs.diagram.render)
}
```

Use lower-level modules directly only when you need a subset of the stack.

```kotlin
dependencies {
    implementation("io.github.huarangmeng:diagram-core:0.1.0")
    implementation("io.github.huarangmeng:diagram-layout:0.1.0")
    implementation("io.github.huarangmeng:diagram-parser:0.1.0")
}
```

## Usage

### Streaming Session

The primary public workflow is a streaming session. Feed source chunks incrementally and finish when the stream ends.

```kotlin
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.render.Diagram

val session = Diagram.session(SourceLanguage.MERMAID)
session.append("""
    flowchart LR
      A[Start] --> B{Decide}
""".trimIndent())
session.append("\n      B -->|yes| C[Ship]\n      B -->|no| D[Stop]\n")

val snapshot = session.finish()
println(snapshot.diagnostics)
```

### Compose Preview

`rememberDiagramSession(...)` wires Compose text measurement into the layout pipeline, and `DiagramCanvas(...)` renders the latest snapshot.

```kotlin
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.render.compose.DiagramCanvas
import com.hrm.diagram.render.compose.rememberDiagramSession

@Composable
fun MermaidPreview(source: String) {
    val session = rememberDiagramSession(
        language = SourceLanguage.MERMAID,
        key = source,
    )
    val snapshot by session.state.collectAsState()

    LaunchedEffect(session, source) {
        session.append(source)
        session.finish()
    }

    DiagramCanvas(
        snapshot = snapshot,
        modifier = Modifier.fillMaxSize(),
        panZoomEnabled = true,
    )
}
```

## Export Status

- **SVG**: The shared draw-command export path is already part of the architecture and public contracts.
- **PNG/JPEG**: Export contracts exist in the core API, while the full multi-platform export pipeline is still being completed as part of the release roadmap.

## Run Locally

### Demo Applications

- **Android**: `./gradlew :androidApp:assembleDebug`
- **Desktop**: `./gradlew :composeApp:run`
- **Web (Wasm)**: `./gradlew :composeApp:wasmJsBrowserDevelopmentRun`
- **Web (JS)**: `./gradlew :composeApp:jsBrowserDevelopmentRun`
- **iOS**: open `iosApp/` in Xcode

### Tests

```bash
./gradlew allTests
```

## Documentation

- [Architecture](./docs/architecture.md)
- [Public API Contract](./docs/api.md)
- [Streaming Contract](./docs/streaming.md)
- [Plan and Phase Status](./docs/plan.md)
- [Testing Strategy](./docs/testing.md)
- [Contributing Guide](./docs/contributing.md)
