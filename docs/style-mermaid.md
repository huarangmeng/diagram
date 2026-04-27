# Mermaid 样式系统设计（对齐 Mermaid.js 语义）

> 状态：Draft
>
> 目标：对齐 Mermaid.js 的主题与样式语义，包括 `theme` / `themeVariables` /
> `classDef` / `class` / `style` / `linkStyle`，但内部实现完全使用 KMP 本地样式模型，
> 不引入 CSS 引擎、DOM 机制或 Mermaid.js 运行时代码。
>
> 参考语义来源：
> - https://mermaid.js.org/config/theming.html
> - https://mermaid.js.org/syntax/flowchart
> - https://mermaid.js.org/syntax/entityRelationshipDiagram.html
> - https://mermaid.js.org/syntax/classDiagram.html

## 0. 文档定位

本文件回答 3 个问题：

1. Mermaid.js 的样式语义，哪些要对齐？
2. 在 KMP 项目里，这些语义如何翻译成“本地可实现”的模型？
3. 解析、布局、渲染、导出分别在哪一层消费这些样式信息？

本文件是实现蓝图，不讨论具体代码细节。

## 1. 范围与非目标

### 1.1 要对齐的 Mermaid.js 语义

- 主题选择：`default` / `neutral` / `dark` / `forest` / `base`
- 图级主题变量：`themeVariables`
- 命名样式类：`classDef`
- 节点与边的样式绑定：
  - `class`
  - `:::`
  - `style`
  - `linkStyle`

### 1.2 不做的事情

- 不支持外部样式表引用。
  - Mermaid 官方文档里存在“通过外部样式表影响图形”的用法。
  - 本项目不实现样式表加载、选择器匹配、浏览器样式计算。
  - 这类输入统一忽略，并产出 `MERMAID-W010`。
- 不引入 Mermaid.js、dagre、ELK、任何 JS 互操作库。
- 不追求像素级复刻 Mermaid.js。
  - 我们只对齐“样式语义”和“默认视觉方向”。
  - 布局仍由本项目自研算法决定。

## 2. 硬约束

- 流式优先：样式系统必须支持 `session.append(...)` 的增量推进。
- 解析器不抛异常：任何非法样式输入只能转为 `Diagnostic`。
- 布局阶段测量、渲染阶段不复测：字体、字号、换行宽度等必须在布局前确定。
- 模块边界不能破：
  - `:diagram-parser` 只做语义提取，不依赖 layout/render
  - `:diagram-layout` 不直接理解 Mermaid 字符串语法
  - `:diagram-render` 是唯一把 theme、语法规则、IR 合并成最终视觉的地方

## 3. Mermaid.js 语义对齐

### 3.1 主题选择

- Mermaid 内置主题：
  - `default`
  - `neutral`
  - `dark`
  - `forest`
  - `base`
- 与 Mermaid.js 保持一致：
  - 只有 `base` 允许通过 `themeVariables` 自定义。
  - 其他主题可被选择，但不能被局部变量“继续改写成另一套完整主题”。

### 3.2 图级主题变量 `themeVariables`

Mermaid.js 使用一组主题变量表达“全图默认视觉”，例如：

- 字体：
  - `fontFamily`
  - `fontSize`
- 基础背景与文本：
  - `background`
  - `textColor`
- 主体颜色：
  - `primaryColor`
  - `primaryTextColor`
  - `primaryBorderColor`
- 连线颜色：
  - `lineColor`
- 图族专属变量：
  - flowchart / sequence / class / state 等分组变量

需要对齐的 Mermaid 行为：

- 颜色只接受 hex，不接受命名颜色。
  - 非 hex 颜色值忽略，并产出 `MERMAID-W011`
- 一部分变量是派生变量。
  - 例如边框色、文本色、默认连线色可能由基础色推导
  - 我们不复制 Mermaid.js 的实现代码，但必须提供确定性的本地派生规则

### 3.3 样式绑定语义

#### 节点

- `classDef foo ...`
  - 定义一个命名样式类 `foo`
- `class A foo`
  - 把样式类 `foo` 绑定到节点 `A`
- `A:::foo`
  - `class` 的简写形式
