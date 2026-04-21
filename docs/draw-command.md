# DrawCommand —— 渲染中间层

`DrawCommand` 是布局之后、渲染之前的指令流。Compose、SVG、PNG/JPEG 三个后端都消费同一份 `List<DrawCommand>`，从而保证三端视觉一致。

定义在 `:diagram-core` 的 `com.hrm.diagram.core.draw` 包。

## 1. 几何原子

```kotlin
data class Point(val x: Float, val y: Float)
data class Size(val width: Float, val height: Float)
data class Rect(val origin: Point, val size: Size)
data class PathCmd(val ops: List<PathOp>)             // moveTo/lineTo/quadTo/cubicTo/close
```

坐标系：原点左上、Y 轴向下、单位 = px @ 1x。`scale` 由各后端在落地时统一应用。

## 2. 指令集

```kotlin
sealed interface DrawCommand {
    val z: Int                                         // 同 Z 内按列表顺序绘制

    data class FillRect(val rect: Rect, val color: Color, val corner: Float = 0f, override val z: Int = 0) : DrawCommand
    data class StrokeRect(val rect: Rect, val stroke: Stroke, val color: Color, val corner: Float = 0f, override val z: Int = 0) : DrawCommand
    data class FillPath(val path: PathCmd, val color: Color, override val z: Int = 0) : DrawCommand
    data class StrokePath(val path: PathCmd, val stroke: Stroke, val color: Color, override val z: Int = 0) : DrawCommand
    data class DrawText(
        val text: String,
        val origin: Point,            // baseline-left
        val font: FontSpec,
        val color: Color,
        val maxWidth: Float? = null,
        override val z: Int = 0,
    ) : DrawCommand
    data class DrawArrow(val from: Point, val to: Point, val style: ArrowStyle, override val z: Int = 0) : DrawCommand
    data class DrawIcon(val name: String, val rect: Rect, override val z: Int = 0) : DrawCommand
    data class Group(val children: List<DrawCommand>, val transform: Transform = Transform.Identity, override val z: Int = 0) : DrawCommand
    data class Clip(val rect: Rect, val children: List<DrawCommand>, override val z: Int = 0) : DrawCommand
    data class Hyperlink(val href: String, val rect: Rect, override val z: Int = 0) : DrawCommand
}

data class Stroke(val width: Float, val dash: FloatArray? = null, val cap: Cap = Cap.Butt, val join: Join = Join.Miter)
data class FontSpec(val family: String, val sizeSp: Float, val weight: Int = 400, val italic: Boolean = false)
data class Color(val argb: Int)
data class Transform(val translate: Point = Point(0f,0f), val scale: Float = 1f, val rotateDeg: Float = 0f) {
    companion object { val Identity = Transform() }
}
data class ArrowStyle(val head: ArrowHead = ArrowHead.Triangle, val tail: ArrowHead = ArrowHead.None, val color: Color, val stroke: Stroke)
```

## 3. 后端约定
- **Compose**：每条指令映射到 `DrawScope` 的对应 API；`Hyperlink` 单独建个透明 `Modifier.clickable` 区域。
- **SVG**：每条指令映射到一个 `<rect>/<path>/<text>/<g>/<a>` 元素。
- **位图（PNG/JPEG）**：各平台 `PlatformCanvas` 适配器消费同一序列；字体度量必须用 Compose `TextMeasurer` 在布局阶段计算好的尺寸，禁止在导出阶段重新测量。

## 4. 不变式
- 指令流是布局结果的**纯函数**，相同 IR + Theme 必产出相同列表（用于快照测试）。
- 列表中所有 `Rect/Point` 坐标都在最终画布坐标系内，导出器只做缩放/换格式。
- 不允许引入"延迟测量"指令；所有文本必须已有显式 `FontSpec` 与可选 `maxWidth`。
