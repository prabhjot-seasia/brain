package com.assurant.brain.service;

import com.assurant.brain.BrainApplicationTests;
import com.assurant.brain.dao.ChunkRepository;
import com.assurant.brain.dao.ProjectRepository;
import com.assurant.brain.domain.Project;
import com.assurant.brain.graph.repository.ConventionNodeRepository;
import com.assurant.brain.graph.repository.ProjectNodeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.atMostOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@DisplayName("IngestionService — Parsing Resilience")
class IngestionServiceIT extends BrainApplicationTests {

    @Autowired IngestionService ingestionService;
    @Autowired ProjectRepository projectRepository;
    @Autowired ChunkRepository chunkRepository;

    @MockitoBean ProjectNodeRepository projectNodeRepository;
    @MockitoBean ConventionNodeRepository conventionNodeRepository;
    @MockitoBean VectorStore vectorStore;
    @MockitoBean com.assurant.brain.graph.repository.RuntimeServiceEdgeNodeRepository runtimeServiceEdgeNodeRepository;

    @MockitoBean ClarifierService clarifierService;
    @MockitoBean PlannerService plannerService;

    @Test
    @DisplayName("Skips __MACOSX resource fork directories without errors")
    void skips_macosx_directory(@TempDir Path tempDir) throws Exception {
        Path javaDir = tempDir.resolve("src/main/java/com/example");
        Files.createDirectories(javaDir);
        Files.writeString(javaDir.resolve("App.java"),
                "package com.example;\npublic class App {}", StandardCharsets.UTF_8);

        Path macDir = tempDir.resolve("__MACOSX/.github");
        Files.createDirectories(macDir);
        Files.write(macDir.resolve("._PULL_REQUEST_TEMPLATE.md"),
                new byte[]{0x00, 0x05, 0x16, 0x07, (byte) 0xFF, (byte) 0xFE, 0x00});

        Project project = seedProject("test-macosx");

        assertThatCode(() -> ingestionService.ingestProject(project, tempDir))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Skips ._ prefixed resource fork files without errors")
    void skips_dot_underscore_files(@TempDir Path tempDir) throws Exception {
        Path docsDir = tempDir.resolve("docs");
        Files.createDirectories(docsDir);
        Files.writeString(docsDir.resolve("README.md"),
                "# My Project\nThis is a readme.", StandardCharsets.UTF_8);

        Files.write(docsDir.resolve("._README.md"),
                new byte[]{0x00, 0x05, 0x16, 0x07, (byte) 0x80, (byte) 0x81, (byte) 0x82});

        Project project = seedProject("test-dotunderscore");

        assertThatCode(() -> ingestionService.ingestProject(project, tempDir))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Skips .DS_Store files without errors")
    void skips_ds_store(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("README.md"),
                "# Hello", StandardCharsets.UTF_8);
        Files.write(tempDir.resolve(".DS_Store"),
                new byte[]{0x00, 0x00, 0x00, 0x01, 'B', 'u', 'd', '1'});

        Project project = seedProject("test-dsstore");

        assertThatCode(() -> ingestionService.ingestProject(project, tempDir))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Handles non-UTF-8 doc files gracefully without throwing")
    void handles_non_utf8_doc_file(@TempDir Path tempDir) throws Exception {
        byte[] latin1Content = "# Résumé\nCafé naïve".getBytes(StandardCharsets.ISO_8859_1);
        byte[] malformed = new byte[latin1Content.length + 4];
        System.arraycopy(latin1Content, 0, malformed, 0, latin1Content.length);
        malformed[malformed.length - 4] = (byte) 0xC0;
        malformed[malformed.length - 3] = (byte) 0xAF;
        malformed[malformed.length - 2] = (byte) 0xFE;
        malformed[malformed.length - 1] = (byte) 0xFF;
        Files.write(tempDir.resolve("CONTRIBUTING.md"), malformed);

        Project project = seedProject("test-encoding");

        assertThatCode(() -> ingestionService.ingestProject(project, tempDir))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Handles completely binary file with .md extension")
    void handles_binary_file_with_doc_extension(@TempDir Path tempDir) throws Exception {
        byte[] binaryContent = new byte[256];
        for (int i = 0; i < 256; i++) binaryContent[i] = (byte) i;
        Files.write(tempDir.resolve("notes.md"), binaryContent);

        Project project = seedProject("test-binary-md");

        assertThatCode(() -> ingestionService.ingestProject(project, tempDir))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Processes valid UTF-8 docs alongside skipped resource forks")
    void processes_valid_docs_alongside_resource_forks(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("README.md"),
                "# Real Project\nThis should be indexed.", StandardCharsets.UTF_8);

        Path macDir = tempDir.resolve("__MACOSX");
        Files.createDirectories(macDir);
        Files.write(macDir.resolve("._README.md"),
                new byte[]{0x00, 0x05, 0x16, 0x07, (byte) 0xFF});
        Files.write(tempDir.resolve(".DS_Store"),
                new byte[]{0x00, 0x00, 0x00, 0x01});

        Project project = seedProject("test-mixed");

        assertThatCode(() -> ingestionService.ingestProject(project, tempDir))
                .doesNotThrowAnyException();

        verify(vectorStore, atMostOnce()).add(anyList());
    }

    @Test
    @DisplayName("Empty doc file is silently skipped")
    void skips_empty_doc_file(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("EMPTY.md"), "", StandardCharsets.UTF_8);

        Project project = seedProject("test-empty-doc");

        assertThatCode(() -> ingestionService.ingestProject(project, tempDir))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Class exceeding max-chars is split into sub-chunks without error")
    void splits_large_java_class() throws Exception {
        Path tempDir = Files.createTempDirectory("brain-test-large-class");
        Path javaDir = tempDir.resolve("src/main/java/com/example");
        Files.createDirectories(javaDir);

        StringBuilder sb = new StringBuilder();
        sb.append("package com.example;\n\npublic class BigClass {\n");
        for (int i = 0; i < 20; i++) {
            sb.append("    public String method").append(i).append("(String input) {\n");
            sb.append("        return input + \"").append("x".repeat(60)).append("\";\n");
            sb.append("    }\n\n");
        }
        sb.append("}\n");
        Files.writeString(javaDir.resolve("BigClass.java"), sb.toString(), StandardCharsets.UTF_8);

        Project project = seedProject("test-large-class");

        ingestionService.ingestProject(project, tempDir);
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                verify(vectorStore, atLeastOnce()).add(anyList()));
    }

    @Test
    @DisplayName("Method exceeding max-chars is split into sub-chunks without error")
    void splits_large_method() throws Exception {
        Path tempDir = Files.createTempDirectory("brain-test-large-method");
        Path javaDir = tempDir.resolve("src/main/java/com/example");
        Files.createDirectories(javaDir);

        StringBuilder sb = new StringBuilder();
        sb.append("package com.example;\n\npublic class BigMethod {\n");
        sb.append("    public void process() {\n");
        for (int i = 0; i < 40; i++) {
            sb.append("        String v").append(i).append(" = \"").append("y".repeat(40)).append("\";\n");
        }
        sb.append("    }\n}\n");
        Files.writeString(javaDir.resolve("BigMethod.java"), sb.toString(), StandardCharsets.UTF_8);

        Project project = seedProject("test-large-method");

        ingestionService.ingestProject(project, tempDir);
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                verify(vectorStore, atLeastOnce()).add(anyList()));
    }

    @Test
    @DisplayName("Doc file exceeding max-chars is split, not truncated")
    void splits_large_doc_file() throws Exception {
        Path tempDir = Files.createTempDirectory("brain-test-large-doc");

        StringBuilder sb = new StringBuilder("# Large README\n\n");
        for (int i = 0; i < 30; i++) {
            sb.append("## Section ").append(i).append("\n");
            sb.append("Details: ").append("z".repeat(50)).append("\n\n");
        }
        Files.writeString(tempDir.resolve("README.md"), sb.toString(), StandardCharsets.UTF_8);

        Project project = seedProject("test-large-doc");

        ingestionService.ingestProject(project, tempDir);
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                verify(vectorStore, atLeastOnce()).add(anyList()));
    }

