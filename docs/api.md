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
    source: String,
    modifier: Modifier = Modifier,
    language: SourceLanguage? = null,         // null = 自动识别
    theme: DiagramTheme = DiagramTheme.Default,
    interaction: DiagramInteraction = DiagramInteraction(),
    onNodeClick: ((NodeId) -> Unit)? = null,
    onError: ((List<Diagnostic>) -> Unit)? = null,
)

@Composable
fun DiagramView(
    model: LaidOutDiagram,
    modifier: Modifier = Modifier,
    /* ... 同上 */
)

data class DiagramInteraction(
    val zoomEnabled: Boolean = true,
    val panEnabled: Boolean = true,
    val minScale: Float = 0.1f,
    val maxScale: Float = 10f,
)
```

## 4. 导出（`:diagram-core`）

```kotlin
// 共用：commonMain
fun LaidOutDiagram.toSvg(options: SvgOptions = SvgOptions()): String

// expect / actual
expect fun LaidOutDiagram.toPng(width: Int, scale: Float = 1f): ByteArray
expect fun LaidOutDiagram.toJpeg(width: Int, quality: Int = 90, scale: Float = 1f): ByteArray

data class SvgOptions(
    val embedFonts: Boolean = false,
    val pretty: Boolean = false,
)
```

## 5. 兼容性承诺
- 所有 `public` 类型加 `@DiagramApi` 注解（自定义），未加注解的视为内部。
- 1.x 阶段：minor 版本不破坏 `@DiagramApi` 签名；新增字段需有默认值。
- 实验性 API 用 `@DiagramExperimental` 标记，需 opt-in。
