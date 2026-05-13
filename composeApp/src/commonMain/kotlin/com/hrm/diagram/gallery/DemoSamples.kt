package com.hrm.diagram.gallery

internal enum class SourceLang(val display: String) {
    MERMAID("Mermaid"),
    PLANTUML("PlantUML"),
    DOT("Graphviz DOT"),
}

internal data class DemoSample(
    val lang: SourceLang,
    val kind: String,
    val label: String,
    val source: String,
)

internal object DemoSamples {
    val all: List<DemoSample> = buildList {
        // ---- Mermaid ----
        add(DemoSample(SourceLang.MERMAID, "flowchart", "flowchart 流程图", """
            flowchart LR
              A[Start] --> B{Decide}
              B -->|yes| C[Do it]
              B -->|no| D[Stop]
        """.trimIndent()))
        add(DemoSample(SourceLang.MERMAID, "flowchart-bt", "flowchart 自下而上 (BT)", """
            flowchart BT
              A[Step 1] --> B[Step 2]
              B --> C[Step 3]
              C --> D[Done]
        """.trimIndent()))
        add(DemoSample(SourceLang.MERMAID, "sequenceDiagram", "sequenceDiagram 时序图", """
            sequenceDiagram
              Alice->>Bob: hello
              Bob-->>Alice: hi
        """.trimIndent()))
        add(DemoSample(SourceLang.MERMAID, "classDiagram", "classDiagram 类图", """
            classDiagram
              Animal <|-- Dog
              Animal : +String name
              Dog : +bark()
        """.trimIndent()))
        add(DemoSample(SourceLang.MERMAID, "classDiagram-notes", "classDiagram + 多向 note", """
            classDiagram
              class Order {
                +Long id
                +place()
              }
              class Customer {
                +String name
              }
              Customer "1" --> "*" Order : places
              note left of Customer : 客户在左侧
              note top of Order : 订单在上方
              note bottom of Order : 订单在下方
              note right of Order : 订单在右侧
              note "全局说明：演示 left/top/bottom/right 四向 note 定位"
        """.trimIndent()))
        add(DemoSample(SourceLang.MERMAID, "classDiagram-css", "classDiagram + cssClass 着色", """
            classDiagram
              class Service:::blue {
                +start()
                +stop()
              }
              class Repository:::green {
                +findAll()
              }
              class Cache:::orange
              class LegacyApi:::red {
                +call() $
              }
              Service --> Repository : reads
              Service ..> Cache : optional
              Service --> LegacyApi : delegates
              cssClass "Repository,Cache" purple
        """.trimIndent()))
        add(DemoSample(SourceLang.MERMAID, "stateDiagram", "stateDiagram 状态图", """
            stateDiagram-v2
              [*] --> Idle
              Idle --> Active : start
              state Active {
                [*] --> Loading
                Loading --> Ready : loaded
                Ready --> [*]
              }
              Active --> Choice
              state Choice <<choice>>
              Choice --> Done : ok
              Choice --> Failed : err
              Done --> [*]
              Failed --> [*]
              note right of Active : 内部子状态
        """.trimIndent()))
        add(DemoSample(SourceLang.MERMAID, "stateDiagram-fork", "stateDiagram + fork/join", """
            stateDiagram-v2
              [*] --> Start
              Start --> F
              state F <<fork>>
              F --> A
              F --> B
              A --> J
              B --> J
              state J <<join>>
              J --> Done
              Done --> [*]
        """.trimIndent()))
        add(DemoSample(SourceLang.MERMAID, "erDiagram", "erDiagram 实体关系图", """
            erDiagram
              CUSTOMER ||--o{ ORDER : places
              ORDER ||--|{ LINE-ITEM : contains
        """.trimIndent()))
        add(DemoSample(SourceLang.MERMAID, "journey", "journey 用户旅程图", """
            journey
              title My day
              section Morning
                Wake up: 3: Me
                Coffee : 5: Me
        """.trimIndent()))
        add(DemoSample(SourceLang.MERMAID, "gantt", "gantt 甘特图", """
            gantt
              title Sample
              section A
              Task 1 :a1, 2026-01-01, 7d
              Task 2 :after a1, 5d
        """.trimIndent()))
        add(DemoSample(SourceLang.MERMAID, "pie", "pie 饼图", """
            pie title Browsers
              "Chrome" : 60
              "Safari" : 25
              "Other" : 15
        """.trimIndent()))
        add(DemoSample(SourceLang.MERMAID, "gitGraph", "gitGraph Git 提交图", """
            gitGraph
              commit
              branch dev
              commit
              checkout main
              merge dev
        """.trimIndent()))
        add(DemoSample(SourceLang.MERMAID, "mindmap", "mindmap 思维导图", """
            mindmap
              root((root))
                A
                  A1
                B
        """.trimIndent()))
        add(DemoSample(SourceLang.MERMAID, "timeline", "timeline 时间线", """
            timeline
              title History
              2020 : Born
              2024 : Grew up
        """.trimIndent()))
        add(DemoSample(SourceLang.MERMAID, "requirementDiagram", "requirementDiagram 需求图", """
            requirementDiagram
              requirement R1 {
                id: 1
                text: "must work"
              }
        """.trimIndent()))
        add(DemoSample(SourceLang.MERMAID, "architectureDiagram", "architectureDiagram 架构图", """
            architecture-beta
              group api(cloud)[API]
              service db(database)[Database] in api
        """.trimIndent()))
        add(DemoSample(SourceLang.MERMAID, "c4", "c4 C4 架构图", """
            C4Context
              Person(u, "User")
              System(s, "App")
              Rel(u, s, "uses")
        """.trimIndent()))
        add(DemoSample(SourceLang.MERMAID, "sankey", "sankey 桑基图", """
            sankey-beta
              A,B,10
              A,C,5
        """.trimIndent()))
        add(DemoSample(SourceLang.MERMAID, "xyChart", "xyChart 坐标图", """
            xychart-beta
              title "Demo"
              x-axis [1, 2, 3, 4]
              y-axis "value" 0 --> 10
              line [1, 4, 6, 9]
        """.trimIndent()))
        add(DemoSample(SourceLang.MERMAID, "block", "block 方块图", """
            block-beta
              columns 2
              A B
              C D
        """.trimIndent()))
        add(DemoSample(SourceLang.MERMAID, "kanban", "kanban 看板图", """
            kanban
              Todo
                t1[Task 1]
              Doing
                t2[Task 2]
        """.trimIndent()))

        // ---- PlantUML ----
        add(DemoSample(SourceLang.PLANTUML, "sequence", "sequence 时序图", """
            @startuml
            Alice -> Bob: hello
            Bob --> Alice: hi
            @enduml
        """.trimIndent()))
        add(DemoSample(SourceLang.PLANTUML, "usecase", "usecase 用例图", """
            @startuml
            actor User
            User --> (Login)
            @enduml
        """.trimIndent()))
        add(DemoSample(SourceLang.PLANTUML, "class", "class 类图", """
            @startuml
            class Animal { +name: String }
            class Dog
            Animal <|-- Dog
            @enduml
        """.trimIndent()))
        add(DemoSample(SourceLang.PLANTUML, "activity", "activity 活动图", """
            @startuml
            start
            :Step 1;
            if (ok?) then (yes)
              :Done;
            else (no)
              :Retry;
            endif
            stop
            @enduml
        """.trimIndent()))
        add(DemoSample(SourceLang.PLANTUML, "component", "component 组件图", """
            @startuml
            [Web] --> [API]
            [API] --> [DB]
            @enduml
        """.trimIndent()))
        add(DemoSample(SourceLang.PLANTUML, "state", "state 状态机图", """
            @startuml
            [*] --> Idle
            Idle --> Working
            Working --> [*]
            @enduml
        """.trimIndent()))
        add(DemoSample(SourceLang.PLANTUML, "object", "object 对象图", """
            @startuml
            object o1
            object o2
            o1 --> o2
            @enduml
        """.trimIndent()))
        add(DemoSample(SourceLang.PLANTUML, "deployment", "deployment 部署图", """
            @startuml
            node Server {
              [App]
            }
            @enduml
        """.trimIndent()))
        add(DemoSample(SourceLang.PLANTUML, "timing", "timing 时序波形图", """
            @startuml
            robust "User" as U
            @0
            U is Idle
            @5
            U is Active
            @enduml
        """.trimIndent()))
        add(DemoSample(SourceLang.PLANTUML, "wireframe", "wireframe 线框图", """
            @startsalt
            {
              Name | "..."
              [OK] | [Cancel]
            }
            @endsalt
        """.trimIndent()))
        add(DemoSample(SourceLang.PLANTUML, "archimate", "archimate 企业架构", """
            @startuml
            archimate business-actor #LightYellow "Customer" as C
            archimate application-component #LightBlue "Portal" as P
            archimate technology-node "K8s" as K
            archimate motivation-goal "Reduce Cost" as G
            group "Application Layer" as AL {
              archimate implementation-event "Launch" as E
            }
            Rel_Serving(P, C, "serves")
            Rel_Assignment(K, P, "hosts")
            Rel_Realization(P, G, "realizes")
            Rel_Flow(C, E, "starts")
            @enduml
        """.trimIndent()))
        add(DemoSample(SourceLang.PLANTUML, "c4", "c4 C4 模型图", """
            @startuml
            !include <C4/C4_Container>
            C4Container
            UpdateLayoutConfig(${'$'}c4ShapeInRow="3", ${'$'}layout="TB")
            AddElementTag("critical", ${'$'}bgColor="#f96", ${'$'}shape="EightSidedShape()", ${'$'}legendText="Critical")
            AddRelTag("async", ${'$'}textColor="blue", ${'$'}lineColor="#8E24AA", ${'$'}lineStyle="DashedLine()", ${'$'}legendText="Async")
            System_Boundary(sys, "Ordering", ${'$'}link="https://example.com/system") {
              Person_Ext(u, "Customer", "Buyer")
              Container(api, "API", "Ktor", "Backend", ${'$'}tags="critical", ${'$'}link="https://example.com/api")
              ContainerQueue(q, "Events", "Kafka", "Async events")
            }
            BiRel_R(u, api, "uses", "HTTPS")
            Rel(api, q, "publishes", "JSON", ${'$'}tags="async", ${'$'}link="https://example.com/events")
            Lay_D(api, q)
            SHOW_LEGEND()
            @enduml
        """.trimIndent()))
        add(DemoSample(SourceLang.PLANTUML, "erd", "erd 数据库 ER 图", """
            @startuml
            entity Customer { *id }
            entity Order { *id }
            Customer ||--o{ Order
            @enduml
        """.trimIndent()))
        add(DemoSample(SourceLang.PLANTUML, "gantt", "gantt 甘特图", """
            @startgantt
            [Task A] lasts 7 days
            [Task B] lasts 5 days and starts at [Task A]'s end
            @endgantt
        """.trimIndent()))
        add(DemoSample(SourceLang.PLANTUML, "mindmap", "mindmap 思维导图", """
            @startmindmap
            * root
            ** A
            *** A1
            ** B
            @endmindmap
        """.trimIndent()))
        add(DemoSample(SourceLang.PLANTUML, "wbs", "wbs 工作分解结构图", """
            @startwbs
            * Project
            ** Phase 1
            ** Phase 2
            @endwbs
        """.trimIndent()))
        add(DemoSample(SourceLang.PLANTUML, "ditaa", "ditaa ASCII 图", """
            @startditaa
            skinparam handwritten true
            +---------+   +---------+   +---------+
            | {d} Doc |<=>| {s} DB  |:::| {io} IO |
            | cGRE    |   | cBLU    |   | cF80    |
            +---------+   +---------+   +---------+
                  ^             |
                  |             v
            /---------\   +---------+
            | {c} Dec |===| {o} End |
            \---------/   +---------+
            @endditaa
        """.trimIndent()))
        add(DemoSample(SourceLang.PLANTUML, "network", "network 网络拓扑图", """
            @startuml
            nwdiag {
              inet internet {
                router [shape = cloud, label = "Internet", color = "#E0F7FA"];
              }
              network dmz {
                address = "210.x.x.x/24"
                web [shape = component, address = "210.x.x.10", description = "frontend"];
              }
              network internal {
                address = "172.16.x.x/24"
                app [shape = queue, description = "events"];
                db [shape = database, address = "172.16.x.20"];
                web;
                web -> app : publish;
                app -> db : persist;
              }
            }
            @enduml
        """.trimIndent()))
        add(DemoSample(SourceLang.PLANTUML, "json", "json JSON 结构图", """
            @startjson
            { "name": "Alice", "age": 30 }
            @endjson
        """.trimIndent()))
        add(DemoSample(SourceLang.PLANTUML, "yaml", "yaml YAML 结构图", """
            @startyaml
            name: Alice
            age: 30
            @endyaml
        """.trimIndent()))

        // ---- DOT ----
        add(DemoSample(SourceLang.DOT, "digraph", "digraph 有向图", """
            digraph G {
              A -> B;
              B -> C;
              A -> C;
            }
        """.trimIndent()))
        add(DemoSample(SourceLang.DOT, "graph", "graph 无向图", """
            graph G {
              A -- B;
              B -- C;
            }
        """.trimIndent()))
        add(DemoSample(SourceLang.DOT, "tree", "tree 树形结构", """
            digraph T {
              node [shape=box];
              root -> a;
              root -> b;
              a -> a1;
            }
        """.trimIndent()))
        add(DemoSample(SourceLang.DOT, "cluster", "cluster 子图分组", """
            digraph G {
              subgraph cluster_0 { a; b; label="Group" }
              a -> c;
            }
        """.trimIndent()))
        add(DemoSample(SourceLang.DOT, "dependency", "dependency 依赖关系", """
            digraph deps {
              "core" -> "layout";
              "core" -> "parser";
              "layout" -> "render";
              "parser" -> "render";
            }
        """.trimIndent()))
    }
}