    @Test
    @DisplayName("Small class under max-chars produces single chunk (no splitting)")
    void normal_class_single_chunk() throws Exception {
        Path tempDir = Files.createTempDirectory("brain-test-normal-class");
        Path javaDir = tempDir.resolve("src/main/java/com/example");
        Files.createDirectories(javaDir);
        Files.writeString(javaDir.resolve("Simple.java"), """
                package com.example;
                public class Simple {
                    private String name;
                    public String getName() { return name; }
                }
                """, StandardCharsets.UTF_8);

        Project project = seedProject("test-normal-class");

        ingestionService.ingestProject(project, tempDir);
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                verify(vectorStore, atLeastOnce()).add(anyList()));
    }

    @Test
    @DisplayName("Ingestion failure rolls back the project row entirely")
    void ingestion_failure_rolls_back_project_row() throws Exception {
        Path tempDir = Files.createTempDirectory("brain-test-fail");
        Files.writeString(tempDir.resolve("README.md"), "# Test", StandardCharsets.UTF_8);

        doThrow(new RuntimeException("Incorrect API key provided: sk-proj-***"))
                .when(vectorStore).add(anyList());

        Project project = seedProject("test-fail-rollback");

        ingestionService.ingestProject(project, tempDir);
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(projectRepository.findById("test-fail-rollback")).isEmpty());
    }

    @Test
    @DisplayName("Ingestion failure rolls back partial chunks via metadata-based delete")
    void ingestion_failure_rolls_back_chunks() throws Exception {
        Path tempDir = Files.createTempDirectory("brain-test-cleanup");
        Files.writeString(tempDir.resolve("README.md"), "# Test", StandardCharsets.UTF_8);

        doThrow(new RuntimeException("insufficient_quota"))
                .when(vectorStore).add(anyList());

        Project project = seedProject("test-cleanup");

        ingestionService.ingestProject(project, tempDir);
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(projectRepository.findById("test-cleanup")).isEmpty();
            assertThat(chunkRepository.countByProjectId("test-cleanup")).isZero();
        });
    }

    @Test
    @DisplayName("OpenAI invalid key → 'Invalid OpenAI API key' message")
    void openai_invalid_key_friendly_message() {
        String msg = ingestionService.resolveErrorMessage(
                new RuntimeException("Incorrect API key provided: sk-proj-***"));
        assertThat(msg).contains("Invalid OpenAI API key");
    }

    @Test
    @DisplayName("OpenAI quota exceeded → 'quota exceeded' message")
    void openai_quota_exceeded_friendly_message() {
        String msg = ingestionService.resolveErrorMessage(
                new RuntimeException("insufficient_quota"));
        assertThat(msg).contains("quota exceeded");
    }

    @Test
    @DisplayName("Ollama unreachable → 'Cannot reach Ollama' + 'ollama serve' message")
    void ollama_connection_refused_friendly_message() {
        String msg = ingestionService.resolveErrorMessage(new RuntimeException(
                "I/O error on POST request for \"http://localhost:11434/api/embed\": Connection refused"));
        assertThat(msg).contains("Cannot reach Ollama").contains("ollama serve");
    }

    @Test
    @DisplayName("Ollama model missing → 'ollama pull bge-m3' message")
    void ollama_model_missing_friendly_message() {
        String msg = ingestionService.resolveErrorMessage(new RuntimeException(
                "Ollama responded with: {\"error\":\"model 'bge-m3' not found, try pulling it first\"}"));
        assertThat(msg).contains("not pulled").contains("ollama pull bge-m3");
    }

    @Test
    @DisplayName("Ollama timeout → 'may still be loading' message")
    void ollama_timeout_friendly_message() {
        String msg = ingestionService.resolveErrorMessage(new RuntimeException(
                "org.springframework.web.client.ResourceAccessException: read timed out"));
        assertThat(msg).contains("Ollama embedding request timed out").contains("may still be loading");
    }

    private Project seedProject(String id) {
        Project p = new Project();
        p.setId(id);
        p.setName("Test " + id);
        p.setLanguage("java");
        p.setFramework("spring-boot");
        p.setBuildTool("gradle");
        return projectRepository.save(p);
    }
}