- `style A ...`
  - 对节点 `A` 做直接覆盖
- `classDef default ...`
  - 对所有未显式覆盖的节点提供默认样式

#### 边

- `linkStyle default ...`
  - 作用于所有边的默认样式
- `linkStyle 3 ...`
  - 作用于第 3 条边（按 Mermaid 语义，为定义顺序编号）
- `linkStyle 1,2,7 ...`
  - 作用于多个边索引

## 4. KMP Native Style Model

这一节是本设计的核心。

我们不把 Mermaid 的样式语义理解成 “CSS”，而是翻译成 KMP 可理解的本地样式模型。

### 4.1 核心对象

#### ThemeTokens

图级主题变量集合，对应 Mermaid `themeVariables`。

职责：
- 承载图级默认颜色、字体、字号、标签背景等 token
- 支持派生变量
- 支持归一化与校验

#### StyleDecl

一条样式声明，表示一个扁平的、结构化的样式值集合。

例如一个 `StyleDecl` 可包含：
- 填充色
- 边框色
- 边框宽度
- 虚线样式
- 文本色
- 字体族
- 字号
- 字重
- 斜体标记

这是对 Mermaid `fill:#f9f,stroke:#333,stroke-width:4px` 这类声明串的 KMP 化表达。

#### StyleClass

命名样式类，对应 Mermaid 的 `classDef`。

结构上等价于：
- `name`
- `decl: StyleDecl`

#### StyleRule

样式绑定规则，描述“把什么样式应用到什么目标上”。

建议至少区分以下类型：
- `NodeClassRule(nodeId, classNames)`
- `NodeInlineRule(nodeId, decl)`
- `EdgeDefaultRule(decl)`
- `EdgeIndexRule(indexes, decl)`

#### ResolvedStyle

样式决议后的最终结果，是 layout/render 直接消费的对象。

建议拆成：
- `ResolvedNodeStyle`
- `ResolvedEdgeStyle`
- `ResolvedLabelStyle`

这些类型应该是“完全去语法化”的：
- 不再出现 Mermaid 关键字
- 只保留平台无关的最终视觉值

### 4.2 Mermaid 语法到 KMP 模型的翻译

| Mermaid 语法 | KMP 本地模型 |
|---|---|
| `themeVariables` | `ThemeTokens` |
| `classDef foo ...` | `StyleClass(name = "foo", decl = ...)` |
| `class A foo` | `StyleRule.NodeClassRule(nodeId = A, classNames = ["foo"])` |
| `A:::foo` | 同上，只是不同语法入口 |
| `style A ...` | `StyleRule.NodeInlineRule(nodeId = A, decl = ...)` |
| `linkStyle default ...` | `StyleRule.EdgeDefaultRule(decl = ...)` |
| `linkStyle 3 ...` | `StyleRule.EdgeIndexRule(indexes = [3], decl = ...)` |

### 4.3 为什么必须这样建模

- 避免误导实现者去做 CSS parser / selector engine
- 让解析器输出结构化结果，而不是保留原始样式字符串到最后
- 让 layout 能直接拿到字体/字号/padding 等测量必需信息
- 让 render 层只做“消费 ResolvedStyle”，不再写硬编码
- 让 streaming 场景下样式更新是增量且确定性的

## 5. 样式声明 `StyleDecl` 设计

### 5.1 Phase 1 支持的 key

#### 节点相关

- `fill`
- `stroke`
- `stroke-width`
- `stroke-dasharray`
- `color`
- `font-family`
- `font-size`
- `font-weight`
- `font-style`

#### 边相关

- `stroke`
- `stroke-width`
- `stroke-dasharray`
- `color`（边标签文本色）

### 5.2 归一化规则

- key 大小写不敏感，统一转小写
- value 去首尾空格
- 末尾 `;` 视为可选终结符
- `stroke-dasharray`：
  - 支持空格分隔
  - 支持逗号分隔
  - Mermaid 转义逗号时先反转义再解析

### 5.3 非法值处理

- 非 hex 颜色：
  - 忽略该值
  - 诊断码：`MERMAID-W011`
- 不支持的 key：
  - 忽略该 key
  - 诊断码：`MERMAID-W012`
