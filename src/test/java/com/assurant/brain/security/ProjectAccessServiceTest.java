package com.assurant.brain.security;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.dao.GeneratedDocumentRepository;
import com.assurant.brain.dao.ProjectMemberRepository;
import com.assurant.brain.domain.GeneratedDocument;
import com.assurant.brain.domain.ProjectMember;
import com.assurant.brain.enums.ProjectRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@DisplayName("ProjectAccessService")
class ProjectAccessServiceTest {

    private ProjectMemberRepository memberRepo;
    private GeneratedDocumentRepository docRepo;

    @BeforeEach
    void setup() {
        memberRepo = mock(ProjectMemberRepository.class);
        docRepo = mock(GeneratedDocumentRepository.class);
    }

    private BrainProperties propsWithEnforcement(boolean enforce) {
        var sec = new BrainProperties.Security("s", 60, "*", 10, 60, 60000, enforce);
        return new BrainProperties(null, null, null, null, null, null, null, null, null,
                sec, null, null, null, null, null, null, null, null,
                new BrainProperties.Docs("mmdc", 60, 0, 0, 5));
    }

    private ProjectMember member(String projectId, String userId, ProjectRole role) {
        ProjectMember m = new ProjectMember();
        m.setProjectId(projectId);
        m.setUserId(userId);
        m.setRole(role);
        return m;
    }

    private ProjectAccessService newSvc(BrainProperties props) {
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<com.assurant.brain.jobs.AsyncJobService> jobs =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(jobs.getIfAvailable()).thenReturn(null);
        return new ProjectAccessService(memberRepo, props, docRepo, jobs);
    }

    @Nested
    @DisplayName("canRead")
    class CanRead {
        @Test
        @DisplayName("blank projectId returns true (no scope to check)")
        void blankProjectId() {
            var svc = newSvc(propsWithEnforcement(false));
            assertThat(svc.canRead(null)).isTrue();
            assertThat(svc.canRead("")).isTrue();
        }

        @Test
        @DisplayName("soft-mode: anonymous user is allowed (logged would-deny)")
        void softModeAnonAllowed() {
            try (MockedStatic<SecurityUtils> m = mockStatic(SecurityUtils.class)) {
                m.when(SecurityUtils::currentUserId).thenReturn(null);
                var svc = newSvc(propsWithEnforcement(false));
                assertThat(svc.canRead("ce-imei")).isTrue();
            }
        }

        @Test
        @DisplayName("enforce-mode: anonymous user is denied")
        void enforceModeAnonDenied() {
            try (MockedStatic<SecurityUtils> m = mockStatic(SecurityUtils.class)) {
                m.when(SecurityUtils::currentUserId).thenReturn(null);
                var svc = newSvc(propsWithEnforcement(true));
                assertThat(svc.canRead("ce-imei")).isFalse();
            }
        }

        @Test
        @DisplayName("soft-mode: non-member is allowed (logged would-deny)")
        void softModeNonMemberAllowed() {
            try (MockedStatic<SecurityUtils> m = mockStatic(SecurityUtils.class)) {
                m.when(SecurityUtils::currentUserId).thenReturn("alice");
                when(memberRepo.findByProjectIdAndUserId("ce-imei", "alice")).thenReturn(Optional.empty());
                var svc = newSvc(propsWithEnforcement(false));
                assertThat(svc.canRead("ce-imei")).isTrue();
            }
        }

        @Test
        @DisplayName("enforce-mode: non-member is denied")
        void enforceModeNonMemberDenied() {
            try (MockedStatic<SecurityUtils> m = mockStatic(SecurityUtils.class)) {
                m.when(SecurityUtils::currentUserId).thenReturn("alice");
                when(memberRepo.findByProjectIdAndUserId("ce-imei", "alice")).thenReturn(Optional.empty());
                var svc = newSvc(propsWithEnforcement(true));
                assertThat(svc.canRead("ce-imei")).isFalse();
            }
        }

        @Test
        @DisplayName("VIEWER role grants read")
        void viewerCanRead() {
            try (MockedStatic<SecurityUtils> m = mockStatic(SecurityUtils.class)) {
                m.when(SecurityUtils::currentUserId).thenReturn("alice");
                when(memberRepo.findByProjectIdAndUserId("ce-imei", "alice"))
                        .thenReturn(Optional.of(member("ce-imei", "alice", ProjectRole.VIEWER)));
                var svc = newSvc(propsWithEnforcement(true));
                assertThat(svc.canRead("ce-imei")).isTrue();
            }
        }
    }

