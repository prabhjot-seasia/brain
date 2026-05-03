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
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("IngestionService — parsing and error resolution")
class IngestionServiceParsingTest {

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
        var embed = new BrainProperties.Embed("ollama", "bge-m3", 1024, "http://localhost:11434");
        var props = new BrainProperties(null, embed, null, chunk, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        service = new IngestionService(projectRepository, chunkRepository,
                projectNodeRepository, crossRepoEdgeBuilder, new CallGraphExtractor(),
                vectorStore, props, new com.assurant.brain.ingest.RepoKindClassifier(),
                java.util.List.of(), java.util.List.of(), new com.assurant.brain.ingest.ManifestProcessor(),
                stubCommunitySummarizer(), org.mockito.Mockito.mock(com.assurant.brain.jobs.AsyncJobService.class), org.mockito.Mockito.mock(com.assurant.brain.service.GitCloneService.class), org.mockito.Mockito.mock(com.assurant.brain.service.ProjectDetector.class));
    }

    @Test
    @DisplayName("Java file with interface is parsed as INTERFACE chunk type")
    void parseJavaInterface(@TempDir Path tempDir) throws Exception {
        when(chunkRepository.countByProjectId("test")).thenReturn(0L);
        when(projectRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        Path javaDir = tempDir.resolve("src/main/java/com/example");
        Files.createDirectories(javaDir);
        Files.writeString(javaDir.resolve("MyRepo.java"),
                "package com.example;\npublic interface MyRepo { void save(); }",
                StandardCharsets.UTF_8);

        Project project = buildProject("test");
        service.ingestProject(project, tempDir);

        verify(vectorStore).add(argThat((List<Document> docs) ->
                docs.stream().anyMatch(d ->
                        "INTERFACE".equals(d.getMetadata().get("chunkType")))));
    }

    @Test
    @DisplayName("Java file with enum is parsed as ENUM chunk type")
    void parseJavaEnum(@TempDir Path tempDir) throws Exception {
        when(chunkRepository.countByProjectId("test")).thenReturn(0L);
        when(projectRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        Path javaDir = tempDir.resolve("src/main/java/com/example");
        Files.createDirectories(javaDir);
        Files.writeString(javaDir.resolve("Status.java"),
                "package com.example;\npublic enum Status { ACTIVE, INACTIVE }",
                StandardCharsets.UTF_8);

        Project project = buildProject("test");
        service.ingestProject(project, tempDir);

        verify(vectorStore).add(argThat((List<Document> docs) ->
                docs.stream().anyMatch(d ->
                        "ENUM".equals(d.getMetadata().get("chunkType")))));
    }

    @Test
    @DisplayName("pom.xml is parsed and libraries added to graph")
    void parsePomFile(@TempDir Path tempDir) throws Exception {
        when(chunkRepository.countByProjectId("test")).thenReturn(0L);
        when(projectRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>demo</artifactId>
                  <version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>org.springframework.boot</groupId>
                      <artifactId>spring-boot-starter-web</artifactId>
                      <version>3.4.4</version>
                    </dependency>
                  </dependencies>
                </project>
                """, StandardCharsets.UTF_8);

        Project project = buildProject("test");
        service.ingestProject(project, tempDir);

        verify(projectNodeRepository).save(argThat(node ->
                node.getLibraries().stream().anyMatch(lib ->
                        "org.springframework.boot:spring-boot-starter-web".equals(lib.getName()))));
    }

    @Test
    @DisplayName("Root pom.xml captures project self-identity (groupId + artifactId)")
    void parsePomFileCapturesSelfIdentity(@TempDir Path tempDir) throws Exception {
        when(chunkRepository.countByProjectId("test")).thenReturn(0L);
        when(projectRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme.platform</groupId>
                  <artifactId>platform-core</artifactId>
                  <version>1.0</version>
                </project>
                """, StandardCharsets.UTF_8);

        Project project = buildProject("test");
        service.ingestProject(project, tempDir);

        verify(projectNodeRepository).save(argThat(node ->
                "com.acme.platform".equals(node.getGroupId())
                        && "platform-core".equals(node.getArtifactId())));
    }

    @Test
    @DisplayName("Nested (non-root) pom.xml does NOT overwrite self-identity")
    void nestedPomDoesNotOverrideSelfIdentity(@TempDir Path tempDir) throws Exception {
        when(chunkRepository.countByProjectId("test")).thenReturn(0L);
        when(projectRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme.platform</groupId>
                  <artifactId>platform-core</artifactId>
                  <version>1.0</version>
                </project>
                """, StandardCharsets.UTF_8);

        Path moduleDir = tempDir.resolve("submodule");
        Files.createDirectories(moduleDir);
        Files.writeString(moduleDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.other</groupId>
                  <artifactId>child-module</artifactId>
                  <version>1.0</version>
                </project>
                """, StandardCharsets.UTF_8);

        Project project = buildProject("test");
        service.ingestProject(project, tempDir);

        verify(projectNodeRepository).save(argThat(node ->
                "com.acme.platform".equals(node.getGroupId())
                        && "platform-core".equals(node.getArtifactId())));
    }

    @Test
    @DisplayName("package.json is parsed: npm name + dependencies captured as libraries")
    void parsePackageJsonFile(@TempDir Path tempDir) throws Exception {
        when(chunkRepository.countByProjectId("test")).thenReturn(0L);
        when(projectRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        Files.writeString(tempDir.resolve("package.json"), """
                {
                  "name": "@acme/ui-kit",
                  "version": "1.0.0",
                  "dependencies": {
                    "react": "^18.0.0",
                    "axios": "^1.6.0"
                  },
                  "devDependencies": {
                    "vitest": "^1.0.0"
                  }
                }
                """, StandardCharsets.UTF_8);

        Project project = buildProject("test");
        service.ingestProject(project, tempDir);

        verify(projectNodeRepository).save(argThat(node ->
                "@acme/ui-kit".equals(node.getNpmName())
                        && node.getLibraries().stream()
                                .anyMatch(lib -> "npm:react".equals(lib.getName()))
                        && node.getLibraries().stream()
                                .anyMatch(lib -> "npm:vitest".equals(lib.getName()))));
    }

    @Test
    @DisplayName("package.json without dependencies block does not error")
    void parsePackageJsonWithoutDeps(@TempDir Path tempDir) throws Exception {
        when(chunkRepository.countByProjectId("test")).thenReturn(0L);
        when(projectRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        Files.writeString(tempDir.resolve("package.json"),
                "{\"name\": \"minimal-pkg\", \"version\": \"0.1.0\"}", StandardCharsets.UTF_8);

        Project project = buildProject("test");
        service.ingestProject(project, tempDir);

        verify(projectNodeRepository).save(argThat(node ->
                "minimal-pkg".equals(node.getNpmName())));
    }

    @Test
    @DisplayName("Cross-repo DEPENDS_ON rebuild runs after successful ingestion")
    void rebuildsCrossRepoEdgesAfterIngest(@TempDir Path tempDir) throws Exception {
        when(chunkRepository.countByProjectId("test")).thenReturn(0L);
        when(projectRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        Files.writeString(tempDir.resolve("README.md"), "# test", StandardCharsets.UTF_8);

        Project project = buildProject("test");
        service.ingestProject(project, tempDir);

        verify(crossRepoEdgeBuilder).rebuildForProject("test");
    }

    @Test
    @DisplayName("Ingestion completes even when cross-repo rebuild throws")
    void ingestionSurvivesCrossRepoRebuildFailure(@TempDir Path tempDir) throws Exception {
        when(chunkRepository.countByProjectId("test")).thenReturn(0L);
        when(projectRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        doThrow(new RuntimeException("neo4j offline"))
                .when(crossRepoEdgeBuilder).rebuildForProject(anyString());

        Files.writeString(tempDir.resolve("README.md"), "# test", StandardCharsets.UTF_8);

        Project project = buildProject("test");
        service.ingestProject(project, tempDir);

        verify(projectRepository, atLeastOnce()).save(argThat(p ->
                p.getIngestionStatus() == IngestionStatus.COMPLETE));
    }

    @Test
    @DisplayName("Doc files (.md, .txt) are parsed with DOC source type and trust weight 1.5")
    void parseDocFile(@TempDir Path tempDir) throws Exception {
        when(chunkRepository.countByProjectId("test")).thenReturn(0L);
        when(projectRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        Files.writeString(tempDir.resolve("README.md"),
                "# My Project\nThis is a test project with some documentation.",
                StandardCharsets.UTF_8);

        Project project = buildProject("test");
        service.ingestProject(project, tempDir);

        verify(vectorStore).add(argThat((List<Document> docs) ->
                docs.stream().anyMatch(d ->
                        "DOC".equals(d.getMetadata().get("sourceType"))
                                && Double.valueOf(1.5).equals(d.getMetadata().get("trustWeight")))));
    }

    @Test
    @DisplayName("ADR files are tagged with ADR chunk type")
    void parseAdrFile(@TempDir Path tempDir) throws Exception {
        when(chunkRepository.countByProjectId("test")).thenReturn(0L);
        when(projectRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        Path adrDir = tempDir.resolve("docs/adr");
        Files.createDirectories(adrDir);
        Files.writeString(adrDir.resolve("001-use-pgvector.md"),
                "# ADR-001: Use pgvector\nDecision: We will use pgvector for embeddings.",
                StandardCharsets.UTF_8);

        Project project = buildProject("test");
        service.ingestProject(project, tempDir);

        verify(vectorStore).add(argThat((List<Document> docs) ->
                docs.stream().anyMatch(d ->
                        "ADR".equals(d.getMetadata().get("chunkType")))));
    }

    @Test
    @DisplayName("Skippable paths are ignored (__MACOSX, .git, node_modules, build, target)")
    void skippablePaths(@TempDir Path tempDir) throws Exception {
        when(chunkRepository.countByProjectId("test")).thenReturn(0L);
        when(projectRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        Files.createDirectories(tempDir.resolve("__MACOSX"));
        Files.writeString(tempDir.resolve("__MACOSX/resource.txt"), "skip me", StandardCharsets.UTF_8);
        Files.createDirectories(tempDir.resolve(".git"));
        Files.writeString(tempDir.resolve(".git/HEAD"), "ref: refs/heads/main", StandardCharsets.UTF_8);
        Files.createDirectories(tempDir.resolve("node_modules/lodash"));
        Files.writeString(tempDir.resolve("node_modules/lodash/index.js"), "module.exports = {}", StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve(".DS_Store"), "binary", StandardCharsets.UTF_8);

        Project project = buildProject("test");
        service.ingestProject(project, tempDir);

        verify(vectorStore, never()).add(anyList());
    }

    @Test
    @DisplayName("resolveErrorMessage maps Connection refused for ollama provider")
    void resolveErrorConnectionRefused() {
        String msg = service.resolveErrorMessage(
                new RuntimeException("Connection refused"));
        assertThat(msg).contains("Cannot reach Ollama");
    }

    @Test
    @DisplayName("resolveErrorMessage maps model not found for ollama provider")
    void resolveErrorModelNotFound() {
        String msg = service.resolveErrorMessage(
                new RuntimeException("model bge-m3 not found"));
        assertThat(msg).contains("Embedding model not pulled");
    }

    @Test
    @DisplayName("resolveErrorMessage maps read timeout for ollama provider")
    void resolveErrorReadTimeout() {
        String msg = service.resolveErrorMessage(
                new RuntimeException("read timed out"));
        assertThat(msg).contains("timed out");
    }

    @Test
    @DisplayName("resolveErrorMessage maps context length exceeded for ollama provider")
    void resolveErrorContextLength() {
        String msg = service.resolveErrorMessage(
                new RuntimeException("input length exceeds the context length"));
        assertThat(msg).contains("context window");
    }

    @Test
    @DisplayName("resolveErrorMessage maps invalid OpenAI key")
    void resolveErrorInvalidOpenAiKey() {
        var embed = new BrainProperties.Embed("openai", "text-embedding-3-small", 1024, null);
        var props = new BrainProperties(null, embed, null, new BrainProperties.Chunk(18000, 200), null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        var svc = new IngestionService(projectRepository, chunkRepository,
                projectNodeRepository, crossRepoEdgeBuilder, new CallGraphExtractor(),
                vectorStore, props, new com.assurant.brain.ingest.RepoKindClassifier(),
                java.util.List.of(), java.util.List.of(), new com.assurant.brain.ingest.ManifestProcessor(),
                stubCommunitySummarizer(), org.mockito.Mockito.mock(com.assurant.brain.jobs.AsyncJobService.class), org.mockito.Mockito.mock(com.assurant.brain.service.GitCloneService.class), org.mockito.Mockito.mock(com.assurant.brain.service.ProjectDetector.class));

        String msg = svc.resolveErrorMessage(
                new RuntimeException("Incorrect API key provided"));
        assertThat(msg).contains("Invalid OpenAI API key");
    }

    @Test
    @DisplayName("resolveErrorMessage maps OpenAI quota exceeded")
    void resolveErrorOpenAiQuota() {
        String msg = service.resolveErrorMessage(
                new RuntimeException("insufficient_quota: exceeded your current quota"));
        assertThat(msg).contains("quota exceeded");
    }

    @Test
    @DisplayName("resolveErrorMessage maps OpenAI rate limit")
    void resolveErrorOpenAiRateLimit() {
        String msg = service.resolveErrorMessage(
                new RuntimeException("Rate limit reached"));
        assertThat(msg).contains("rate limit");
    }

    @Test
    @DisplayName("resolveErrorMessage maps Bedrock access denied")
    void resolveErrorBedrockAccessDenied() {
        String msg = service.resolveErrorMessage(
                new RuntimeException("AccessDeniedException: not authorized to perform: bedrock"));
        assertThat(msg).contains("Bedrock access denied");
    }

    @Test
    @DisplayName("resolveErrorMessage maps Bedrock throttling")
    void resolveErrorBedrockThrottling() {
        String msg = service.resolveErrorMessage(
                new RuntimeException("ThrottlingException"));
        assertThat(msg).contains("Bedrock throttled");
    }

    @Test
    @DisplayName("resolveErrorMessage maps Bedrock dimension mismatch")
    void resolveErrorBedrockDimensions() {
        String msg = service.resolveErrorMessage(
                new RuntimeException("ValidationException: dimensions mismatch"));
        assertThat(msg).contains("vector dimension");
    }

    @Test
    @DisplayName("resolveErrorMessage maps token limit exceeded")
    void resolveErrorTokenLimit() {
        String msg = service.resolveErrorMessage(
                new RuntimeException("Tokens in a single document exceeds the maximum"));
        assertThat(msg).contains("embedding token limit");
    }

    @Test
    @DisplayName("resolveErrorMessage maps OutOfMemoryError")
    void resolveErrorOom() {
        String msg = service.resolveErrorMessage(
                new RuntimeException("Java heap space", new OutOfMemoryError("Java heap space")));
        assertThat(msg).contains("out of memory");
    }

    @Test
    @DisplayName("resolveErrorMessage truncates long messages to 300 chars")
    void resolveErrorLongMessage() {
        String longMsg = "x".repeat(500);
        String msg = service.resolveErrorMessage(new RuntimeException(longMsg));
        assertThat(msg).hasSize(303);
        assertThat(msg).endsWith("...");
    }

    @Test
    @DisplayName("resolveErrorMessage with null message returns empty fallback")
    void resolveErrorNullMessage() {
        String msg = service.resolveErrorMessage(new RuntimeException((String) null));
        assertThat(msg).isEmpty();
    }

    @Test
    @DisplayName("handleIngestionFailure cleans up chunks, neo4j, and project row")
    void handleIngestionFailure(@TempDir Path tempDir) throws Exception {
        when(chunkRepository.countByProjectId("test")).thenReturn(0L);
        when(projectRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        doThrow(new RuntimeException("Embedding failed")).when(vectorStore).add(anyList());

        Path javaDir = tempDir.resolve("src/main/java/com/example");
        Files.createDirectories(javaDir);
        Files.writeString(javaDir.resolve("Foo.java"),
                "package com.example;\npublic class Foo { }",
                StandardCharsets.UTF_8);

        Project project = buildProject("test");
        service.ingestProject(project, tempDir);

        verify(chunkRepository, atLeast(1)).deleteByProjectId("test");
        verify(projectNodeRepository).deleteById("test");
        verify(projectRepository).deleteById("test");
    }

    @Test
    @DisplayName("contentHash is included in vector document metadata")
    void contentHashInMetadata(@TempDir Path tempDir) throws Exception {
        when(chunkRepository.countByProjectId("test")).thenReturn(0L);
        when(projectRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        Files.writeString(tempDir.resolve("README.md"), "Hello", StandardCharsets.UTF_8);

        Project project = buildProject("test");
        service.ingestProject(project, tempDir);

        verify(vectorStore).add(argThat((List<Document> docs) ->
                docs.stream().allMatch(d ->
                        d.getMetadata().containsKey("contentHash")
                                && ((String) d.getMetadata().get("contentHash")).length() == 64)));
    }

    @Test
    @DisplayName("Oversized content is split into multiple sub-chunks")
    void splitOversizedContent(@TempDir Path tempDir) throws Exception {
        when(chunkRepository.countByProjectId("test")).thenReturn(0L);
        when(projectRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        var smallChunk = new BrainProperties.Chunk(100, 20);
        var props = new BrainProperties(null, null, null, smallChunk, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        var svc = new IngestionService(projectRepository, chunkRepository,
                projectNodeRepository, crossRepoEdgeBuilder, new CallGraphExtractor(),
                vectorStore, props, new com.assurant.brain.ingest.RepoKindClassifier(),
                java.util.List.of(), java.util.List.of(), new com.assurant.brain.ingest.ManifestProcessor(),
                stubCommunitySummarizer(), org.mockito.Mockito.mock(com.assurant.brain.jobs.AsyncJobService.class), org.mockito.Mockito.mock(com.assurant.brain.service.GitCloneService.class), org.mockito.Mockito.mock(com.assurant.brain.service.ProjectDetector.class));

        String longContent = "# Title\n" + "This is a long line of documentation content.\n".repeat(20);
        Files.writeString(tempDir.resolve("LONG.md"), longContent, StandardCharsets.UTF_8);

        Project project = buildProject("test");
        svc.ingestProject(project, tempDir);

        verify(vectorStore).add(argThat((List<Document> docs) -> docs.size() > 1));
    }

    private Project buildProject(String id) {
        Project p = new Project();
        p.setId(id);
        p.setName("Test");
        p.setLanguage("java");
        p.setIngestionStatus(IngestionStatus.PENDING);
        return p;
    }

    private com.assurant.brain.retrieval.CommunitySummarizer stubCommunitySummarizer() {
        var stub = mock(com.assurant.brain.retrieval.CommunitySummarizer.class);
        when(stub.detectCommunities(any())).thenReturn(java.util.List.of());
        return stub;
    }
}
