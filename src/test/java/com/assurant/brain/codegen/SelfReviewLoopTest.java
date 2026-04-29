package com.assurant.brain.codegen;

import com.assurant.brain.dao.CodeReviewIterationRepository;
import com.assurant.brain.dao.PullRequestRecordRepository;
import com.assurant.brain.domain.CodeReviewIteration;
import com.assurant.brain.domain.PullRequestRecord;
import com.assurant.brain.enums.ReviewVerdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("SelfReviewLoop")
class SelfReviewLoopTest {

    private CodeGeneratorService codeGenerator;
    private CodeReviewService codeReviewer;
    private PullRequestRecordRepository prRepo;
    private CodeReviewIterationRepository iterationRepo;
    private SelfReviewLoop loop;

    @BeforeEach
    void setup() {
        codeGenerator = mock(CodeGeneratorService.class);
        codeReviewer = mock(CodeReviewService.class);
        prRepo = mock(PullRequestRecordRepository.class);
        iterationRepo = mock(CodeReviewIterationRepository.class);
        loop = new SelfReviewLoop(codeGenerator, codeReviewer, prRepo, iterationRepo);
    }

    private PullRequestRecord prRecord() {
        PullRequestRecord r = new PullRequestRecord();
        r.setId(UUID.randomUUID());
        return r;
    }

    @Test
    @DisplayName("first-iteration pass returns the original files unchanged and records PASS")
    void passesFirstIteration() {
        Map<String, String> files = Map.of("src/Foo.java", "class Foo {}");
        when(codeReviewer.review(anyMap(), anyString()))
                .thenReturn(new CodeReviewService.ReviewResult(true, List.of(), "OK"));
        PullRequestRecord r = prRecord();

        Map<String, String> result = loop.run(r, files, "{}", 3);

        assertThat(result).isEqualTo(files);
        verify(codeReviewer, times(1)).review(anyMap(), anyString());
        verify(codeGenerator, never()).fixCode(anyMap(), any(), anyString());
        ArgumentCaptor<CodeReviewIteration> captor = ArgumentCaptor.forClass(CodeReviewIteration.class);
        verify(iterationRepo).save(captor.capture());
        assertThat(captor.getValue().getVerdict()).isEqualTo(ReviewVerdict.PASS);
        assertThat(captor.getValue().getIterationNumber()).isEqualTo(1);
    }

    @Test
    @DisplayName("fail then pass on second iteration runs fixCode once and returns fixed files")
    void failThenPass() {
        Map<String, String> initial = Map.of("src/Foo.java", "class Foo {}");
        Map<String, String> fixed = Map.of("src/Foo.java", "class Foo { /* fixed */ }");
        when(codeReviewer.review(anyMap(), anyString()))
                .thenReturn(new CodeReviewService.ReviewResult(false, List.of("issue"), "fail"))
                .thenReturn(new CodeReviewService.ReviewResult(true, List.of(), "ok"));
        when(codeGenerator.fixCode(anyMap(), any(), anyString())).thenReturn(fixed);
        PullRequestRecord r = prRecord();

        Map<String, String> result = loop.run(r, initial, "{}", 3);

        assertThat(result).isEqualTo(fixed);
        verify(codeGenerator, times(1)).fixCode(anyMap(), any(), anyString());
        verify(codeReviewer, times(2)).review(anyMap(), anyString());
    }

    @Test
    @DisplayName("max iterations exhausted returns last attempt without throwing")
    void maxIterationsExhausted() {
        when(codeReviewer.review(anyMap(), anyString()))
                .thenReturn(new CodeReviewService.ReviewResult(false, List.of("issue"), "fail"));
        when(codeGenerator.fixCode(anyMap(), any(), anyString())).thenReturn(Map.of("Foo", "v2"));
        PullRequestRecord r = prRecord();

        Map<String, String> result = loop.run(r, Map.of("Foo", "v1"), "{}", 2);

        assertThat(result).isNotNull();
        verify(codeReviewer, times(2)).review(anyMap(), anyString());
        verify(codeGenerator, times(1)).fixCode(anyMap(), any(), anyString());
    }

    @Test
    @DisplayName("PR record selfReviewIterations updates each pass")
    void prRecordTracksIterations() {
        when(codeReviewer.review(anyMap(), anyString()))
                .thenReturn(new CodeReviewService.ReviewResult(false, List.of("x"), "fail"))
                .thenReturn(new CodeReviewService.ReviewResult(true, List.of(), "ok"));
        when(codeGenerator.fixCode(anyMap(), any(), anyString())).thenReturn(Map.of("Foo", "v2"));
        PullRequestRecord r = prRecord();

        loop.run(r, Map.of("Foo", "v1"), "{}", 5);

        verify(prRepo, times(2)).save(r);
        assertThat(r.getSelfReviewIterations()).isEqualTo(2);
    }
}
