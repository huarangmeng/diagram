# Diagram

[![Kotlin](https://img.shields.io/badge/Kotlin-2.3.20-blue.svg)](https://kotlinlang.org)
[![Compose Multiplatform](https://img.shields.io/badge/Compose%20Multiplatform-1.10.3-brightgreen.svg)](https://www.jetbrains.com/lp/compose-multiplatform/)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.huarangmeng/diagram-render.svg?color=orange&label=Maven%20Central)](https://central.sonatype.com/search?q=io.github.huarangmeng%3Adiagram-render)
[![Android API](https://img.shields.io/badge/Android%20API-23%2B-brightgreen.svg)](https://android-arsenal.com/api?level=23)
[![Syntax](https://img.shields.io/badge/Syntax-Mermaid%20%7C%20PlantUML%20%7C%20DOT-orange.svg)](./docs/syntax-compat/)

一个基于 Kotlin Multiplatform 与 Compose Multiplatform 的跨平台图表渲染 SDK，面向 Mermaid、PlantUML 和 Graphviz DOT 提供自研解析、自研布局与统一渲染能力。项目覆盖 Android、iOS、Desktop (JVM) 与 Web (JS/Wasm)，并把流式增量渲染作为一等公民用例。

[English Version](./README.md)

## 图像预览

### Mermaid

![Mermaid 预览](./images/mermaid.png)

### PlantUML

![PlantUML 预览](./images/plantuml.png)

### DOT

![DOT 预览](./images/dot.png)

## 核心特性

- **三套语法统一支持**：在同一套 KMP 代码库中解析并渲染 Mermaid、PlantUML 和 Graphviz DOT。
- **流式优先链路**：`Diagram.session()` 支持 append-only 的增量解析、布局和绘制更新，适合 LLM 输出和实时预览。
- **完全自研引擎**：解析器、IR、布局和渲染全部使用 Kotlin 实现，不依赖 ELK、dagre、Graphviz native 或 JS 借力方案。
- **Compose 多端渲染**：`DiagramView(source = ...)` 是面向应用层的默认 Composable，并在内部接管语法识别、snapshot 与增量更新。
- **增量性能收口**：内置文本测量缓存、稳定实体 key、dirty edge routing 和 `DrawCommandIndex` 视口裁剪。
- **较宽的语法覆盖面**：当前 demo gallery 已覆盖 Mermaid、PlantUML、DOT 的大量图族和接近官方样例风格的场景。

## 当前覆盖范围

- **Mermaid**：flowchart、sequence、class、state、ER、journey、gantt、pie、gauge、gitGraph、mindmap、timeline、requirement、architecture、C4、sankey、xyChart、quadrantChart、block、kanban、packet。
- **PlantUML**：sequence、usecase、class、activity、component、state、object、deployment、ERD、timing、salt/wireframe、Archimate、C4、gantt、mindmap、WBS、ditaa、network、JSON、YAML、pie/chart/xy。
- **DOT**：digraph、graph、cluster、rank/style/color/label 子集、HTML-like label 清洗、端口锚点与 statement 级 streaming parser。

更细的兼容矩阵见 [Mermaid](./docs/syntax-compat/mermaid.md)、[PlantUML](./docs/syntax-compat/plantuml.md) 和 [DOT](./docs/syntax-compat/dot.md)。

## 模块说明

- `:diagram-core`：共享 IR、几何、主题、诊断、绘制指令与导出相关基础类型。
- `:diagram-layout`：自研布局算法集合，包括 Sugiyama、树式、时间轴、图表和结构化布局。
- `:diagram-parser`：Mermaid、PlantUML、DOT 三套解析器及共享 IR lowering。
- `:diagram-render`：Compose 渲染门面、流式 session API、视口感知 Canvas 和对外主入口。
- `:composeApp`：跨平台 demo gallery，内置大量样例。
- `:androidApp`：本地验证用 Android 宿主应用。

## 引入方式

直接引入 `diagram-render` 即可使用完整的解析、布局和 Compose 渲染能力。

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

如果只需要部分能力，也可以按模块拆开依赖。

```kotlin
dependencies {
    implementation("io.github.huarangmeng:diagram-core:0.1.0")
    implementation("io.github.huarangmeng:diagram-layout:0.1.0")
    implementation("io.github.huarangmeng:diagram-parser:0.1.0")
}
```

## 使用方式

### Markdown 路由判断

Markdown 渲染器可以在创建 Compose UI 前，先询问某个代码块或流式文本前缀是否应该交给图表渲染。

```kotlin
import com.hrm.diagram.render.Diagram

val detection = Diagram.detectSource(fenceBody, hint = fenceInfo)
if (detection.shouldRouteToDiagram) {
    // 使用 DiagramView(source = fenceBody) 渲染
}
```

### 流式 Session

当前最核心的公开工作流是 streaming session。调用方可以不断追加源码分片，在流结束时执行 `finish()`。

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

### Compose 预览

`DiagramView(...)` 在应用边界接收源码字符串，并支持可选传入 `DiagramTheme`。它会把 Compose 文本测量接进布局链路，自动识别 Mermaid / PlantUML / DOT，并在内部维护增量 snapshot。

```kotlin
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.hrm.diagram.core.theme.DiagramTheme
import com.hrm.diagram.render.compose.DiagramView
import com.hrm.diagram.render.theme.material3

@Composable
fun MermaidPreview(source: String) {
    val theme = DiagramTheme.material3()
    DiagramView(
        source = source,
        theme = theme,
        modifier = Modifier.fillMaxSize(),
        zoomEnabled = true,
    )
}
```

## 导出状态

- **SVG**：共享 draw-command 导出链路已经进入架构与公开契约。
- **PNG/JPEG**：核心 API 契约已经存在，完整的多平台导出实现仍在按发布路线继续收口。

## 本地运行

### Demo 应用

- **Android**：`./gradlew :androidApp:assembleDebug`
- **Desktop**：`./gradlew :composeApp:run`
- **Web (Wasm)**：`./gradlew :composeApp:wasmJsBrowserDevelopmentRun`
- **Web (JS)**：`./gradlew :composeApp:jsBrowserDevelopmentRun`
- **iOS**：用 Xcode 打开 `iosApp/`

### 测试

```bash
./gradlew allTests
```

## 文档

- [架构说明](./docs/architecture.md)
- [公开 API 契约](./docs/api.md)
- [Streaming 规约](./docs/streaming.md)
- [阶段计划与进度](./docs/plan.md)
- [测试策略](./docs/testing.md)
- [贡献指南](./docs/contributing.md)
