package com.assurant.brain.codegen;

import com.assurant.brain.dao.ChunkRepository;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Log4j2
@Component
@RequiredArgsConstructor
public class HallucinationDetector {

    private static final Set<String> KNOWN_PREFIXES = Set.of(
            "java.", "javax.", "jakarta.", "org.springframework.", "org.apache.",
            "com.fasterxml.", "lombok.", "org.hibernate.", "org.slf4j.",
            "org.junit.", "org.mockito.", "org.assertj.", "io.cucumber.",
            "org.neo4j.", "com.github.javaparser.", "org.eclipse.jgit."
    );

    private final ChunkRepository chunkRepository;

    public List<String> detect(Map<String, String> generatedFiles, String projectId) {
        List<String> hallucinations = new ArrayList<>();

        Set<String> knownChunkNames = chunkRepository.findChunkNamesByProjectId(projectId);

        for (Map.Entry<String, String> entry : generatedFiles.entrySet()) {
            String filePath = entry.getKey();
            String content = entry.getValue();

            if (!filePath.endsWith(".java")) continue;

            ParseResult<CompilationUnit> parseResult = new JavaParser().parse(content);
            if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) continue;

            CompilationUnit cu = parseResult.getResult().get();
            hallucinations.addAll(checkImports(filePath, cu, knownChunkNames));
        }

        log.info("Hallucination detection: {} issues found across {} files",
                hallucinations.size(), generatedFiles.size());
        return hallucinations;
    }

    private List<String> checkImports(String filePath, CompilationUnit cu, Set<String> knownChunkNames) {
        List<String> issues = new ArrayList<>();

        for (ImportDeclaration imp : cu.getImports()) {
            String importName = imp.getNameAsString();

            if (isKnownLibrary(importName)) continue;

            String className = importName.contains(".")
                    ? importName.substring(importName.lastIndexOf('.') + 1)
                    : importName;

            boolean existsInProject = knownChunkNames.stream()
                    .anyMatch(chunk -> chunk.contains(className));

            if (!existsInProject) {
                issues.add("[HALLUCINATION] " + filePath + ": Import '" + importName +
                        "' references class '" + className + "' not found in project graph");
            }
        }

        return issues;
    }

    private boolean isKnownLibrary(String importName) {
        return KNOWN_PREFIXES.stream().anyMatch(importName::startsWith);
    }
}