- 不支持的单位或非法数值：
  - 忽略该值
  - 诊断码：`MERMAID-W012`

## 6. ThemeTokens 设计

### 6.1 原始输入

`ThemeTokens` 来源于 Mermaid 图级配置中的 `themeVariables`。

建议保留两层表示：

- 原始层：
  - 解析后但未派生的原始 token
- 归一化层：
  - 完成 hex 校验、数值归一化、布尔归一化后的 token

### 6.2 派生规则

Mermaid.js 中有一批变量是从基础色派生出来的。

本项目不复制 Mermaid.js 实现，但需要一套本地确定性规则：

- 输入相同，输出必须稳定
- 优先保证可读性和对比度
- 只要用户显式指定了派生变量，就永远以显式值优先

最低要求：

- `primaryBorderColor` 可由 `primaryColor` 派生
- `primaryTextColor` 可根据 `primaryColor` 与 `darkMode` 派生
- `lineColor` 可由 `background` 派生

### 6.3 与 `DiagramTheme` 的关系

`ThemeTokens` 不是替代 `DiagramTheme`，而是 Mermaid 图级配置对 `DiagramTheme` 的局部覆盖源。

关系应为：

- `DiagramTheme` 提供全局基础默认
- `ThemeTokens` 提供 Mermaid 图级修正
- 两者经过 resolver 合并后，才得到最终默认样式

## 7. 样式决议（Resolve）

### 7.1 决议入口

定义一个纯函数：

`resolveStyles(globalTheme, diagramThemeTokens, styleClasses, styleRules, model) -> ResolvedStyleIndex`

输出的 `ResolvedStyleIndex` 用于按节点/边查询最终样式。

### 7.2 节点优先级

与 Mermaid.js 对齐：

1. 全局 `DiagramTheme`
2. Mermaid 图级 `ThemeTokens`
3. `classDef default`
4. 绑定到节点的样式类（按源码出现顺序）
5. `style <nodeId> ...`

后者覆盖前者。

### 7.3 边优先级

1. 全局 `DiagramTheme`
2. Mermaid 图级 `ThemeTokens`
3. `linkStyle default`
4. `linkStyle <index>`

### 7.4 多 class 决议

- 保留源码顺序
- 后绑定的 class 覆盖先绑定的 class
- 重复 class 视为幂等，不额外产生效果

## 8. 与布局的关系

布局阶段必须使用样式决议后的文本属性。

### 8.1 布局需要的样式信息

- 字体族
- 字号
- 字重
- 斜体
- 文本最大换行宽度
- 节点 padding

### 8.2 约束

- `:diagram-layout` 不能直接解析 Mermaid 样式语法
- 进入 layout 之前必须已经拿到结构化、已决议的文本样式
- render/export 不得再测量文本

## 9. 与渲染的关系

render 层不应直接理解 Mermaid `classDef/style/linkStyle` 字符串。

render 只消费 `ResolvedStyle`：

- 节点填充/描边/文字颜色
- 边的颜色/宽度/虚线
- 标签背景
- 箭头样式

目标：

- 所有 sub-pipeline 去掉硬编码颜色与字体
- 样式变化只通过 resolver 输出驱动

## 10. IR 承载策略（不改公开 IR 的前提下）

在不触发 ADR 的前提下，优先复用现有结构：

### 10.1 图级

放在 `DiagramModel.styleHints.extras`：

- `mermaid.theme`
- `mermaid.themeTokens`
- `mermaid.styleModel`

### 10.2 节点级

- `Node.payload` 保存类绑定信息、辅助标记
- `Node.style` 只保存已经能无歧义映射到通用 IR 的字段

### 10.3 边级

- `Edge.payload` 保存 link index 等 Mermaid 特有辅助信息
- `Edge.style` 保存已经能映射到通用 IR 的字段

如果后续发现需要一等公民 typed storage，再通过 ADR 扩展 IR。

## 11. 诊断策略

固定 warning 码：

- `MERMAID-W010`
  - 含义：外部样式表引用已忽略
  - 触发：出现依赖外部样式表才能生效的 Mermaid 样式用法
