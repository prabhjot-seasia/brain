package com.assurant.brain.rest.v1.conventions.controller;

import com.assurant.brain.conventions.RulePack;
import com.assurant.brain.conventions.RulePackInstaller;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("RulePackController")
class RulePackControllerTest {

    private static final String APPROVAL_TOKEN = "test-approval-token";

    private RulePackInstaller installer;
    private RulePackController controller;

    @BeforeEach
    void setup() {
        installer = mock(RulePackInstaller.class);
        controller = new RulePackController(installer,
                mock(com.assurant.brain.jobs.AsyncJobService.class));
        ReflectionTestUtils.setField(controller, "approvalToken", APPROVAL_TOKEN);
    }

    private RulePack samplePack() {
        return new RulePack("spring-boot", "1.0.0", "Spring Boot pack", List.of(
                new RulePack.PackConvention("Use constructor injection", "DI", 1.5)));
    }

    @Test
    @DisplayName("install delegates to RulePackInstaller and returns 200 when X-Brain-Approval matches")
    void installDelegates() {
        var pack = samplePack();
        var body = new RulePackController.InstallRequest("1.0.0", pack);
        when(installer.install(eq("proj-1"), eq(pack), eq(true)))
                .thenReturn(new RulePackInstaller.InstallResult(1, "rulepack:spring-boot@1.0.0"));

        ResponseEntity<RulePackInstaller.InstallResult> resp =
                controller.install("proj-1", APPROVAL_TOKEN, body);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().installed()).isEqualTo(1);
        verify(installer).install("proj-1", pack, true);
    }

    @Test
    @DisplayName("install rejected with 403 when X-Brain-Approval header missing")
    void installRejectsMissingHeader() {
        var body = new RulePackController.InstallRequest("1.0.0", samplePack());

        assertThatThrownBy(() -> controller.install("proj-1", null, body))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("THANOS approval header");
        verify(installer, never()).install(any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("install rejected with 403 when X-Brain-Approval header mismatched")
    void installRejectsBadHeader() {
        var body = new RulePackController.InstallRequest("1.0.0", samplePack());

        assertThatThrownBy(() -> controller.install("proj-1", "wrong-token", body))
                .isInstanceOf(ResponseStatusException.class);
        verify(installer, never()).install(any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("install rejected when approvalToken config is blank (fail-closed)")
    void installFailsClosedWhenTokenUnset() {
        ReflectionTestUtils.setField(controller, "approvalToken", "");
        var body = new RulePackController.InstallRequest("1.0.0", samplePack());

        assertThatThrownBy(() -> controller.install("proj-1", "anything", body))
                .isInstanceOf(ResponseStatusException.class);
        verify(installer, never()).install(any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("uninstall delegates and returns 200 when approval header matches")
    void uninstallDelegates() {
        when(installer.uninstall("proj-1", "spring-boot", "1.0.0"))
                .thenReturn(new RulePackInstaller.UninstallResult(2, "rulepack:spring-boot@1.0.0"));

        ResponseEntity<RulePackInstaller.UninstallResult> resp =
                controller.uninstall("proj-1", "spring-boot", "1.0.0", APPROVAL_TOKEN);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().removed()).isEqualTo(2);
        verify(installer).uninstall("proj-1", "spring-boot", "1.0.0");
    }

    @Test
    @DisplayName("uninstall rejected with 403 when approval header missing")
    void uninstallRejectsMissingHeader() {
        assertThatThrownBy(() -> controller.uninstall("proj-1", "spring-boot", "1.0.0", null))
                .isInstanceOf(ResponseStatusException.class);
        verify(installer, never()).uninstall(any(), any(), any());
    }

    private static <T> T any() { return org.mockito.ArgumentMatchers.any(); }
    private static boolean anyBoolean() { return org.mockito.ArgumentMatchers.anyBoolean(); }
}
