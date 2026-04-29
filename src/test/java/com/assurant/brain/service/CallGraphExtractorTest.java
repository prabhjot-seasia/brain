package com.assurant.brain.service;

import com.assurant.brain.graph.node.ClassNode;
import com.assurant.brain.graph.node.ModuleNode;
import com.assurant.brain.graph.node.ProjectNode;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CallGraphExtractor")
class CallGraphExtractorTest {

    private final CallGraphExtractor extractor = new CallGraphExtractor();

    @Test
    @DisplayName("collectCallTargets captures static-style method calls with PascalCase scopes")
    void capturesStaticStyleCalls() {
        TypeDeclaration<?> type = parseFirstType("""
                package com.example;
                public class Foo {
                    public void run() {
                        Bar.execute();
                    }
                }
                """);
        Set<String> targets = extractor.collectCallTargets(type);
        assertThat(targets).contains("Bar");
    }

    @Test
    @DisplayName("collectCallTargets captures ObjectCreationExpr (new Foo())")
    void capturesConstructorCalls() {
        TypeDeclaration<?> type = parseFirstType("""
                package com.example;
                public class Foo {
                    public void run() {
                        new HelperService();
                    }
                }
                """);
        Set<String> targets = extractor.collectCallTargets(type);
        assertThat(targets).contains("HelperService");
    }

    @Test
    @DisplayName("collectCallTargets ignores lowercase scopes (variables, not types)")
    void ignoresLowercaseScopes() {
        TypeDeclaration<?> type = parseFirstType("""
                package com.example;
                public class Foo {
                    public void run(Helper helper) {
                        helper.doThing();
                    }
                }
                """);
        Set<String> targets = extractor.collectCallTargets(type);
        assertThat(targets).doesNotContain("helper");
    }

    @Test
    @DisplayName("collectCallTargets tolerates null TypeDeclaration")
    void toleratesNull() {
        assertThat(extractor.collectCallTargets(null)).isEmpty();
    }

    @Test
    @DisplayName("wireCalls resolves targets against project's simple-name index")
    void wireCallsResolvesTargets() {
        ProjectNode project = projectWith("com.example.Caller", "com.example.Target");
        ClassNode caller = findByName(project, "Caller");
        Map<ClassNode, Set<String>> pending = new HashMap<>();
        pending.put(caller, Set.of("Target", "Unknown"));

        extractor.wireCalls(project, pending);

        assertThat(caller.getCalls()).hasSize(1);
        assertThat(caller.getCalls().get(0).getName()).isEqualTo("Target");
    }

    @Test
    @DisplayName("wireCalls ignores self-references")
    void wireCallsIgnoresSelf() {
        ProjectNode project = projectWith("com.example.Caller");
        ClassNode caller = findByName(project, "Caller");
        Map<ClassNode, Set<String>> pending = new HashMap<>();
        pending.put(caller, Set.of("Caller"));

        extractor.wireCalls(project, pending);

        assertThat(caller.getCalls()).isEmpty();
    }

    @Test
    @DisplayName("wireCalls is a no-op on null project or empty map")
    void wireCallsNoOp() {
        extractor.wireCalls(null, Map.of());
        ProjectNode project = projectWith("com.example.Caller");
        extractor.wireCalls(project, null);
        extractor.wireCalls(project, Map.of());
    }

    private TypeDeclaration<?> parseFirstType(String source) {
        CompilationUnit cu = new JavaParser().parse(source).getResult().orElseThrow();
        return cu.getTypes().get(0);
    }

    private ProjectNode projectWith(String... qualifiedNames) {
        ProjectNode project = new ProjectNode();
        project.setId("proj-1");
        ModuleNode module = new ModuleNode();
        module.setName("com");
        for (String fqn : qualifiedNames) {
            ClassNode cls = new ClassNode();
            String simple = fqn.substring(fqn.lastIndexOf('.') + 1);
            cls.setName(simple);
            cls.setQualifiedName(fqn);
            cls.setProjectId("proj-1");
            module.getClasses().add(cls);
        }
        project.getModules().add(module);
        return project;
    }

    private ClassNode findByName(ProjectNode project, String name) {
        return project.getModules().get(0).getClasses().stream()
                .filter(c -> c.getName().equals(name))
                .findFirst().orElseThrow();
    }
}
