package com.assurant.brain.codegen;

import com.assurant.brain.graph.node.ClassNode;
import com.assurant.brain.graph.node.LibraryNode;
import com.assurant.brain.graph.node.ModuleNode;
import com.assurant.brain.graph.node.ProjectNode;
import com.assurant.brain.graph.node.SymbolReferenceNode;
import com.assurant.brain.graph.repository.ProjectNodeRepository;
import com.assurant.brain.graph.repository.SymbolReferenceNodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Log4j2
@Service
@RequiredArgsConstructor
public class SymbolDictionaryBuilder {

    private static final int MAX_SYMBOLS = 500;

    private final ProjectNodeRepository projectNodeRepository;
    private final SymbolReferenceNodeRepository symbolReferenceNodeRepository;

    public SymbolDictionary build(String projectId) {
        if (projectId == null || projectId.isBlank()) return SymbolDictionary.EMPTY;
        try {
            Optional<ProjectNode> project = projectNodeRepository.findById(projectId);
            if (project.isEmpty()) {
                log.debug("SymbolDictionary: project={} not found, returning empty", projectId);
                return SymbolDictionary.EMPTY;
            }
            Set<String> classes = collectClasses(project.get());
            Set<String> libraries = collectLibraries(project.get());
            Set<String> symbols = collectSymbols(projectId);
            log.debug("SymbolDictionary built for project={} classes={} libraries={} symbols={}",
                    projectId, classes.size(), libraries.size(), symbols.size());
            return new SymbolDictionary(classes, libraries, symbols);
        } catch (RuntimeException e) {
            log.warn("SymbolDictionary build failed for project={}: {} — returning empty", projectId, e.getMessage());
            return SymbolDictionary.EMPTY;
        }
    }

    private Set<String> collectClasses(ProjectNode project) {
        Set<String> out = new LinkedHashSet<>();
        if (project.getModules() == null) return out;
        for (ModuleNode module : project.getModules()) {
            if (module.getClasses() == null) continue;
            for (ClassNode cls : module.getClasses()) {
                if (cls.getQualifiedName() != null && !cls.getQualifiedName().isBlank()) {
                    out.add(cls.getQualifiedName());
                }
            }
        }
        return out;
    }

    private Set<String> collectLibraries(ProjectNode project) {
        Set<String> out = new LinkedHashSet<>();
        if (project.getLibraries() == null) return out;
        for (LibraryNode lib : project.getLibraries()) {
            String coord = renderCoordinate(lib);
            if (coord != null) out.add(coord);
        }
        return out;
    }

    private String renderCoordinate(LibraryNode lib) {
        String groupId = lib.getGroupId();
        String artifactId = lib.getArtifactId();
        String version = lib.getVersion();
        String name = lib.getName();
        if (groupId != null && artifactId != null) {
            return groupId + ":" + artifactId + (version != null ? ":" + version : "");
        }
        if (name != null && !name.isBlank()) {
            return version != null ? name + ":" + version : name;
        }
        return null;
    }

    private Set<String> collectSymbols(String projectId) {
        Set<String> out = new LinkedHashSet<>();
        try {
            List<SymbolReferenceNode> rows = symbolReferenceNodeRepository.findByProjectId(projectId);
            int count = 0;
            for (SymbolReferenceNode sym : rows) {
                if (sym.getSymbolFqn() == null || sym.getSymbolFqn().isBlank()) continue;
                out.add(sym.getSymbolFqn());
                if (++count >= MAX_SYMBOLS) break;
            }
        } catch (RuntimeException e) {
            log.debug("Symbol reference fetch failed for project={}: {}", projectId, e.getMessage());
        }
        return out;
    }
}
