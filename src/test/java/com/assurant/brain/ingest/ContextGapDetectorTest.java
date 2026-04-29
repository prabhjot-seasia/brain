package com.assurant.brain.ingest;

import com.assurant.brain.graph.node.DatabaseTableNode;
import com.assurant.brain.graph.node.ProjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ContextGapDetector")
class ContextGapDetectorTest {

    private final ContextGapDetector detector = new ContextGapDetector();

    @Test
    @DisplayName("flags unknown config table as CONFIG_TABLE_DATA gap")
    void flagsUnknownConfigTable() {
        ProjectNode projectNode = new ProjectNode();
        String plan = "We will read carrier_config rows for VZW and merge with promo settings.";

        List<ContextGap> gaps = detector.detect(projectNode, plan);

        assertThat(gaps).anyMatch(g -> g.type() == ContextGapType.CONFIG_TABLE_DATA);
        assertThat(gaps.get(0).data().get("tableName")).isEqualTo("carrier_config");
    }

    @Test
    @DisplayName("does not flag config tables that are already ingested")
    void doesNotFlagKnownConfigTable() {
        ProjectNode projectNode = new ProjectNode();
        DatabaseTableNode known = new DatabaseTableNode();
        known.setTableName("carrier_config");
        projectNode.getOwnedTables().add(known);

        List<ContextGap> gaps = detector.detect(projectNode, "Update carrier_config row for VZW");

        assertThat(gaps).noneMatch(g -> g.type() == ContextGapType.CONFIG_TABLE_DATA);
    }

    @Test
    @DisplayName("flags secret references as SECRET_VALUE_NEEDED")
    void flagsSecretReferences() {
        ProjectNode projectNode = new ProjectNode();
        String plan = "The change exercises STRIPE_API_KEY and JWT_SECRET environment variables.";

        List<ContextGap> gaps = detector.detect(projectNode, plan);

        assertThat(gaps).anyMatch(g -> g.type() == ContextGapType.SECRET_VALUE_NEEDED);
    }

    @Test
    @DisplayName("caps total gaps at MAX_GAP_QUESTIONS_PER_SESSION")
    void capsAtMaximum() {
        ProjectNode projectNode = new ProjectNode();
        String plan = """
                Reading carrier_config and feature_flags and app_config rows.
                Also using STRIPE_API_KEY and SLACK_TOKEN secrets,
                and ${apps.catalog} placeholder is unresolved.
                """;

        List<ContextGap> gaps = detector.detect(projectNode, plan);

        assertThat(gaps).hasSize(ContextGapDetector.MAX_GAP_QUESTIONS_PER_SESSION);
    }
}
