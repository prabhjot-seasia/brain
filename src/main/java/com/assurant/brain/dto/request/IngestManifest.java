package com.assurant.brain.dto.request;

import java.util.List;

public record IngestManifest(
        List<EnvironmentDescriptor> environments,
        List<String> tenants,
        ApiContractRegistryRef apiContractRegistry,
        CloudConfigRef cloudConfig,
        String automationForProjectId) {

    public IngestManifest {
        environments = environments == null ? List.of() : List.copyOf(environments);
        tenants = tenants == null ? List.of() : List.copyOf(tenants);
    }

    public IngestManifest(List<EnvironmentDescriptor> environments, List<String> tenants,
                            ApiContractRegistryRef apiContractRegistry, CloudConfigRef cloudConfig) {
        this(environments, tenants, apiContractRegistry, cloudConfig, null);
    }

    public static IngestManifest empty() {
        return new IngestManifest(List.of(), List.of(), null, null, null);
    }

    public boolean isEmpty() {
        return environments.isEmpty()
                && tenants.isEmpty()
                && apiContractRegistry == null
                && cloudConfig == null
                && (automationForProjectId == null || automationForProjectId.isBlank());
    }

    public record EnvironmentDescriptor(
            String name,
            String baseDomain,
            String awsAccount) {}

    public record ApiContractRegistryRef(
            String projectId,
            String masterSpecPath,
            String definitionsPath) {}

    public record CloudConfigRef(
            String configServerBranch,
            String configRepoUrl,
            String configRepoPath) {}
}