- `MERMAID-W011`
  - 含义：非 hex 颜色值已忽略
  - 触发：`themeVariables` 或样式声明中出现 `red` 之类非 hex 色值
- `MERMAID-W012`
  - 含义：不支持的样式 key 或非法样式值已忽略
  - 触发：未知 key、非法单位、无法解析的 dash/number/font 值

策略：

- 样式问题默认降级，不阻断整图渲染
- 诊断信息尽量定位到具体语句

## 12. 测试策略

### 12.1 黄金语料

每类图至少 5 个含样式的官方示例：

- `theme + themeVariables`
- `classDef + class`
- `style`
- `linkStyle default`
- `linkStyle index`

### 12.2 快照

推荐使用 SVG 快照观察：

- 颜色
- 线宽
- 虚线
- label 背景
- 字体效果

### 12.3 Streaming 切片

同一份 Mermaid 源文本：

- one-shot 解析的最终样式
- chunk append 的最终样式

两者必须一致。

## 13. 当前固定决策

- 外部样式表：不支持，忽略，`MERMAID-W010`
- `linkStyle` 索引：按边定义顺序递增
- 多 class：按源码顺序应用，后者覆盖前者
- 派生 token：使用本地确定性规则，不复制 Mermaid.js 代码
- 内部实现术语：统一使用 KMP 本地样式模型，不使用 CSS 引擎术语

## 14. Mermaid -> KMP Native Style Model 映射表

### 14.1 图级 token

| Mermaid 语义 | KMP 模型 |
|---|---|
| `theme` | 主题选择输入 |
| `themeVariables` | `ThemeTokens` |
| 派生主题变量 | `ThemeTokens` 归一化/派生层 |

### 14.2 节点样式

| Mermaid 语义 | KMP 模型 |
|---|---|
| `classDef foo ...` | `StyleClass(name = "foo", decl = ...)` |
| `class A foo` | `StyleRule.NodeClassRule(A, ["foo"])` |
| `A:::foo` | 同上（不同语法入口） |
| `style A ...` | `StyleRule.NodeInlineRule(A, decl)` |

### 14.3 边样式

| Mermaid 语义 | KMP 模型 |
|---|---|
| `linkStyle default ...` | `StyleRule.EdgeDefaultRule(decl)` |
| `linkStyle 3 ...` | `StyleRule.EdgeIndexRule([3], decl)` |
| `linkStyle 1,2,7 ...` | `StyleRule.EdgeIndexRule([1,2,7], decl)` |

### 14.4 最终消费

| KMP 模型 | 消费层 |
|---|---|
| `ThemeTokens` | resolver |
| `StyleDecl` / `StyleClass` / `StyleRule` | resolver |
| `ResolvedStyle` | layout + render |

## 15. 解析流程

本节定义 Mermaid 样式语义如何从源码进入 KMP 本地样式模型。

### 15.1 总体流程

样式解析不是一个独立系统，而是 Mermaid 解析链路的一部分：

1. 词法阶段识别样式相关语句
2. 语法阶段把语句分流到对应样式构造器
3. 语义阶段把结果写入 `ThemeTokens / StyleClass / StyleRule`
4. IR 快照阶段把这些结果挂入图级 / 节点级 / 边级承载结构
5. resolver 阶段把它们与 `DiagramTheme` 合并为 `ResolvedStyle`

### 15.2 输入来源

样式信息可能来自 4 类输入：

#### A. 图级配置输入

例如：

- `theme`
- `themeVariables`
- 后续可能扩展的 Mermaid 图级配置项

输出：

- `ThemeTokens`
- 图级 `styleHints.extras`

#### B. 命名样式类输入

例如：

- `classDef warn fill:#f96,stroke:#333`
- `classDef default fill:#fff`

输出：

- `StyleClass(name = "...", decl = ...)`
- 若类名为 `default`，额外记为默认类样式

#### C. 节点绑定规则输入

例如：

- `class A warn`
- `class A,B warn,focus`
- `A:::warn`

输出：

- `StyleRule.NodeClassRule`

#### D. 直接覆盖输入

例如：

- `style A fill:#f00`
- `linkStyle default stroke:#333`
- `linkStyle 1,2,7 stroke-width:4px`