    @Nested
    @DisplayName("canWrite")
    class CanWrite {
        @Test
        @DisplayName("VIEWER role denies write in enforce mode")
        void viewerCannotWrite() {
            try (MockedStatic<SecurityUtils> m = mockStatic(SecurityUtils.class)) {
                m.when(SecurityUtils::currentUserId).thenReturn("alice");
                when(memberRepo.findByProjectIdAndUserId("ce-imei", "alice"))
                        .thenReturn(Optional.of(member("ce-imei", "alice", ProjectRole.VIEWER)));
                var svc = newSvc(propsWithEnforcement(true));
                assertThat(svc.canWrite("ce-imei")).isFalse();
            }
        }

        @Test
        @DisplayName("MEMBER role grants write")
        void memberCanWrite() {
            try (MockedStatic<SecurityUtils> m = mockStatic(SecurityUtils.class)) {
                m.when(SecurityUtils::currentUserId).thenReturn("alice");
                when(memberRepo.findByProjectIdAndUserId("ce-imei", "alice"))
                        .thenReturn(Optional.of(member("ce-imei", "alice", ProjectRole.MEMBER)));
                var svc = newSvc(propsWithEnforcement(true));
                assertThat(svc.canWrite("ce-imei")).isTrue();
            }
        }
    }

    @Nested
    @DisplayName("canAdminister")
    class CanAdminister {
        @Test
        @DisplayName("OWNER role grants admin; MEMBER does not")
        void onlyOwnerAdmins() {
            try (MockedStatic<SecurityUtils> m = mockStatic(SecurityUtils.class)) {
                m.when(SecurityUtils::currentUserId).thenReturn("alice");
                when(memberRepo.findByProjectIdAndUserId("ce-imei", "alice"))
                        .thenReturn(Optional.of(member("ce-imei", "alice", ProjectRole.OWNER)));
                var svc = newSvc(propsWithEnforcement(true));
                assertThat(svc.canAdminister("ce-imei")).isTrue();

                when(memberRepo.findByProjectIdAndUserId("ce-imei", "alice"))
                        .thenReturn(Optional.of(member("ce-imei", "alice", ProjectRole.MEMBER)));
                assertThat(svc.canAdminister("ce-imei")).isFalse();
            }
        }
    }

    @Nested
    @DisplayName("canReadDocument / canWriteDocument")
    class DocumentChecks {
        @Test
        @DisplayName("missing document → allowed (404 surfaces from controller, not 403)")
        void missingDocAllows() {
            UUID id = UUID.randomUUID();
            when(docRepo.findById(id)).thenReturn(Optional.empty());
            var svc = newSvc(propsWithEnforcement(true));
            assertThat(svc.canReadDocument(id)).isTrue();
            assertThat(svc.canWriteDocument(id)).isTrue();
        }

        @Test
        @DisplayName("document found → delegates to canRead/canWrite for projectId")
        void documentDelegates() {
            UUID id = UUID.randomUUID();
            GeneratedDocument doc = new GeneratedDocument();
            doc.setId(id);
            doc.setProjectId("ce-imei");
            when(docRepo.findById(id)).thenReturn(Optional.of(doc));
            try (MockedStatic<SecurityUtils> m = mockStatic(SecurityUtils.class)) {
                m.when(SecurityUtils::currentUserId).thenReturn("alice");
                when(memberRepo.findByProjectIdAndUserId("ce-imei", "alice"))
                        .thenReturn(Optional.of(member("ce-imei", "alice", ProjectRole.VIEWER)));
                var svc = newSvc(propsWithEnforcement(true));
                assertThat(svc.canReadDocument(id)).isTrue();
                assertThat(svc.canWriteDocument(id)).isFalse();  // VIEWER can't write
            }
        }

        @Test
        @DisplayName("null documentId returns true (no scope)")
        void nullDocId() {
            var svc = newSvc(propsWithEnforcement(true));
            assertThat(svc.canReadDocument(null)).isTrue();
            assertThat(svc.canWriteDocument(null)).isTrue();
        }
    }

    @Nested
    @DisplayName("canReadJob — async-job stream + polling auth (UX-Q3 C13)")
    class JobChecks {

        @Test
        @DisplayName("null jobId allowed (no scope, e.g. /jobs?projectId=…)")
        void nullJobIdAllowed() {
            var svc = newSvc(propsWithEnforcement(true));
            assertThat(svc.canReadJob(null)).isTrue();
        }

