# 公开 API 契约

> 本文档定义对外稳定 API。任何破坏性变更必须经 ADR（`docs/adr/`）评审。

## 1. 顶层入口（`:diagram-render`）

```kotlin
object Diagram {
    /** 判断 Markdown fence / 文本块是否应交给 Diagram 渲染。 */
    fun detectSource(source: CharSequence, hint: String? = null): DiagramSourceDetection

    /** 打开底层 streaming session；Compose 应用层通常直接用 DiagramView(source = ...)。 */
    fun session(language: SourceLanguage): DiagramSession
}

enum class SourceLanguage { MERMAID, PLANTUML, DOT }

data class DiagramSourceDetection(
    val status: DiagramSourceStatus,
    val language: SourceLanguage?,
    val reason: String,
) {
    val isDiagram: Boolean
    val isPending: Boolean
    val shouldRouteToDiagram: Boolean
}

enum class DiagramSourceStatus { DIAGRAM, PENDING, NOT_DIAGRAM }
```

- `hint` 建议传 Markdown code fence 的 info string，例如 `mermaid`、`plantuml`、`puml`、`dot`、`graphviz`。
- `DIAGRAM` 表示可以立即交给 `DiagramView(source = ...)`。
- `PENDING` 表示流式前缀已经强烈指向某种图表，但可能还缺少完整 header；Markdown/LLM 容器可以先预留图表渲染槽。
- `NOT_DIAGRAM` 表示普通 Markdown / 代码块，不应交给本库。

## 2. 布局

```kotlin
fun DiagramModel.layout(options: LayoutOptions = LayoutOptions()): LaidOutDiagram

data class LayoutOptions(
    val direction: Direction = Direction.TopToBottom,
    val nodeSpacing: Float = 40f,
    val rankSpacing: Float = 60f,
    val theme: DiagramTheme = DiagramTheme.Default,
    val seed: Long = 0L,                  // 力导向等随机算法用
    val hints: Map<NodeId, LayoutHint> = emptyMap(),
)
```

## 3. Compose 渲染（`:diagram-render`）

```kotlin
@Composable
fun DiagramView(
    source: String,
    modifier: Modifier = Modifier,
    zoomEnabled: Boolean = false,
)
```

- `DiagramView` 是对外推荐的 Compose 门面命名。
- `DiagramView` 对外输入只接受 `source: String`；语法识别、`DiagramSession`、`DiagramSnapshot`、文本测量与增量 append 由库内部接管。
- `DiagramView` 对外只额外暴露 `zoomEnabled` 这一项交互开关，缩放状态与 viewport 细节由库内部接管；开启后支持移动端多指缩放 / 平移，以及 PC 鼠标滚轮或触控板缩放。
- `DiagramSession` / `DiagramSnapshot` 仍作为 streaming 与导出链路的底层契约存在，但不是 Compose 应用层入口。

## 4. 导出（`:diagram-core` + `:diagram-render`）

导出 API 分两层：

- `:diagram-core` 定义纯导出载体 `RenderedDiagram` 与 SVG / PNG / JPEG 编码契约；
- `:diagram-render` 负责把 `DiagramSnapshot` / `LaidOutDiagram` 转成 `RenderedDiagram`，并提供对外便捷的 `toSvg()` / `toPng()` / `toJpeg()`。

这样可以保持 `:diagram-core` 不依赖 `:diagram-layout`，同时让导出器只消费稳定的 `DrawCommand` 载体。

### 4.1 核心导出载体（`:diagram-core`）

```kotlin
data class RenderedDiagram(
    val bounds: Rect,
    val drawCommands: List<DrawCommand>,
    val background: Color? = null,
)

sealed interface ExportScale {
    data object Intrinsic : ExportScale
    data class Factor(val value: Float) : ExportScale
    data class Width(val px: Int) : ExportScale
    data class Height(val px: Int) : ExportScale
}

sealed interface ExportBackground {
    data object Auto : ExportBackground
    data object Transparent : ExportBackground
    data class Solid(val color: Color) : ExportBackground
}

data class ExportArtifact<T>(
    val value: T,
    val mimeType: String,
    val widthPx: Int,
    val heightPx: Int,
    val diagnostics: List<Diagnostic> = emptyList(),
)

data class SvgExportOptions(
    val scale: ExportScale = ExportScale.Intrinsic,
    val background: ExportBackground = ExportBackground.Auto,
    val pretty: Boolean = false,
    val embedFonts: Boolean = false,
    val includeXmlDeclaration: Boolean = true,
)

data class RasterExportOptions(
    val scale: ExportScale = ExportScale.Intrinsic,
    val background: ExportBackground = ExportBackground.Auto,
)

data class JpegExportOptions(
    val scale: ExportScale = ExportScale.Intrinsic,
    val background: ExportBackground = ExportBackground.Auto,
    val quality: Int = 90,
)

fun RenderedDiagram.exportSvg(
    options: SvgExportOptions = SvgExportOptions(),
): ExportArtifact<String>

expect suspend fun RenderedDiagram.exportPng(
    options: RasterExportOptions = RasterExportOptions(),
): ExportArtifact<ByteArray>

expect suspend fun RenderedDiagram.exportJpeg(
    options: JpegExportOptions = JpegExportOptions(),
): ExportArtifact<ByteArray>
```

### 4.2 导出桥接（`:diagram-render`）

```kotlin
fun DiagramSnapshot.prepareExport(
    background: ExportBackground = ExportBackground.Auto,
): RenderedDiagram

fun LaidOutDiagram.prepareExport(
    theme: DiagramTheme = DiagramTheme.Default,
    background: ExportBackground = ExportBackground.Auto,
): RenderedDiagram
```

### 4.3 对外便捷入口（`:diagram-render`）

```kotlin
fun DiagramSnapshot.toSvg(
    options: SvgExportOptions = SvgExportOptions(),
): String

suspend fun DiagramSnapshot.toPng(
    options: RasterExportOptions = RasterExportOptions(),
): ByteArray

suspend fun DiagramSnapshot.toJpeg(
    options: JpegExportOptions = JpegExportOptions(),
): ByteArray

fun LaidOutDiagram.toSvg(
    theme: DiagramTheme = DiagramTheme.Default,
    options: SvgExportOptions = SvgExportOptions(),
): String

suspend fun LaidOutDiagram.toPng(
    theme: DiagramTheme = DiagramTheme.Default,
    options: RasterExportOptions = RasterExportOptions(),
): ByteArray

suspend fun LaidOutDiagram.toJpeg(
    theme: DiagramTheme = DiagramTheme.Default,
    options: JpegExportOptions = JpegExportOptions(),
): ByteArray
```

### 4.4 语义约束

- `ExportScale.Width` / `Height` 只做**等比缩放**，不允许导出阶段拉伸或重新布局。
- `ExportBackground.Auto` 继承 `RenderedDiagram.background`；`Transparent` 仅对 SVG / PNG 保证透明语义。
- `JPEG` 遇到透明背景时必须降为不透明纯色背景，并附带 `EXPORT-W001`。
- PNG / JPEG 导出是 `suspend`：JVM/Android/iOS 可同步实现，JS / Wasm 允许走异步 `canvas/blob/arrayBuffer`。
- 导出阶段**禁止重新测量文本**；所有文本几何必须复用布局 / 渲染阶段写入的测量结果。

## 5. 兼容性承诺
- 所有 `public` 类型加 `@DiagramApi` 注解（自定义），未加注解的视为内部。
- 1.x 阶段：minor 版本不破坏 `@DiagramApi` 签名；新增字段需有默认值。
- 实验性 API 用 `@DiagramExperimental` 标记，需 opt-in。
