# 测试策略

## 1. 测试金字塔

| 层 | 工具 | 跑在 | 覆盖 |
|---|---|---|---|
| 单元（解析/IR/几何） | `kotlin-test` | `commonTest` | 函数级 |
| 解析黄金语料 | 自研 snapshot 比较 | `commonTest` | 每个语法每个图类型 |
| 布局确定性 | 坐标快照 + 容差 | `commonTest` | 每个布局算法 |
| DrawCommand 快照 | 序列化为字符串 | `commonTest` | 端到端管线 |
| SVG 端到端 | 字符串快照 | `commonTest` | 所有图类型 |
| PNG 端到端 | 像素比 + 容差 | `jvmTest` / `androidUnitTest` / iOS 测试 / `macosArm64Test` / web 测试 | 各平台导出 |
| Compose 渲染 | Roborazzi（JVM）+ 手动跑 demo gallery | `jvmTest` | 视觉回归 |

## 2. 黄金语料约定

```
composeApp/src/commonTest/resources/
  mermaid/
    flowchart/
      basic.mmd
      basic.expected.svg
      basic.expected.ir.txt
    sequence/
    ...
  plantuml/
  dot/
```

- 每个 `.<ext>` 必须配 `.expected.svg`（核心）+ 可选 `.expected.ir.txt`。
- 修复 bug → 先加一个能复现的样例再改代码。

## 3. Snapshot 工具
- 自研最小 helper：`assertSnapshot(actual, file)`，CI 失败时打印 diff；本地用 `-PupdateSnapshots=true` 重新落盘。
- 不引入第三方 snapshot 库以保持 commonTest 跨平台。

## 4. 跨平台跑测命令
```bash
./gradlew allTests                     # 全部目标
./gradlew :diagram-core:jvmTest        # 单模块单平台
./gradlew :diagram-parser:jsTest
./gradlew :diagram-parser:wasmJsTest
```

## 5. 性能基准（可选，Phase 7）
- JMH（JVM）+ kotlinx-benchmark 多平台。
- 基线在 `docs/architecture.md` §6。

## 6. macOS Native（仅 arm64）

Apple Silicon 上执行，不以 Windows 的 klib 编译或非空图片代替：

```bash
./gradlew :diagram-core:macosArm64Test :diagram-layout:macosArm64Test :diagram-parser:macosArm64Test :diagram-render:macosArm64Test --max-workers=2
```

`RasterExportMacosTest` 独立解码最终文件，检查左上坐标、非零 bounds、等比缩放、透明及半透明 PNG、z 顺序、嵌套 transform/clip、圆角、路径曲线、dash/cap/join、全部箭头端点、文本基线/多行/字体样式、无 measuredBounds 的历史文本入口，以及 JPEG 不透明背景和品质对应的实际像素误差。
`MacosPipelineRasterTest` 经三种语言真实 streaming parser → layout → snapshot → PNG 链路检查文字区域的可见像素；默认 pipeline 的四汉字标题及两行中文还须匹配移除估算矩形的全部像素，并验证独立 CoreText 完整字符串范围、行数及每个字形。字体降级回归必须观察真实单 face 字体的 traits API 返回 null，不能 mock 或跳过。CI 额外核对四模块目标 POM、root `available-at` 和 klib 载荷，不操作 Maven 发布或签名。

Windows 可用 `-Pkotlin.native.enableKlibsCrossCompilation=true` 编译四模块 `compileTestKotlinMacosArm64`，只能证明主代码/测试代码及平台 API 类型可编译，不能证明 macOS 链接、字体、坐标翻转或编码结果。当前真实 Native 测试尚未运行。
