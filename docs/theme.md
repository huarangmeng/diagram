# 主题 / 颜色 / 字体 / 形状 / 箭头

## 1. DiagramTheme

```kotlin
data class DiagramTheme(
    val palette: Palette,
    val typography: Typography,
    val nodeDefaults: NodeStyle,
    val edgeDefaults: EdgeStyle,
    val clusterDefaults: ClusterStyle,
    val arrowDefaults: ArrowStyle,
    val background: Color,
) {
    companion object {
        val Default: DiagramTheme       // Mermaid default 风格
        val Dark: DiagramTheme
        val MaterialYou: DiagramTheme   // 取自 ColorScheme
    }
}
```

## 2. Palette
- `primary / secondary / accent / surface / onSurface / outline / muted / danger / success / warning`
- 解析器解析到的语法颜色（如 mermaid `style A fill:#f9f`）覆盖 palette。

## 3. Typography
- `bodyFont`、`titleFont`、`monoFont`，每个含 `family + sizeSp + weight`。
- 字体回退：用户传入未加载字体时降级到 Compose 平台默认；记录 `RenderWarnings`。

## 4. NodeShape 列举

`Box, RoundedBox, Stadium, Circle, Ellipse, Diamond, Hexagon, Parallelogram, Trapezoid, Cylinder, Subroutine, Note, Cloud, Actor, UseCase, Component, Package, State, ChoicePoint, ForkBar, StartCircle, EndCircle, JsonNode, Custom(name)`

新增形状步骤：在 `core/draw/Shapes.kt` 注册 `Shape -> PathCmd` 函数；同时更新本节。

## 5. ArrowHead 列举

`None, Triangle, OpenTriangle, Diamond, OpenDiamond, Circle, OpenCircle, Bar, Cross`

## 6. 与三家语法映射
- Mermaid `classDef` / `style` / `linkStyle` → 转 `NodeStyle` / `EdgeStyle` 覆盖。
- PlantUML `skinparam` → 在 lowering 时合并入 `StyleHints`，渲染时叠加到 theme。
- DOT `node[shape=...] edge[arrowhead=...]` 等属性 → 直接映射。

详细映射表在每个语法的 `docs/syntax-compat/<lang>.md` 内维护。

### Mermaid External CSS (Ignored)
- Mermaid 语法允许引用外部 CSS class（例如 `.cssClass > rect { ... }`）作为样式来源。
- 本项目不支持加载/解析外部 CSS：遇到仅在外部 CSS 中定义的 class，将忽略并产出 `MERMAID-W010` 警告。
- Mermaid 样式体系的对齐方案详见 `docs/style-mermaid.md`。
