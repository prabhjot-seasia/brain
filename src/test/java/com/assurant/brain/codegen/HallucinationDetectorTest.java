package com.assurant.brain.codegen;

import com.assurant.brain.dao.ChunkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("HallucinationDetector")
class HallucinationDetectorTest {

    private ChunkRepository chunkRepository;
    private HallucinationDetector detector;

    @BeforeEach
    void setup() {
        chunkRepository = mock(ChunkRepository.class);
        detector = new HallucinationDetector(chunkRepository);
    }

    @Test
    @DisplayName("passes when all project imports exist in chunk index")
    void noHallucinationsWhenImportsExist() {
        String code = """
                package com.example;

                import com.example.MyService;

                public class MyController {}
                """;
        when(chunkRepository.findChunkNamesByProjectId("proj-1"))
                .thenReturn(Set.of("MyService.java", "MyRepo.java"));

        List<String> issues = detector.detect(Map.of("src/MyController.java", code), "proj-1");
        assertThat(issues).isEmpty();
    }

    @Test
    @DisplayName("flags import referencing class not in project graph")
    void detectsPhantomImport() {
        String code = """
                package com.example;

                import com.example.PhantomService;

                public class MyController {}
                """;
        when(chunkRepository.findChunkNamesByProjectId("proj-1"))
                .thenReturn(Set.of("RealService.java"));

        List<String> issues = detector.detect(Map.of("src/MyController.java", code), "proj-1");
        assertThat(issues).anyMatch(i -> i.contains("[HALLUCINATION]") && i.contains("PhantomService"));
    }

    @Test
    @DisplayName("skips well-known library imports from java and spring packages")
    void skipsKnownLibraryPrefixes() {
        String code = """
                package com.example;

                import java.util.List;
                import org.springframework.stereotype.Service;
                import lombok.RequiredArgsConstructor;
                import jakarta.validation.Valid;

                public class MyService {}
                """;
        when(chunkRepository.findChunkNamesByProjectId("proj-1"))
                .thenReturn(Set.of());

        List<String> issues = detector.detect(Map.of("src/MyService.java", code), "proj-1");
        assertThat(issues).isEmpty();
    }

    @Test
    @DisplayName("skips non-Java files entirely")
    void skipsNonJavaFiles() {
        when(chunkRepository.findChunkNamesByProjectId("proj-1")).thenReturn(Set.of());

        List<String> issues = detector.detect(Map.of("README.md", "import com.example.Foo;"), "proj-1");
        assertThat(issues).isEmpty();
    }

    @Test
    @DisplayName("skips unparseable Java files without error")
    void skipsUnparseableFiles() {
        when(chunkRepository.findChunkNamesByProjectId("proj-1")).thenReturn(Set.of());

        List<String> issues = detector.detect(Map.of("src/Broken.java", "not valid java {{{"), "proj-1");
        assertThat(issues).isEmpty();
    }

    @Test
    @DisplayName("handles multiple files and accumulates issues")
    void accumulatesIssuesAcrossFiles() {
        String codeA = "package com.ex;\nimport com.ex.GhostA;\npublic class A {}";
        String codeB = "package com.ex;\nimport com.ex.GhostB;\npublic class B {}";
        when(chunkRepository.findChunkNamesByProjectId("proj-1")).thenReturn(Set.of("Real.java"));

        List<String> issues = detector.detect(Map.of("A.java", codeA, "B.java", codeB), "proj-1");
        assertThat(issues).hasSize(2);
    }

    @Test
    @DisplayName("returns no issues for empty file map")
    void emptyFileMap() {
        when(chunkRepository.findChunkNamesByProjectId("proj-1")).thenReturn(Set.of());
        List<String> issues = detector.detect(Map.of(), "proj-1");
        assertThat(issues).isEmpty();
    }
}
