package com.assurant.brain.codegen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record PlanGraph(List<PlanNode> nodes, List<PlanEdge> edges) {

    public PlanGraph {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
    }

    public static PlanGraph empty() {
        return new PlanGraph(List.of(), List.of());
    }

    public int size() {
        return nodes.size();
    }

    public List<PlanNode> seedNodes() {
        return nodes.stream().filter(n -> !n.derived()).toList();
    }

    public List<PlanNode> derivedNodes() {
        return nodes.stream().filter(PlanNode::derived).toList();
    }

    public List<PlanNode> topologicalOrder() {
        Map<String, PlanNode> byId = new LinkedHashMap<>();
        for (PlanNode n : nodes) byId.put(n.blockId(), n);

        Map<String, Set<String>> incoming = new HashMap<>();
        Map<String, Set<String>> outgoing = new HashMap<>();
        for (PlanNode n : nodes) {
            incoming.put(n.blockId(), new LinkedHashSet<>());
            outgoing.put(n.blockId(), new LinkedHashSet<>());
        }
        for (PlanEdge e : edges) {
            if (byId.containsKey(e.from()) && byId.containsKey(e.to())) {
                outgoing.get(e.from()).add(e.to());
                incoming.get(e.to()).add(e.from());
            }
        }

        List<PlanNode> ordered = new ArrayList<>();
        Set<String> ready = new LinkedHashSet<>();
        for (PlanNode n : nodes) {
            if (incoming.get(n.blockId()).isEmpty()) ready.add(n.blockId());
        }

        while (!ready.isEmpty()) {
            String next = ready.iterator().next();
            ready.remove(next);
            ordered.add(byId.get(next));
            for (String target : outgoing.get(next)) {
                incoming.get(target).remove(next);
                if (incoming.get(target).isEmpty()) ready.add(target);
            }
        }

        if (ordered.size() != nodes.size()) {
            List<PlanNode> fallback = new ArrayList<>(ordered);
            for (PlanNode n : nodes) {
                if (!fallback.contains(n)) fallback.add(n);
            }
            return Collections.unmodifiableList(fallback);
        }
        return Collections.unmodifiableList(ordered);
    }
}
