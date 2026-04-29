package com.assurant.brain.service;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.dao.ChunkRepository;
import com.assurant.brain.dao.ProjectRepository;
import com.assurant.brain.domain.Project;
import com.assurant.brain.enums.IngestionStatus;
import com.assurant.brain.graph.CrossRepoEdgeBuilder;
import com.assurant.brain.graph.repository.ProjectNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("IngestionService — incremental ingestion")
class IngestionServiceIncrementalTest {

    private IngestionService service;
    private ProjectRepository projectRepository;
    private ChunkRepository chunkRepository;
    private ProjectNodeRepository projectNodeRepository;
    private CrossRepoEdgeBuilder crossRepoEdgeBuilder;
    private VectorStore vectorStore;

    @BeforeEach
    void setup() {
        projectRepository = mock(ProjectRepository.class);
        chunkRepository = mock(ChunkRepository.class);
        projectNodeRepository = mock(ProjectNodeRepository.class);
        crossRepoEdgeBuilder = mock(CrossRepoEdgeBuilder.class);
        vectorStore = mock(VectorStore.class);

        var chunk = new BrainProperties.Chunk(18000, 200);
        var props = new BrainProperties(null, null, null, chunk, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        var communitySummarizer = mock(com.assurant.brain.retrieval.CommunitySummarizer.class);
        when(communitySummarizer.detectCommunities(any())).thenReturn(java.util.List.of());
        service = new IngestionService(projectRepository, chunkRepository,
                projectNodeRepository, crossRepoEdgeBuilder, new CallGraphExtractor(),
                vectorStore, props, new com.assurant.brain.ingest.RepoKindClassifier(),
                java.util.List.of(), java.util.List.of(), new com.assurant.brain.ingest.ManifestProcessor(),
                communitySummarizer, org.mockito.Mockito.mock(com.assurant.brain.jobs.AsyncJobService.class), org.mockito.Mockito.mock(com.assurant.brain.service.GitCloneService.class), org.mockito.Mockito.mock(com.assurant.brain.service.ProjectDetector.class));
    }

    @Test
    @DisplayName("Full ingestion when no existing chunks")
    void fullIngestionOnNewProject() throws Exception {
        when(chunkRepository.countByProjectId("test")).thenReturn(0L);
        when(projectRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        Path tempDir = createTestProject();
        Project project = buildProject("test");

        service.ingestProject(project, tempDir);

        verify(vectorStore, atLeastOnce()).add(anyList());
        verify(chunkRepository, never()).deleteByProjectIdAndFilePaths(anyString(), anyList());
    }

    @Test
    @DisplayName("Incremental ingestion skips unchanged files")
    void incrementalSkipsUnchanged() throws Exception {
        String content = "package com.example;\npublic class Hello { public void hi() {} }";
        String hash = IngestionService.sha256(content);

        when(chunkRepository.countByProjectId("test")).thenReturn(1L);
        List<Object[]> existingHashes = new ArrayList<>();
        existingHashes.add(new Object[]{"src/main/java/com/example/Hello.java", hash});
        when(chunkRepository.findContentHashesByProjectId("test")).thenReturn(existingHashes);
        when(projectRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        Path tempDir = Files.createTempDirectory("brain-inc-test-");
        Path javaDir = tempDir.resolve("src/main/java/com/example");
        Files.createDirectories(javaDir);
        Files.writeString(javaDir.resolve("Hello.java"), content, StandardCharsets.UTF_8);

        Project project = buildProject("test");
        service.ingestProject(project, tempDir);

        verify(vectorStore, never()).add(anyList());
    }

    @Test
    @DisplayName("Incremental ingestion embeds modified files")
    void incrementalEmbedsModified() throws Exception {
        when(chunkRepository.countByProjectId("test")).thenReturn(1L);
        List<Object[]> existingHashes = new ArrayList<>();
        existingHashes.add(new Object[]{"src/main/java/com/example/Hello.java", "old_hash_value"});
        when(chunkRepository.findContentHashesByProjectId("test")).thenReturn(existingHashes);
        when(projectRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        Path tempDir = Files.createTempDirectory("brain-inc-test-");
        Path javaDir = tempDir.resolve("src/main/java/com/example");
        Files.createDirectories(javaDir);
        Files.writeString(javaDir.resolve("Hello.java"),
                "package com.example;\npublic class Hello { public void updated() {} }",
                StandardCharsets.UTF_8);

        Project project = buildProject("test");
        service.ingestProject(project, tempDir);

        verify(vectorStore, atLeastOnce()).add(anyList());
    }

    @Test
    @DisplayName("Incremental ingestion deletes chunks for removed files")
    void incrementalDeletesRemoved() throws Exception {
        when(chunkRepository.countByProjectId("test")).thenReturn(1L);
        List<Object[]> existingHashes = new ArrayList<>();
        existingHashes.add(new Object[]{"src/main/java/com/example/Deleted.java", "some_hash"});
        when(chunkRepository.findContentHashesByProjectId("test")).thenReturn(existingHashes);
        when(projectRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        Path tempDir = Files.createTempDirectory("brain-inc-test-");
        Files.createDirectories(tempDir.resolve("src"));

        Project project = buildProject("test");
        service.ingestProject(project, tempDir);

        verify(chunkRepository).deleteByProjectIdAndFilePaths(eq("test"), anyList());
    }

    @Test
    @DisplayName("sha256 produces consistent 64-char hex digest")
    void sha256Consistent() {
        String hash = IngestionService.sha256("test content");
        assertThat(hash).hasSize(64);
        assertThat(IngestionService.sha256("test content")).isEqualTo(hash);
    }

    @Test
    @DisplayName("sha256 produces different hashes for different content")
    void sha256Different() {
        assertThat(IngestionService.sha256("aaa"))
                .isNotEqualTo(IngestionService.sha256("bbb"));
    }

    private Path createTestProject() throws Exception {
        Path tempDir = Files.createTempDirectory("brain-test-");
        Path javaDir = tempDir.resolve("src/main/java/com/example");
        Files.createDirectories(javaDir);
        Files.writeString(javaDir.resolve("Hello.java"),
                "package com.example;\npublic class Hello { public String greet() { return \"hi\"; } }",
                StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("README.md"), "# Test Project", StandardCharsets.UTF_8);
        return tempDir;
    }

    private Project buildProject(String id) {
        Project p = new Project();
        p.setId(id);
        p.setName("Test");
        p.setLanguage("java");
        p.setIngestionStatus(IngestionStatus.PENDING);
        return p;
    }
}
