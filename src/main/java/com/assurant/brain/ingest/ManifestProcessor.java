package com.assurant.brain.ingest;

import com.assurant.brain.dto.request.IngestManifest;
import com.assurant.brain.graph.node.ApiDocumentRegistryNode;
import com.assurant.brain.graph.node.EnvironmentNode;
import com.assurant.brain.graph.node.ProjectNode;
import com.assurant.brain.graph.node.TenantNode;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

@Log4j2
@Component
public class ManifestProcessor {

    public void apply(ProjectNode projectNode, IngestManifest manifest) {
        if (manifest == null || manifest.isEmpty()) return;

        int environmentsAdded = applyEnvironments(projectNode, manifest);
        int tenantsAdded = applyTenants(projectNode, manifest);
        boolean registryAdded = applyApiContractRegistry(projectNode, manifest);
        boolean cloudConfigAdded = applyCloudConfig(projectNode, manifest);

        log.info("ManifestProcessor applied for project={}: environments={}, tenants={}, registry={}, cloudConfig={}",
                projectNode.getId(), environmentsAdded, tenantsAdded, registryAdded, cloudConfigAdded);
    }

    private int applyEnvironments(ProjectNode projectNode, IngestManifest manifest) {
        if (manifest.environments().isEmpty()) return 0;
        Set<String> existing = new HashSet<>();
        projectNode.getEnvironments().forEach(e -> existing.add(e.getId()));
        int added = 0;
        for (IngestManifest.EnvironmentDescriptor descriptor : manifest.environments()) {
            if (StringUtils.isBlank(descriptor.name())) continue;
            String id = projectNode.getId() + ":env:" + descriptor.name();
            if (!existing.add(id)) continue;
            EnvironmentNode node = new EnvironmentNode();
            node.setId(id);
            node.setName(descriptor.name());
            node.setBaseDomain(descriptor.baseDomain());
            node.setAwsAccount(descriptor.awsAccount());
            projectNode.getEnvironments().add(node);
            added++;
        }
        return added;
    }

    private int applyTenants(ProjectNode projectNode, IngestManifest manifest) {
        if (manifest.tenants().isEmpty()) return 0;
        Set<String> existing = new HashSet<>();
        projectNode.getTenants().forEach(t -> existing.add(t.getId()));
        int added = 0;
        for (String tenantName : manifest.tenants()) {
            if (StringUtils.isBlank(tenantName)) continue;
            String normalized = tenantName.toLowerCase().trim();
            String id = projectNode.getId() + ":tenant:" + normalized;
            if (!existing.add(id)) continue;
            TenantNode node = new TenantNode();
            node.setId(id);
            node.setName(normalized);
            node.setDisplayName(tenantName);
            node.setSource("MANIFEST");
            projectNode.getTenants().add(node);
            added++;
        }
        return added;
    }

    private boolean applyApiContractRegistry(ProjectNode projectNode, IngestManifest manifest) {
        IngestManifest.ApiContractRegistryRef ref = manifest.apiContractRegistry();
        if (ref == null || StringUtils.isBlank(ref.projectId())) return false;
        if (projectNode.getApiDocumentRegistry() != null) return false;

        ApiDocumentRegistryNode registry = new ApiDocumentRegistryNode();
        registry.setId(projectNode.getId() + ":apiDocRegistry:" + ref.projectId());
        registry.setProjectId(ref.projectId());
        registry.setMasterSpecPath(ref.masterSpecPath());
        projectNode.setApiDocumentRegistry(registry);
        return true;
    }

    private boolean applyCloudConfig(ProjectNode projectNode, IngestManifest manifest) {
        IngestManifest.CloudConfigRef ref = manifest.cloudConfig();
        if (ref == null || StringUtils.isBlank(ref.configRepoUrl())) return false;
        log.info("ManifestProcessor: cloud-config repo={} branch={} path={} captured for project={}",
                ref.configRepoUrl(), ref.configServerBranch(), ref.configRepoPath(), projectNode.getId());
        return true;
    }
}
