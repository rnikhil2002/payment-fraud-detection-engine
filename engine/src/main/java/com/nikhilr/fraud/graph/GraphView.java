package com.nikhilr.fraud.graph;

import java.util.List;

/** Nodes and links for the dashboard's interactive graph. */
public record GraphView(String rootAccount, List<GraphNode> nodes, List<GraphLink> links) {

    public record GraphNode(String id, String label, String name, boolean flagged) {
    }

    public record GraphLink(String source, String target, String type, Double amount) {
    }
}
