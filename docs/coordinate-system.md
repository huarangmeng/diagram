# 坐标系与单位

## 1. 坐标
- 原点左上，X 向右，Y 向下。
- 单位：`Float` 像素 @ 1x。所有 `LaidOutDiagram` 内坐标都已是最终像素，不带 dp/sp 概念。
- 角度：度（°），顺时针为正。

## 2. 字号
- `FontSpec.sizeSp` 单位是 sp（按 Compose 概念），在生成 `DrawCommand` 时由布局阶段统一转换为 px 写入 `FontSpec.sizeSp`。
- 文本 `origin = baseline-left`，不是 top-left。

## 3. DPI 与 scale
- 渲染端：Compose 自动按 `Density` 处理。
- 导出端：通过 `toPng(width, scale)` 控制；`scale=2f` 等同 2x 视网膜。
- SVG：viewBox = `LaidOutDiagram.bounds`，宽高用 `100%` 或导出选项指定。

## 4. 文本测量
- 所有文本必须在 **布局阶段** 通过共享的 `TextMeasurer`（commonMain 抽象）测量，结果写入 IR 的几何字段。
- 渲染/导出阶段**禁止**重新测量；如发现需要，说明布局阶段漏测，应回头修。
