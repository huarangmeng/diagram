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
              ORDER ||--|{ LINE_ITEM : contains
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
        add(DemoSample(SourceLang.MERMAID, "sequenceDiagram-advanced", "sequenceDiagram 分支/并行/激活", """
            sequenceDiagram
              autonumber
              actor User
              participant Web
              participant API
              participant DB
              User->>Web: Submit order
              activate Web
              Web->>API: POST /orders
              activate API
              par validate payment
                API->>API: check risk
              and reserve stock
                API->>DB: reserve items
                DB-->>API: reserved
              end
              alt accepted
                API-->>Web: 201 Created
                Web-->>User: receipt
              else rejected
                API-->>Web: 409 Conflict
                Web-->>User: show retry
              end
              deactivate API
              deactivate Web
              note over User,DB: loop/alt/par/activation/autonumber preview
        """.trimIndent()))
        add(DemoSample(SourceLang.MERMAID, "flowchart-shapes", "flowchart 多形状/边标签", """
            flowchart TB
              A((Mobile)) --> B{Cache hit?}
              B -->|no| C[API Gateway]
              C --> D[(Queue)]
              C --> E[(Database)]
              B -->|yes| F(Render local)
              D --> G[Worker]
              G --> E
        """.trimIndent()))
        add(DemoSample(SourceLang.MERMAID, "gantt-rich", "gantt 多级时间轴/进度/里程碑", """
            gantt
              title Release Plan
              dateFormat  YYYY-MM-DD
              axisFormat  %b %d
              tickInterval 1week
              excludes weekends
              section Design
              UX research       :done, ux, 2026-01-05, 5d
              Prototype         :active, proto, after ux, 6d
              section Build
              API implementation :crit, api, 2026-01-12, 10d
              Web integration    :web, after proto, 8d
              Release candidate  :milestone, rc, after api, 0d
              vert "Code freeze" : 2026-01-23
              click api href "https://example.com/api"
        """.trimIndent()))
        add(DemoSample(SourceLang.MERMAID, "timeline-rich", "timeline 分段/主题", """
            ---
            config:
              timeline:
                disableMulticolor: true
              themeVariables:
                cScale0: "#E0F2FE"
                cScale1: "#DCFCE7"
            ---
            timeline LR
              title Platform Milestones
              section Parser
                2026 Q1 : Mermaid matrix
                        : PlantUML matrix
              section Renderer
                2026 Q2 : Streaming canvas
                        : Export pipeline
              section Quality
                2026 Q3 : Golden corpus
                        : Perf trace
        """.trimIndent()))
        add(DemoSample(SourceLang.MERMAID, "requirementDiagram-rich", "requirementDiagram 关系/样式", """
            requirementDiagram
              requirement req_parser {
                id: REQ-1
                text: "parse official samples"
                risk: medium
                verifymethod: test
              }
              functionalRequirement req_stream {
                id: REQ-2
                text: "append-only streaming updates"
                risk: high
                verifymethod: inspection
              }
              element renderer {
                type: component
                docRef: render module
              }
              req_parser - derives -> req_stream
              renderer - satisfies -> req_stream
              classDef important fill:#fff3e0,stroke:#e65100,stroke-width:3px
              class req_stream important
        """.trimIndent()))
        add(DemoSample(SourceLang.MERMAID, "architectureDiagram-rich", "architectureDiagram 嵌套/端口/样式", """
            architecture-beta
              group cloud(cloud)[Cloud]
              group edge(server)[Edge] in cloud
              service gateway(internet)[Gateway] in edge
              service api(server)[API] in cloud
              service queue(queue)[Events] in cloud
              service db(database)[Database] in cloud
              junction fanout in cloud
              gateway:R --> L:api
              api:R --> L:fanout
              fanout:R --> L:queue
              api:B --> T:db
              classDef hot fill:#fff7ed,stroke:#ea580c,stroke-width:3px
              class api hot
              style db fill:#eff6ff,stroke:#2563eb,color:#1e3a8a
        """.trimIndent()))
        add(DemoSample(SourceLang.MERMAID, "xyChart-rich", "xyChart area/scatter/frontmatter", """
            ---
            config:
              xyChart:
                showDataLabel: true
              themeVariables:
                xyChartPlotColor: "#ECFDF5"
                xyChartTitleColor: "#065F46"
            ---
            xychart
              title "Adoption"
              x-axis [Jan, Feb, Mar, Apr, May]
              y-axis "Users" 0 --> 100
              bar [12, 25, 45, 65, 80]
              line [8, 22, 41, 70, 90]
              area [5, 18, 35, 55, 72]
        """.trimIndent()))
        add(DemoSample(SourceLang.MERMAID, "quadrantChart", "quadrantChart 四象限", """
            quadrantChart
              title Reach and engagement
              x-axis Low Reach --> High Reach
              y-axis Low Engagement --> High Engagement
              quadrant-1 Scale
              quadrant-2 Nurture
              quadrant-3 Drop
              quadrant-4 Rework
              Campaign A: [0.8, 0.75] radius: 10, color: #16a34a
              Campaign B: [0.35, 0.65] radius: 8, color: #f97316
              Campaign C: [0.2, 0.25] radius: 6, color: #64748b
        """.trimIndent()))
        add(DemoSample(SourceLang.MERMAID, "gauge", "gauge 仪表盘", """
            gauge
              title CPU Usage
              min 0
              max 200
              value 135
        """.trimIndent()))
        add(DemoSample(SourceLang.MERMAID, "packet", "packet-beta 协议字段", """
            packet-beta
            title TCP Header
            0-15: "Source Port"
            16-31: "Destination Port"
            32-63: "Sequence Number"
            64-95: "Acknowledgment Number"
            96-99: "Data Offset"
            100-105: "Reserved"
            106: "URG"
            107: "ACK"
            108: "PSH"
            109: "RST"
            110: "SYN"
            111: "FIN"
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
            class Animal {
              +name: String
            }
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
        add(DemoSample(SourceLang.PLANTUML, "sequence-advanced", "sequence 分组/box/skinparam", """
            @startuml
            skinparam sequence {
              ArrowColor #2563EB
              ParticipantBackgroundColor #EFF6FF
              ParticipantBorderColor #1D4ED8
              LifeLineBorderColor #94A3B8
            }
            box "Checkout" #FFF7ED
              actor Customer
              boundary Web
              control API
            end box
            database DB
            Customer -> Web: place order
            activate Web
            Web -> API: POST /orders
            activate API
            alt in stock
              API -> DB: reserve()
              DB --> API: ok
              API --> Web: 201 Created
            else out of stock
              API --> Web: 409 Conflict
            end
            deactivate API
            Web --> Customer: confirmation
            deactivate Web
            @enduml
        """.trimIndent()))
        add(DemoSample(SourceLang.PLANTUML, "activity-advanced", "activity swimlane/fork/repeat", """
            @startuml
            skinparam ActivityBackgroundColor #ECFDF5
            start
            |User|
            :Submit request;
            |Service|
            if (valid?) then (yes)
              fork
                :Reserve inventory;
              fork again
                :Charge payment;
              end fork
              repeat
                :Notify downstream;
              repeat while (retry?) is (yes)
              :Complete order;
            else (no)
              :Return validation error;
            endif
            stop
            @enduml
        """.trimIndent()))
        add(DemoSample(SourceLang.PLANTUML, "component-ports", "component port/package/skinparam", """
            @startuml
            skinparam component {
              BackgroundColor #EFF6FF
              BorderColor #2563EB
              FontColor #1E3A8A
            }
            package Backend {
              component "Order API" as Api {
                portin HttpIn
                portout EventsOut
              }
              queue Jobs
              database Orders
            }
            interface "HTTP" as Http
            Http --> HttpIn : REST
            EventsOut --> Jobs : publish
            Api --> Orders : persist
            note right of Api
              port + package + queue/database preview
            end note
            @enduml
        """.trimIndent()))
        add(DemoSample(SourceLang.PLANTUML, "deployment-rich", "deployment 多形状/嵌套/样式", """
            @startuml
            skinparam node {
              BackgroundColor #F8FAFC
              BorderColor #475569
            }
            node Server {
              artifact "web.jar" as Web
              queue Jobs
              database OrdersDb
            }
            cloud Internet
            actor Customer
            Customer --> Internet
            Internet --> Web : HTTPS
            Web --> Jobs : async
            Web --> OrdersDb : JDBC
            note bottom of OrdersDb : queue/artifact/cloud preview
            @enduml
        """.trimIndent()))
        add(DemoSample(SourceLang.PLANTUML, "timing-advanced", "timing clock/binary/robust/constraint", """
            @startuml
            scale 50 as 25 pixels
            clock "Clock" as CLK with period 100 duty 30%
            binary "Enable" as EN
            robust "Response" as RES
            concise "Mode" as M
            @0 : boot
            EN is low
            RES is idle
            M is Idle
            @50
            EN is high
            M is Run : Executing <<thick>>
            @100
            RES is busy
            EN -> RES : request
            @150 <-> @250 : latency budget
            @250
            RES is done
            @enduml
        """.trimIndent()))
        add(DemoSample(SourceLang.PLANTUML, "wireframe-rich", "wireframe Salt 控件集合", """
            @startsalt
            {
              {^ "File" | "Edit" | "View" }
              {# Login form
                User | "alice@example.com"
                Pass | "••••"
                [X] Remember me | ( ) Guest | (X) Admin
                -- | --
                [Login] | [Cancel]
              }
              {T
                + Project
                ++ Parser
                ++ Renderer
              }
              {S
                Row 1
                Row 2
                Row 3
              }
            }
            @endsalt
        """.trimIndent()))
        add(DemoSample(SourceLang.PLANTUML, "chart-pie", "chart pie 样式/图例", """
            @startpie
            title Runtime Share
            legend right
            #4F46E5; "Parser" : 35
            #16A34A; "Layout" : 25
            #F97316; "Render" : 40
            @endpie
        """.trimIndent()))
        add(DemoSample(SourceLang.PLANTUML, "chart-xy", "chart bar/line/scatter", """
            @startchart
            title Build Metrics
            legend bottom
            x-axis [Mon, Tue, Wed, Thu, Fri]
            y-axis 0 --> 100
            bar "Tests" [72, 80, 86, 91, 95] #2563EB
            line "Coverage" [60, 64, 70, 74, 78] #16A34A
            scatter "Failures" [(1, 8), (2, 5), (3, 3), (4, 2), (5, 1)] #DC2626
            @endchart
        """.trimIndent()))
        add(DemoSample(SourceLang.PLANTUML, "gantt-rich", "gantt 日历/进度/里程碑", """
            @startgantt
            Project starts 2026-01-05
            saturday are closed
            sunday are closed
            2026-01-16 is closed
            -- Design --
            [Research] lasts 5 days and is colored in #93C5FD
            [Prototype] starts at [Research]'s end and lasts 6 days
            -- Build --
            [API] starts 2026-01-12 and lasts 10 days and is 60% complete
            [Web] starts at [Prototype]'s end and lasts 8 days and is dashed
            [Release] happens at [API]'s end and is milestone
            [API] -> [Release]
            note bottom of [API] : critical backend path
            @endgantt
        """.trimIndent()))
        add(DemoSample(SourceLang.PLANTUML, "mindmap-styled", "mindmap style/icon/boxless", """
            @startmindmap
            <style>
            mindmapDiagram {
              .focus {
                BackgroundColor #DCFCE7
                LineColor #16A34A
                FontColor #14532D
                RoundCorner 18
              }
              .risk * {
                BackgroundColor #FFEDD5
                LineColor #EA580C
              }
            }
            </style>
            * <&flag> Roadmap <<focus>>
            ** Parser
            *** Official samples
            **_ Render constraints
            ** Risk <<risk>>
            *** Perf trace
            *** Streaming pinning
            @endmindmap
        """.trimIndent()))
        add(DemoSample(SourceLang.PLANTUML, "wbs-styled", "wbs style/方向/多行", """
            @startwbs
            <style>
            wbsDiagram {
              .done {
                BackgroundColor #DBEAFE
                LineColor #2563EB
                FontColor #1E3A8A
              }
              .todo * {
                BackgroundColor #FEF3C7
                LineColor #D97706
                RoundCorner 16
              }
            }
            </style>
            * Project <<done>>
            **> Phase 6 DOT
            *** Parser
            *** Layout
            **< Phase 7 Export <<todo>>
            *** SVG
            *** PNG/JPEG
            **_:Multi-line
            preview ;
            @endwbs
        """.trimIndent()))
        add(DemoSample(SourceLang.PLANTUML, "json-rich", "json 嵌套结构/类型", """
            @startjson
            {
              "project": "diagram",
              "targets": ["jvm", "js", "ios"],
              "features": {
                "streaming": true,
                "coverage": 0.92,
                "warnings": null
              }
            }
            @endjson
        """.trimIndent()))
        add(DemoSample(SourceLang.PLANTUML, "yaml-rich", "yaml 列表/块标量", """
            @startyaml
            project: diagram
            targets:
              - jvm
              - js
              - ios
            release:
              version: 1.0
              notes: |
                Streaming previews
                Golden corpus ready
            flags: { streaming: true, export: false }
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
