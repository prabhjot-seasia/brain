package com.assurant.brain.codegen;

import com.assurant.brain.dao.LearningEventRepository;
import com.assurant.brain.dao.ProjectRepository;
import com.assurant.brain.domain.LearningEvent;
import com.assurant.brain.domain.Project;
import com.assurant.brain.enums.LearningEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("StyleFingerprintAuditTask")
class StyleFingerprintAuditTaskTest {

    private ProjectRepository projectRepo;
    private VectorStore vectorStore;
    private LearningEventRepository learningEventRepo;
    private StyleFingerprintAuditTask task;

    @BeforeEach
    void setup() {
        projectRepo = mock(ProjectRepository.class);
        vectorStore = mock(VectorStore.class);
        learningEventRepo = mock(LearningEventRepository.class);
        StyleFingerprintBuilder builder = new StyleFingerprintBuilder();
        task = new StyleFingerprintAuditTask(projectRepo, vectorStore, builder, learningEventRepo);
        ReflectionTestUtils.setField(task, "enabled", true);
        ReflectionTestUtils.setField(task, "cron", "0 15 4 * * *");
    }

    @Test
    @DisplayName("disabled flag → no work, no DB writes")
    void disabledNoOps() {
        ReflectionTestUtils.setField(task, "enabled", false);
        task.auditAllProjects();
        verify(projectRepo, never()).findAll();
        verify(learningEventRepo, never()).save(any());
    }

    @Test
    @DisplayName("project with no source samples → skipped (no SNAPSHOT written)")
    void noSamplesSkipped() {
        Project p = new Project();
        p.setId("ce-imei");
        when(projectRepo.findAll()).thenReturn(List.of(p));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        when(learningEventRepo.findFirstByProjectIdAndEventTypeOrderByCreatedAtDesc(any(), any()))
                .thenReturn(Optional.empty());

        task.auditAllProjects();

        verify(learningEventRepo, never()).save(any());
    }

    @Test
    @DisplayName("first run for a project (no previous snapshot) → writes one SNAPSHOT, no DRIFT")
    void firstRunWritesSnapshot() {
        Project p = new Project();
        p.setId("ce-imei");
        when(projectRepo.findAll()).thenReturn(List.of(p));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(
                List.of(new Document("public class A { public int run() { return 1; } }")));
        when(learningEventRepo.findFirstByProjectIdAndEventTypeOrderByCreatedAtDesc(any(), any()))
                .thenReturn(Optional.empty());

        task.auditAllProjects();

        ArgumentCaptor<LearningEvent> captor = ArgumentCaptor.forClass(LearningEvent.class);
        verify(learningEventRepo, times(1)).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo(LearningEventType.STYLE_FINGERPRINT_SNAPSHOT);
        assertThat(captor.getValue().getProjectId()).isEqualTo("ce-imei");
    }

    @Test
    @DisplayName("avg method length drifts >= 1.5σ from baseline → DRIFT event written before SNAPSHOT")
    void driftDetected() {
        Project p = new Project();
        p.setId("ce-imei");
        when(projectRepo.findAll()).thenReturn(List.of(p));
        // current sample: a fat 20+-line method
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(new Document("""
                package x;
                public class Big {
                    public int huge() {
                        int a = 1; int b = 2; int c = 3; int d = 4;
                        int e = 5; int f = 6; int g = 7; int h = 8;
                        int i = 9; int j = 10; int k = 11; int l = 12;
                        int m = 13; int n = 14; int o = 15; int p = 16;
                        int q = 17; int r = 18; int s = 19; int t = 20;
                        return a+b+c+d+e+f+g+h+i+j+k+l+m+n+o+p+q+r+s+t;
                    }
                }
                """)));
        // previous baseline: methods averaged 3 lines, very tight
        Map<String, Object> prevDetails = new HashMap<>();
        prevDetails.put("sampledMethods", 50);
        prevDetails.put("avgMethodLines", 3.0);
        prevDetails.put("methodLinesStdev", 1.0);
        prevDetails.put("p90MethodLines", 5);
        prevDetails.put("returnEarlyRatio", 0.4);
        prevDetails.put("varUsageRatio", 0.3);
        prevDetails.put("streamUsageRatio", 0.2);
        prevDetails.put("lambdaUsageRatio", 0.4);
        prevDetails.put("avgImportsPerFile", 5);
        prevDetails.put("commentDensityPct", 0d);
        LearningEvent prevSnap = new LearningEvent();
        prevSnap.setEventType(LearningEventType.STYLE_FINGERPRINT_SNAPSHOT);
        prevSnap.setProjectId("ce-imei");
        prevSnap.setDetails(prevDetails);
        when(learningEventRepo.findFirstByProjectIdAndEventTypeOrderByCreatedAtDesc(
                "ce-imei", LearningEventType.STYLE_FINGERPRINT_SNAPSHOT))
                .thenReturn(Optional.of(prevSnap));

        task.auditAllProjects();

        ArgumentCaptor<LearningEvent> captor = ArgumentCaptor.forClass(LearningEvent.class);
        verify(learningEventRepo, times(2)).save(captor.capture());

        List<LearningEvent> saved = captor.getAllValues();
        assertThat(saved).extracting(LearningEvent::getEventType)
                .containsExactly(LearningEventType.STYLE_FINGERPRINT_DRIFT,
                                 LearningEventType.STYLE_FINGERPRINT_SNAPSHOT);
        assertThat(saved.get(0).getConventionRule()).contains("drifted");
    }

    @Test
    @DisplayName("project listing failure → caught, returns silently")
    void listingFailureSafe() {
        when(projectRepo.findAll()).thenThrow(new RuntimeException("db down"));
        task.auditAllProjects();
        verify(learningEventRepo, never()).save(any());
    }
}
