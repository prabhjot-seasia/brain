package com.assurant.brain.conventions;

import com.assurant.brain.graph.node.ConventionNode;
import com.assurant.brain.graph.repository.ConventionNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("RulePackInstaller")
class RulePackInstallerTest {

    private ConventionNodeRepository repo;
    private RulePackInstaller installer;

    @BeforeEach
    void setup() {
        repo = mock(ConventionNodeRepository.class);
        installer = new RulePackInstaller(repo,
                mock(com.assurant.brain.jobs.AsyncJobService.class));
    }

    private RulePack samplePack() {
        return new RulePack("spring-boot", "1.0.0", "Spring Boot pack", List.of(
                new RulePack.PackConvention("Use constructor injection", "DI", 1.5),
                new RulePack.PackConvention("@Slf4j is banned, use @Log4j2", "LOGGING", 1.5)));
    }

    @Test
    @DisplayName("install saves a ConventionNode per pack convention with rulepack: source tag")
    void installSavesConventions() {
        when(repo.findByProjectIdAndSourceFilePrefix(anyString(), anyString()))
                .thenReturn(List.of());

        var result = installer.install("proj-1", samplePack(), true);

        assertThat(result.installed()).isEqualTo(2);
        assertThat(result.sourceTag()).isEqualTo("rulepack:spring-boot@1.0.0");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ConventionNode>> captor = ArgumentCaptor.forClass(List.class);
        verify(repo).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(2)
                .allSatisfy(c -> {
                    assertThat(c.getProjectId()).isEqualTo("proj-1");
                    assertThat(c.getSourceFile()).isEqualTo("rulepack:spring-boot@1.0.0");
                    assertThat(c.getTrustWeight()).isEqualTo(1.5);
                });
    }

    @Test
    @DisplayName("install rejected when thanosApproved=false")
    void installRequiresThanosApproval() {
        assertThatThrownBy(() -> installer.install("proj-1", samplePack(), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("THANOS approval required");
        verify(repo, never()).saveAll(org.mockito.ArgumentMatchers.<List<ConventionNode>>any());
    }

    @Test
    @DisplayName("install fails when pack already installed")
    void installFailsWhenAlreadyInstalled() {
        when(repo.findByProjectIdAndSourceFilePrefix(anyString(), anyString()))
                .thenReturn(List.of(new ConventionNode()));

        assertThatThrownBy(() -> installer.install("proj-1", samplePack(), true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already installed");
    }

    @Test
    @DisplayName("uninstall deletes by sourceFile prefix and reports count")
    void uninstallDeletes() {
        when(repo.findByProjectIdAndSourceFilePrefix("proj-1", "rulepack:spring-boot@1.0.0"))
                .thenReturn(List.of(new ConventionNode(), new ConventionNode()));

        var result = installer.uninstall("proj-1", "spring-boot", "1.0.0");

        assertThat(result.removed()).isEqualTo(2);
        assertThat(result.sourceTag()).isEqualTo("rulepack:spring-boot@1.0.0");
        verify(repo).deleteByProjectIdAndSourceFilePrefix("proj-1", "rulepack:spring-boot@1.0.0");
    }

    @Test
    @DisplayName("blank-rule conventions are silently skipped")
    void blankRuleConventionsSkipped() {
        when(repo.findByProjectIdAndSourceFilePrefix(anyString(), anyString())).thenReturn(List.of());
        var pack = new RulePack("p", "1.0", "desc", List.of(
                new RulePack.PackConvention("Real rule A", "DI", 1.5),
                new RulePack.PackConvention("  ", "DI", 1.5),
                new RulePack.PackConvention("Real rule B", "DI", 1.5)));

        var result = installer.install("proj-1", pack, true);

        assertThat(result.installed()).isEqualTo(2);
    }

    @Test
    @DisplayName("trustWeight=0 defaults to 1.0; out-of-range clamped to [0.1, 3.0]")
    void trustWeightClamped() {
        when(repo.findByProjectIdAndSourceFilePrefix(anyString(), anyString())).thenReturn(List.of());
        var pack = new RulePack("p", "1.0", "desc", List.of(
                new RulePack.PackConvention("zero", "DI", 0.0),
                new RulePack.PackConvention("low",  "DI", 0.05),
                new RulePack.PackConvention("high", "DI", 9.0)));

        installer.install("proj-1", pack, true);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<ConventionNode>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(repo).saveAll(captor.capture());
        var saved = captor.getValue();
        assertThat(saved).extracting(ConventionNode::getTrustWeight)
                .containsExactly(1.0, 0.1, 3.0);
    }

    @Test
    @DisplayName("uninstall validates blank inputs")
    void uninstallRejectsBlankArgs() {
        assertThatThrownBy(() -> installer.uninstall(null, "p", "1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("projectId");
        assertThatThrownBy(() -> installer.uninstall("proj", "", "1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("packId");
        assertThatThrownBy(() -> installer.uninstall("proj", "p", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("version");
    }

    @Test
    @DisplayName("install rejected when pack has more than MAX_CONVENTIONS_PER_PACK conventions")
    void installRejectsOversizedPack() {
        var conventions = new java.util.ArrayList<RulePack.PackConvention>();
        for (int i = 0; i < 600; i++) {
            conventions.add(new RulePack.PackConvention("rule-" + i, "DI", 1.5));
        }
        var pack = new RulePack("big", "1.0", "desc", conventions);

        assertThatThrownBy(() -> installer.install("proj-1", pack, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds 500 conventions");
    }

    @Test
    @DisplayName("validate rejects empty pack")
    void validateRejectsEmpty() {
        var bad = new RulePack("p", "1", null, List.of());
        assertThatThrownBy(() -> installer.install("proj-1", bad, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one convention");
    }
}
