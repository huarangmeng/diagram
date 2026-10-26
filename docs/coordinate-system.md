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

## 5. macOS Native 栅格化
- CoreGraphics 位图 context 的默认 Y 轴与契约相反：根变换只翻转一次，再应用导出缩放和负 bounds 平移。
- Group 按 translate → clockwise rotate → scale 应用；Clip 跟随当前变换，所有子状态成对 save/restore。
- CoreText 绘制时局部翻回字形坐标，不翻转图表内容；baseline 使用显式 origin，排字宽度只受原 maxWidth 约束，估算测量矩形不得裁掉字形。
- 位图以 sRGB 和显式像素尺寸创建，不读取 NSScreen backingScaleFactor；PNG 保留 alpha，JPEG 背景由共享计划转为不透明。
