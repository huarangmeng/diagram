# 测试策略

## 1. 测试金字塔

| 层 | 工具 | 跑在 | 覆盖 |
|---|---|---|---|
| 单元（解析/IR/几何） | `kotlin-test` | `commonTest` | 函数级 |
| 解析黄金语料 | 自研 snapshot 比较 | `commonTest` | 每个语法每个图类型 |
| 布局确定性 | 坐标快照 + 容差 | `commonTest` | 每个布局算法 |
| DrawCommand 快照 | 序列化为字符串 | `commonTest` | 端到端管线 |
| SVG 端到端 | 字符串快照 | `commonTest` | 所有图类型 |
| PNG 端到端 | 像素比 + 容差 | `jvmTest` / `androidUnitTest` / iOS 测试 / web 测试 | 各平台导出 |
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
