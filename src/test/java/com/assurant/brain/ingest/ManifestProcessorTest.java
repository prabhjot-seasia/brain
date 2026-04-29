package com.assurant.brain.ingest;

import com.assurant.brain.dto.request.IngestManifest;
import com.assurant.brain.graph.node.ProjectNode;
import com.assurant.brain.graph.node.TenantNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ManifestProcessor")
class ManifestProcessorTest {

    private final ManifestProcessor processor = new ManifestProcessor();

    @Test
    @DisplayName("creates EnvironmentNodes from manifest descriptors")
    void createsEnvironmentNodes() {
        ProjectNode projectNode = newProjectNode();
        IngestManifest manifest = new IngestManifest(
                List.of(
                        new IngestManifest.EnvironmentDescriptor("dev", "dev.hyla.hylatest.com", "111111111111"),
                        new IngestManifest.EnvironmentDescriptor("uat", "uat.hyla.hylatest.com", "222222222222"),
                        new IngestManifest.EnvironmentDescriptor("prod", "hyla.hylamobile.com", "333333333333")),
                List.of(),
                null,
                null);

        processor.apply(projectNode, manifest);

        assertThat(projectNode.getEnvironments()).hasSize(3);
        assertThat(projectNode.getEnvironments())
                .anyMatch(e -> e.getName().equals("uat")
                        && e.getBaseDomain().equals("uat.hyla.hylatest.com")
                        && e.getAwsAccount().equals("222222222222"));
    }

    @Test
    @DisplayName("augments existing TenantNodes with manifest tenants without duplicating")
    void augmentsTenants() {
        ProjectNode projectNode = newProjectNode();
        TenantNode existing = new TenantNode();
        existing.setId(projectNode.getId() + ":tenant:vzw");
        existing.setName("vzw");
        existing.setSource("PROPERTIES_SUFFIX");
        projectNode.getTenants().add(existing);

        IngestManifest manifest = new IngestManifest(
                List.of(),
                List.of("vzw", "att", "google"),
                null,
                null);

        processor.apply(projectNode, manifest);

        assertThat(projectNode.getTenants()).hasSize(3);
        assertThat(projectNode.getTenants())
                .anyMatch(t -> t.getName().equals("att") && t.getSource().equals("MANIFEST"));
        assertThat(projectNode.getTenants())
                .anyMatch(t -> t.getName().equals("vzw") && t.getSource().equals("PROPERTIES_SUFFIX"));
    }

    @Test
    @DisplayName("registers ApiDocumentRegistryNode when manifest provides apiContractRegistry")
    void registersApiContractRegistry() {
        ProjectNode projectNode = newProjectNode();
        IngestManifest manifest = new IngestManifest(
                List.of(),
                List.of(),
                new IngestManifest.ApiContractRegistryRef(
                        "ce-api-document",
                        "src/main/resources/main-spec.yaml",
                        "src/main/resources/definitions"),
                null);

        processor.apply(projectNode, manifest);

        assertThat(projectNode.getApiDocumentRegistry()).isNotNull();
        assertThat(projectNode.getApiDocumentRegistry().getProjectId()).isEqualTo("ce-api-document");
        assertThat(projectNode.getApiDocumentRegistry().getMasterSpecPath())
                .isEqualTo("src/main/resources/main-spec.yaml");
    }

    @Test
    @DisplayName("does nothing when manifest is empty")
    void noopOnEmptyManifest() {
        ProjectNode projectNode = newProjectNode();
        processor.apply(projectNode, IngestManifest.empty());
        assertThat(projectNode.getEnvironments()).isEmpty();
        assertThat(projectNode.getTenants()).isEmpty();
        assertThat(projectNode.getApiDocumentRegistry()).isNull();
    }

    private ProjectNode newProjectNode() {
        ProjectNode node = new ProjectNode();
        node.setId("proj-1");
        node.setName("proj-1");
        return node;
    }
}
