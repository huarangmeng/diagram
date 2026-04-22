# 0003: 导出后端合并入 :diagram-core

- 状态：Accepted（修订）
- 日期：2026-04（2026-XX 修订：再合并）
- 关联：用户反馈"模块过多" + `plan.md` §2、§7

## 背景
最初规划 `:diagram-export-svg` + `:diagram-export-image` 两个模块；
第一次修订合并为 `:diagram-export` 单模块。
本次修订进一步把 `:diagram-export` 合并入 `:diagram-core`，将整体模块数从 8 → 4。

## 选项
- A：拆三个模块（svg / image / core）。
- B：拆 `:diagram-export` 与 `:diagram-core`。
- C：导出能力下沉到 `:diagram-core` 内部 `export.svg` / `export.image` 子包。

## 决定
选 C。

## 影响
- 模块数 8 → 4：`:diagram-core` / `:diagram-layout` / `:diagram-parser` / `:diagram-render`。
- `:diagram-core` 体积略增，但导出与 `DrawCommand` 同模块、无跨模块 smart-cast 失效问题（见 AGENTS §1.5）。
- 用户只需依赖 `:diagram-core` 即可获得 headless SVG/PNG 导出，无需额外工件。
- 不利点：`:diagram-core` 不再是"零依赖纯 IR"，但仍保持零运行时第三方依赖（仅 stdlib + kotlinx）。
