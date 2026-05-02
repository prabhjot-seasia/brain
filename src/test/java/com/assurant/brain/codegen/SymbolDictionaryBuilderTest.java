package com.assurant.brain.codegen;

import com.assurant.brain.graph.node.ClassNode;
import com.assurant.brain.graph.node.LibraryNode;
import com.assurant.brain.graph.node.ModuleNode;
import com.assurant.brain.graph.node.ProjectNode;
import com.assurant.brain.graph.node.SymbolReferenceNode;
import com.assurant.brain.graph.repository.ProjectNodeRepository;
import com.assurant.brain.graph.repository.SymbolReferenceNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("SymbolDictionaryBuilder")
class SymbolDictionaryBuilderTest {

    private ProjectNodeRepository projectRepo;
    private SymbolReferenceNodeRepository symbolRepo;
    private SymbolDictionaryBuilder builder;

    @BeforeEach
    void setup() {
        projectRepo = mock(ProjectNodeRepository.class);
        symbolRepo = mock(SymbolReferenceNodeRepository.class);
        builder = new SymbolDictionaryBuilder(projectRepo, symbolRepo);
    }

    @Test
    @DisplayName("blank projectId → EMPTY")
    void blankProject() {
        assertThat(builder.build("")).isSameAs(SymbolDictionary.EMPTY);
        assertThat(builder.build(null)).isSameAs(SymbolDictionary.EMPTY);
    }

    @Test
    @DisplayName("project missing in graph → EMPTY (no exception leaks)")
    void projectMissing() {
        when(projectRepo.findById("ce-imei")).thenReturn(Optional.empty());
        SymbolDictionary dict = builder.build("ce-imei");
        assertThat(dict.classes()).isEmpty();
        assertThat(dict.libraries()).isEmpty();
        assertThat(dict.symbols()).isEmpty();
    }

    @Test
    @DisplayName("collects classes from modules + libraries + symbols")
    void buildsFullDictionary() {
        ProjectNode project = new ProjectNode();
        ModuleNode module = new ModuleNode();
        ClassNode foo = new ClassNode();
        foo.setQualifiedName("com.assurant.brain.Foo");
        ClassNode bar = new ClassNode();
        bar.setQualifiedName("com.assurant.brain.Bar");
        module.setClasses(List.of(foo, bar));
        project.setModules(List.of(module));

        LibraryNode lib = new LibraryNode();
        lib.setGroupId("org.springframework");
        lib.setArtifactId("spring-context");
        lib.setVersion("6.1.5");
        project.setLibraries(List.of(lib));

        when(projectRepo.findById("p")).thenReturn(Optional.of(project));

        SymbolReferenceNode sym = new SymbolReferenceNode();
        sym.setSymbolFqn("com.assurant.brain.Foo#run()");
        when(symbolRepo.findByProjectId("p")).thenReturn(List.of(sym));

        SymbolDictionary dict = builder.build("p");

        assertThat(dict.classes()).contains("com.assurant.brain.Foo", "com.assurant.brain.Bar");
        assertThat(dict.libraries()).containsExactly("org.springframework:spring-context:6.1.5");
        assertThat(dict.symbols()).containsExactly("com.assurant.brain.Foo#run()");
        assertThat(dict.hasClass("com.assurant.brain.Foo")).isTrue();
        assertThat(dict.hasClass("com.acme.MadeUp")).isFalse();
    }

    @Test
    @DisplayName("library without groupId/artifactId falls back to name+version")
    void libraryFallbackToName() {
        ProjectNode project = new ProjectNode();
        LibraryNode lib = new LibraryNode();
        lib.setName("react");
        lib.setVersion("18.2.0");
        project.setLibraries(List.of(lib));
        when(projectRepo.findById("p")).thenReturn(Optional.of(project));
        when(symbolRepo.findByProjectId("p")).thenReturn(List.of());

        SymbolDictionary dict = builder.build("p");
        assertThat(dict.libraries()).containsExactly("react:18.2.0");
    }

    @Test
    @DisplayName("renderForPrompt emits a structured block with caps + truncation marker")
    void renderForPromptShape() {
        SymbolDictionary dict = new SymbolDictionary(
                java.util.Set.of("com.x.A", "com.x.B"),
                java.util.Set.of("g:a:1.0"),
                java.util.Set.of("com.x.A#m()"));
        String rendered = dict.renderForPrompt(10_000);
        assertThat(rendered)
                .contains("PROJECT_SYMBOLS")
                .contains("classes:")
                .contains("- com.x.A")
                .contains("libraries:")
                .contains("- g:a:1.0")
                .contains("symbols")
                .contains("- com.x.A#m()");
    }

    @Test
    @DisplayName("empty dictionary renders a guarded fallback message")
    void emptyDictionaryFallback() {
        String rendered = SymbolDictionary.EMPTY.renderForPrompt(1000);
        assertThat(rendered).contains("no project symbol dictionary available");
    }

    @Test
    @DisplayName("Neo4j failure during build → returns EMPTY (does not bubble)")
    void neo4jFailureSafe() {
        when(projectRepo.findById("p")).thenThrow(new RuntimeException("neo4j down"));
        SymbolDictionary dict = builder.build("p");
        assertThat(dict).isSameAs(SymbolDictionary.EMPTY);
    }
}
