package com.hrm.diagram.core.ir

data class Node(
    val id: NodeId,
    val label: RichLabel = RichLabel.Empty,
    val shape: NodeShape = NodeShape.Box,
    val style: NodeStyle = NodeStyle.Default,
    val ports: List<Port> = emptyList(),
    val payload: Map<String, String> = emptyMap(),
)

data class Edge(
    val from: NodeId,
    val to: NodeId,
    val label: RichLabel? = null,
    val kind: EdgeKind = EdgeKind.Solid,
    val arrow: ArrowEnds = ArrowEnds.ToOnly,
    val fromPort: PortId? = null,
    val toPort: PortId? = null,
    val style: EdgeStyle = EdgeStyle.Default,
)

data class Cluster(
    val id: NodeId,
    val label: RichLabel? = null,
    val children: List<NodeId> = emptyList(),
    val nestedClusters: List<Cluster> = emptyList(),
    val style: ClusterStyle = ClusterStyle.Default,
)
