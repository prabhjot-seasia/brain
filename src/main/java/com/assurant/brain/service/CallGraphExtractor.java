package com.assurant.brain.service;

import com.assurant.brain.graph.node.ClassNode;
import com.assurant.brain.graph.node.ModuleNode;
import com.assurant.brain.graph.node.ProjectNode;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Log4j2
@Component
public class CallGraphExtractor {

    public Set<String> collectCallTargets(TypeDeclaration<?> type) {
        Set<String> targets = new HashSet<>();
        if (type == null) return targets;

        List<MethodDeclaration> methods = type.findAll(MethodDeclaration.class);
        for (MethodDeclaration method : methods) {
            method.findAll(MethodCallExpr.class).forEach(call -> {
                call.getScope().ifPresent(scope -> {
                    if (scope instanceof NameExpr name) {
                        String ident = name.getNameAsString();
                        if (looksLikeType(ident)) {
                            targets.add(ident);
                        }
                    }
                });
            });
            method.findAll(ObjectCreationExpr.class).forEach(ctor ->
                    targets.add(ctor.getType().getNameAsString()));
        }
        return targets;
    }

    public void wireCalls(ProjectNode projectNode,
                           Map<ClassNode, Set<String>> pendingTargets) {
        if (projectNode == null || pendingTargets == null || pendingTargets.isEmpty()) return;

        Map<String, ClassNode> bySimpleName = buildSimpleNameIndex(projectNode);
        int edges = 0;

        for (Map.Entry<ClassNode, Set<String>> entry : pendingTargets.entrySet()) {
            ClassNode source = entry.getKey();
            for (String target : entry.getValue()) {
                ClassNode resolved = bySimpleName.get(target);
                if (resolved == null || resolved == source) continue;
                if (source.getCalls().contains(resolved)) continue;
                source.getCalls().add(resolved);
                edges++;
            }
        }
        if (edges > 0) {
            log.info("Wired {} intra-project CALLS edge(s) for project={}",
                    edges, projectNode.getId());
        }
    }

    private Map<String, ClassNode> buildSimpleNameIndex(ProjectNode projectNode) {
        Map<String, ClassNode> index = new HashMap<>();
        for (ModuleNode module : projectNode.getModules()) {
            for (ClassNode cls : module.getClasses()) {
                index.putIfAbsent(cls.getName(), cls);
            }
        }
        return index;
    }

    private boolean looksLikeType(String identifier) {
        if (identifier == null || identifier.isEmpty()) return false;
        char first = identifier.charAt(0);
        return Character.isUpperCase(first);
    }
}
