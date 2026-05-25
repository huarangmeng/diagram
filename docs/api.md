# 公开 API 契约

> 本文档定义对外稳定 API。任何破坏性变更必须经 ADR（`docs/adr/`）评审。

## 1. 顶层入口（`:diagram-render`）

```kotlin
object Diagram {
    /** 自动识别语法（按首行 / @startxxx / digraph 等）。 */
    fun parse(source: String): ParseResult

    /** 显式指定语法。 */
    fun parse(source: String, language: SourceLanguage): ParseResult
}

enum class SourceLanguage { MERMAID, PLANTUML, DOT }

data class ParseResult(
    val model: DiagramModel?,
    val diagnostics: List<Diagnostic>,
) {
    val isSuccess: Boolean get() = model != null && diagnostics.none { it.severity == Severity.ERROR }
}
```

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
    snapshot: DiagramSnapshot,
    modifier: Modifier = Modifier,
    zoomEnabled: Boolean = false,
)

@Composable
fun DiagramView(
    session: DiagramSession,
    modifier: Modifier = Modifier,
    zoomEnabled: Boolean = false,
)

@Composable
fun rememberDiagramSession(
    language: SourceLanguage,
    key: Any? = language,
    theme: DiagramTheme = DiagramTheme.Default,
    layoutOptions: LayoutOptions = LayoutOptions(),
): DiagramSession
```

- `DiagramView` 是对外推荐的 Compose 门面命名。
- `DiagramView` 对外只暴露 `zoomEnabled` 这一项交互开关，缩放状态与 viewport 细节由库内部接管。
- `rememberDiagramSession` 负责把 Compose `TextMeasurer` 接入 session 生命周期。

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