输出：

- `StyleRule.NodeInlineRule`
- `StyleRule.EdgeDefaultRule`
- `StyleRule.EdgeIndexRule`

### 15.3 词法阶段

词法阶段只负责“切出样式语句的结构边界”，不做最终决议。

至少需要识别：

- `classDef`
- `class`
- `style`
- `linkStyle`
- `:::`
- 样式声明串中的 `:`、`,`、`;`

词法阶段目标：

- 保证流式输入下，样式声明串可以在 chunk 边界安全续扫
- 不要求在 lexer 阶段理解所有 key 的含义

### 15.4 语法阶段

语法阶段把 Mermaid 样式语句拆成结构化中间结果。

#### `themeVariables`

输出：

- 原始 `ThemeTokens` 键值表

语法阶段只做：

- 字段名提取
- 原始值提取

不做：

- 颜色派生
- 最终默认值合并

#### `classDef`

输出：

- 一个或多个 `StyleClass`

处理规则：

- 允许一条语句定义多个类名
- 样式声明串先解析为 `StyleDecl`
- `default` 作为保留类名单独记录

#### `class`

输出：

- `NodeClassRule`

处理规则：

- 一个语句可绑定多个节点
- 一个节点可绑定多个 class
- 顺序必须保留

#### `:::`

输出：

- 等价转换为 `NodeClassRule`

处理规则：

- 这是语法糖，不引入单独的内部语义

#### `style`

输出：

- `NodeInlineRule`

处理规则：

- 一条语句可覆盖一个或多个节点
- 样式串先解析为 `StyleDecl`

#### `linkStyle`

输出：

- `EdgeDefaultRule`
  或
- `EdgeIndexRule`

处理规则：

- `default` 走边默认规则
- 数字列表走边索引规则
- 索引解析失败则产诊断并忽略该语句

### 15.5 语义归一化阶段

这一阶段负责把“字符串形式的 Mermaid 值”变为 KMP 可直接使用的 typed 值。

#### ThemeTokens 归一化

- 颜色：
  - 校验是否为 hex
  - 非 hex -> `MERMAID-W011`
- 字体：
  - `fontFamily` 保留字符串
  - `fontSize` 归一化为数值
- 布尔：
  - `darkMode` 归一化为布尔值

#### StyleDecl 归一化

- key 统一转小写
- 值去空格
- `stroke-dasharray` 归一化为 `List<Float>`
- `stroke-width` / `font-size` 归一化为数值
- 不支持的 key -> `MERMAID-W012`

### 15.6 图快照落盘阶段

当 Mermaid parser 形成当前 IR 快照时，样式相关数据也必须进入快照。

建议规则：

- 图级：
  - `ThemeTokens`、`StyleClass`、`StyleRule` 的序列化摘要放入 `DiagramModel.styleHints.extras`
- 节点级：
  - class 绑定等局部信息放入 `Node.payload`
- 边级：
  - link index 等局部信息放入 `Edge.payload`

目的：

- 保证 render/layout 不回看 Mermaid 原文
- 保证 snapshot 是自描述的

### 15.7 解析阶段的错误恢复

样式解析必须是“尽量多保留有效信息”的。

例如：

- `style A fill:#f00,unknown-key:1,stroke:#333`

应处理为：

- `fill` 生效
- `unknown-key` 产 `MERMAID-W012`
- `stroke` 继续生效

而不是整条样式语句失效。

## 16. 增量更新流程

本节定义在 `session.append(...)` 下，Mermaid 样式系统如何增量更新。

### 16.1 增量目标

增量更新必须满足：

- 已稳定的节点/边不因无关样式输入而重复决议
- 已布局节点尽量不跳动
- 样式新增只影响必要的 dirty 范围
- `finish()` 能把 append 阶段的部分信息收敛成最终稳定结果

### 16.2 增量状态

在 Mermaid 会话内，至少维护以下状态：

#### A. 原始样式输入状态

- 未闭合或未完成的样式语句缓存
- 例如被 chunk 截断的 `classDef` / `style` / `themeVariables`

#### B. 结构化样式状态

