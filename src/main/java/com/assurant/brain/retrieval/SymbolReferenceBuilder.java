package com.assurant.brain.retrieval;

import com.assurant.brain.graph.node.SymbolReferenceNode;
import com.assurant.brain.graph.repository.SymbolReferenceNodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Log4j2
@Service
@RequiredArgsConstructor
public class SymbolReferenceBuilder {

    private static final String DEFAULT_SCHEME = "scip-java";

    private final SymbolReferenceNodeRepository repository;

    public record Member(String memberName, String kind) {}

    public List<SymbolReferenceNode> rebuildForClass(String projectId, String classFqn,
                                                      String language, List<Member> members) {
        if (projectId == null || classFqn == null) return List.of();
        List<SymbolReferenceNode> created = new ArrayList<>();

        SymbolReferenceNode classNode = new SymbolReferenceNode();
        classNode.setId(symbolId(projectId, classFqn));
        classNode.setProjectId(projectId);
        classNode.setSymbolFqn(classFqn);
        classNode.setKind("CLASS");
        classNode.setLanguage(language == null ? "java" : language);
        classNode.setScipScheme(DEFAULT_SCHEME);
        classNode.setDeclaredInClassFqn(classFqn);
        created.add(classNode);

        if (members != null) {
            for (Member member : members) {
                if (member.memberName() == null || member.memberName().isBlank()) continue;
                String memberFqn = classFqn + "#" + member.memberName();
                SymbolReferenceNode mNode = new SymbolReferenceNode();
                mNode.setId(symbolId(projectId, memberFqn));
                mNode.setProjectId(projectId);
                mNode.setSymbolFqn(memberFqn);
                mNode.setKind(member.kind() == null ? "METHOD" : member.kind().toUpperCase());
                mNode.setLanguage(language == null ? "java" : language);
                mNode.setScipScheme(DEFAULT_SCHEME);
                mNode.setDeclaredInClassFqn(classFqn);
                created.add(mNode);
            }
        }
        repository.saveAll(created);
        log.debug("Rebuilt {} SymbolReferenceNodes for class={}", created.size(), classFqn);
        return created;
    }

    public List<SymbolReferenceNode> findCallSites(String symbolFqn) {
        if (symbolFqn == null || symbolFqn.isBlank()) return List.of();
        try {
            return repository.findBySymbolFqn(symbolFqn);
        } catch (RuntimeException e) {
            log.debug("findCallSites failed for {}: {}", symbolFqn, e.getMessage());
            return List.of();
        }
    }

    private String symbolId(String projectId, String fqn) {
        return projectId + ":" + fqn;
    }
}
