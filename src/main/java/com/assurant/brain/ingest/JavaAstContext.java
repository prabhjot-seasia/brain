package com.assurant.brain.ingest;

import com.assurant.brain.graph.node.ProjectNode;
import com.github.javaparser.ast.CompilationUnit;

import java.nio.file.Path;

public record JavaAstContext(
        CompilationUnit cu,
        Path filePath,
        String projectId,
        ProjectNode projectNode) {
}