- 当前已确认的 `ThemeTokens`
- 当前已确认的 `StyleClass`
- 当前已确认的 `StyleRule`

#### C. 决议缓存状态

- 节点样式决议缓存：`nodeId -> ResolvedNodeStyle`
- 边样式决议缓存：`edgeIndex -> ResolvedEdgeStyle`
- 图级默认样式缓存

#### D. dirty 集合

- `dirtyTheme`
- `dirtyNodeIds`
- `dirtyEdgeIndexes`
- `dirtyMeasurementNodeIds`

### 16.3 append(chunk) 的处理顺序

每次 `append(chunk)` 时：

1. lexer 续扫 chunk
2. parser 识别新增样式语句
3. 更新 `ThemeTokens / StyleClass / StyleRule`
4. 计算 dirty 范围
5. 仅对 dirty 对象重新做 resolve
6. 若样式变化影响文本测量，则把对应节点加入 `dirtyMeasurementNodeIds`
7. layout 对这些节点做增量重算
8. render 输出更新后的 `DrawCommand`

### 16.4 dirty 范围判定

#### 图级 token 更新

例如新增：

- `themeVariables.fontSize`
- `themeVariables.primaryColor`

影响：

- `dirtyTheme = true`
- 若影响字体/字号/换行，所有节点进入 `dirtyMeasurementNodeIds`
- 若只影响颜色，不需要重新测量，但需要重新渲染所有受影响节点/边

#### 新增或修改 `classDef`

影响：

- 所有引用该 class 的节点进入 `dirtyNodeIds`
- 若 class 含字体相关字段，这些节点同时进入 `dirtyMeasurementNodeIds`

#### 新增 `class` / `:::`

影响：

- 被绑定的节点进入 `dirtyNodeIds`
- 若绑定的 class 含字体相关字段，进入 `dirtyMeasurementNodeIds`

#### 新增 `style`

影响：

- 目标节点进入 `dirtyNodeIds`
- 若改动含字体、字号、padding 等，进入 `dirtyMeasurementNodeIds`

#### 新增 `linkStyle`

影响：

- 若是 `default`：所有边进入 `dirtyEdgeIndexes`
- 若是指定索引：仅对应边进入 `dirtyEdgeIndexes`

### 16.5 可增量更新与必须收敛的内容

#### append 阶段可直接生效

- 颜色变化
- 线宽变化
- 虚线变化
- 文本颜色变化
- 已闭合且合法的 class/style/linkStyle 规则

#### append 阶段可局部重测

- `font-family`
- `font-size`
- `font-weight`
- `font-style`
- 其他会改变文本包围盒的属性

#### finish() 阶段必须收敛

- 未闭合的样式语句必须定性为：
  - 成功补全
  - 或失败并产诊断
- `linkStyle` 作用到“最终边索引”的结果必须稳定
- 图级派生 token 必须完成最后一次统一计算

### 16.6 `linkStyle` 的增量约束

`linkStyle` 使用边定义顺序作为索引，因此增量场景下必须遵守：

- 边索引只增不减
- 已产生的边索引不重新编号
- 若 append 新增边，新的边只会拿到更大的索引

这样可以保证：

- `linkStyle 3` 在整个 session 生命周期内语义稳定

### 16.7 与布局 pinning 的关系

样式变化可能影响节点尺寸，从而影响布局。

规则：

- 若只改颜色/线宽/虚线：
  - 不触发节点重测量
  - 尽量不触发布局变化
- 若改字体/字号/padding：
  - 节点重新测量
  - 增量布局尽量保持旧节点位置 pinned
  - 只有真正受影响的节点与相邻路由进入局部更新

### 16.8 增量错误处理

在 append 阶段遇到非法样式输入时：

- 保留此前已确认的样式状态
- 当前错误语句不污染已稳定结果
- 产出对应 warning 或 error diagnostic
- 后续 chunk 到来后继续解析

### 16.9 增量一致性要求

同一份 Mermaid 文本：

- 一次性输入得到的最终 `ResolvedStyle`
- 多次 append 输入得到的最终 `ResolvedStyle`

必须一致。

这是样式增量系统的核心正确性要求。
