package com.assurant.brain.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Component;

import java.util.List;

@Log4j2
@Component
@RequiredArgsConstructor
public class Neo4jIndexBootstrap {

    private final Neo4jClient neo4jClient;

    private static final List<String> INDEXES = List.of(
            "CREATE INDEX class_projectId_idx IF NOT EXISTS FOR (c:Class) ON (c.projectId)",
            "CREATE INDEX class_qualifiedName_idx IF NOT EXISTS FOR (c:Class) ON (c.qualifiedName)",
            "CREATE INDEX project_id_idx IF NOT EXISTS FOR (p:Project) ON (p.id)"
    );

    @EventListener(ApplicationReadyEvent.class)
    public void ensureIndexes() {
        for (String ddl : INDEXES) {
            try {
                neo4jClient.query(ddl).run();
                log.debug("Neo4j index ensured: {}", ddl);
            } catch (RuntimeException e) {
                log.warn("Neo4j index DDL failed (continuing): {} — {}", ddl, e.getMessage());
            }
        }
    }
}