        @Test
        @DisplayName("AsyncJobService unavailable → fail-open (so /jobs/* boots even if jobs slice fails to wire)")
        void asyncServiceUnavailableAllows() {
            UUID jobId = UUID.randomUUID();
            @SuppressWarnings("unchecked")
            org.springframework.beans.factory.ObjectProvider<com.assurant.brain.jobs.AsyncJobService> provider =
                    mock(org.springframework.beans.factory.ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(null);
            var svc = new ProjectAccessService(memberRepo, propsWithEnforcement(true), docRepo, provider);
            assertThat(svc.canReadJob(jobId)).isTrue();
        }

        @Test
        @DisplayName("strict mode + non-member → DENIED (403 surfaces from @PreAuthorize)")
        void strictModeNonMemberDenied() {
            UUID jobId = UUID.randomUUID();
            @SuppressWarnings("unchecked")
            org.springframework.beans.factory.ObjectProvider<com.assurant.brain.jobs.AsyncJobService> provider =
                    mock(org.springframework.beans.factory.ObjectProvider.class);
            com.assurant.brain.jobs.AsyncJobService asyncSvc = mock(com.assurant.brain.jobs.AsyncJobService.class);
            when(asyncSvc.projectIdOf(jobId)).thenReturn(Optional.of("ce-imei"));
            when(provider.getIfAvailable()).thenReturn(asyncSvc);

            try (MockedStatic<SecurityUtils> m = mockStatic(SecurityUtils.class)) {
                m.when(SecurityUtils::currentUserId).thenReturn("eve-not-a-member");
                when(memberRepo.findByProjectIdAndUserId("ce-imei", "eve-not-a-member"))
                        .thenReturn(Optional.empty());
                var svc = new ProjectAccessService(memberRepo, propsWithEnforcement(true), docRepo, provider);
                assertThat(svc.canReadJob(jobId)).as("strict-mode non-member must be denied").isFalse();
            }
        }

        @Test
        @DisplayName("strict mode + project member with VIEWER role → ALLOWED")
        void strictModeViewerAllowed() {
            UUID jobId = UUID.randomUUID();
            @SuppressWarnings("unchecked")
            org.springframework.beans.factory.ObjectProvider<com.assurant.brain.jobs.AsyncJobService> provider =
                    mock(org.springframework.beans.factory.ObjectProvider.class);
            com.assurant.brain.jobs.AsyncJobService asyncSvc = mock(com.assurant.brain.jobs.AsyncJobService.class);
            when(asyncSvc.projectIdOf(jobId)).thenReturn(Optional.of("ce-imei"));
            when(provider.getIfAvailable()).thenReturn(asyncSvc);

            try (MockedStatic<SecurityUtils> m = mockStatic(SecurityUtils.class)) {
                m.when(SecurityUtils::currentUserId).thenReturn("alice");
                when(memberRepo.findByProjectIdAndUserId("ce-imei", "alice"))
                        .thenReturn(Optional.of(member("ce-imei", "alice", ProjectRole.VIEWER)));
                var svc = new ProjectAccessService(memberRepo, propsWithEnforcement(true), docRepo, provider);
                assertThat(svc.canReadJob(jobId)).as("VIEWER membership grants READ on job").isTrue();
            }
        }

        @Test
        @DisplayName("soft mode + non-member → ALLOWED with would-deny WARN log")
        void softModeNonMemberAllowed() {
            UUID jobId = UUID.randomUUID();
            @SuppressWarnings("unchecked")
            org.springframework.beans.factory.ObjectProvider<com.assurant.brain.jobs.AsyncJobService> provider =
                    mock(org.springframework.beans.factory.ObjectProvider.class);
            com.assurant.brain.jobs.AsyncJobService asyncSvc = mock(com.assurant.brain.jobs.AsyncJobService.class);
            when(asyncSvc.projectIdOf(jobId)).thenReturn(Optional.of("ce-imei"));
            when(provider.getIfAvailable()).thenReturn(asyncSvc);

            try (MockedStatic<SecurityUtils> m = mockStatic(SecurityUtils.class)) {
                m.when(SecurityUtils::currentUserId).thenReturn("eve-not-a-member");
                when(memberRepo.findByProjectIdAndUserId("ce-imei", "eve-not-a-member"))
                        .thenReturn(Optional.empty());
                var svc = new ProjectAccessService(memberRepo, propsWithEnforcement(false), docRepo, provider);
                assertThat(svc.canReadJob(jobId)).as("soft-mode allows non-member access").isTrue();
            }
        }

        @Test
        @DisplayName("job not found in DB → fail-open (gives 404 from controller, not 403)")
        void jobNotFoundAllows() {
            UUID jobId = UUID.randomUUID();
            @SuppressWarnings("unchecked")
            org.springframework.beans.factory.ObjectProvider<com.assurant.brain.jobs.AsyncJobService> provider =
                    mock(org.springframework.beans.factory.ObjectProvider.class);
            com.assurant.brain.jobs.AsyncJobService asyncSvc = mock(com.assurant.brain.jobs.AsyncJobService.class);
            when(asyncSvc.projectIdOf(jobId)).thenReturn(Optional.empty());
            when(provider.getIfAvailable()).thenReturn(asyncSvc);

            var svc = new ProjectAccessService(memberRepo, propsWithEnforcement(true), docRepo, provider);
            assertThat(svc.canReadJob(jobId)).as("missing job → allowed (404 surfaces from controller)").isTrue();
        }
    }
}
