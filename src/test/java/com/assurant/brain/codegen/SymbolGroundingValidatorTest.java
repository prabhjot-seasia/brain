package com.assurant.brain.codegen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SymbolGroundingValidator")
class SymbolGroundingValidatorTest {

    private final SymbolGroundingValidator validator = new SymbolGroundingValidator();

    @Test
    @DisplayName("clean code with imports resolved by project graph → ok=true")
    void cleanCodeOk() {
        SymbolDictionary dict = new SymbolDictionary(
                Set.of("com.assurant.brain.Foo", "com.assurant.brain.Bar"),
                Set.of(), Set.of());
        String code = """
                package com.assurant.brain;
                import com.assurant.brain.Foo;
                public class UsesFoo {
                    private final Foo foo = new Foo();
                }
                """;
        var result = validator.validate(Map.of("src/main/java/com/assurant/brain/UsesFoo.java", code), dict);
        assertThat(result.ok()).isTrue();
        assertThat(result.issues()).isEmpty();
    }

    @Test
    @DisplayName("hallucinated import → flagged with offending FQN")
    void hallucinatedImportFlagged() {
        SymbolDictionary dict = new SymbolDictionary(
                Set.of("com.assurant.brain.Foo"), Set.of(), Set.of());
        String code = """
                package com.assurant.brain;
                import com.acme.MadeUpService;
                public class UsesMadeUp {
                    private final MadeUpService svc = new MadeUpService();
                }
                """;
        var result = validator.validate(Map.of("X.java", code), dict);
        assertThat(result.ok()).isFalse();
        assertThat(result.issues())
                .anyMatch(i -> i.contains("com.acme.MadeUpService"))
                .anyMatch(i -> i.toLowerCase().contains("hallucinated"));
    }

    @Test
    @DisplayName("hallucinated `new SomeService()` with no import → flagged")
    void hallucinatedConstructorFlagged() {
        SymbolDictionary dict = new SymbolDictionary(
                Set.of("com.assurant.brain.Foo"), Set.of(), Set.of());
        String code = """
                package com.assurant.brain;
                public class Caller {
                    public void run() {
                        new GhostService().doWork();
                    }
                }
                """;
        var result = validator.validate(Map.of("Caller.java", code), dict);
        assertThat(result.ok()).isFalse();
        assertThat(result.issues()).anyMatch(i -> i.contains("GhostService"));
    }

    @Test
    @DisplayName("standard library types (String, List, Optional) are not flagged")
    void stdLibNotFlagged() {
        SymbolDictionary dict = new SymbolDictionary(Set.of(), Set.of(), Set.of());
        String code = """
                package com.assurant.brain;
                import java.util.List;
                import java.util.Optional;
                public class StdUser {
                    public Optional<String> first(List<String> items) {
                        return items.isEmpty() ? Optional.empty() : Optional.of(items.get(0));
                    }
                }
                """;
        var result = validator.validate(Map.of("StdUser.java", code), dict);
        assertThat(result.ok()).isTrue();
    }

    @Test
    @DisplayName("Spring Framework imports are not flagged (third-party allowlist)")
    void springImportsAllowed() {
        SymbolDictionary dict = new SymbolDictionary(Set.of(), Set.of(), Set.of());
        String code = """
                package com.assurant.brain;
                import org.springframework.stereotype.Service;
                @Service
                public class MyService {}
                """;
        var result = validator.validate(Map.of("MyService.java", code), dict);
        assertThat(result.ok()).isTrue();
    }

    @Test
    @DisplayName("EMPTY dictionary → validator skips (project not yet ingested, fail-open)")
    void emptyDictionarySkips() {
        String code = """
                package com.assurant.brain;
                import com.totally.MadeUp;
                public class X { MadeUp m; }
                """;
        var result = validator.validate(Map.of("X.java", code), SymbolDictionary.EMPTY);
        assertThat(result.ok()).isTrue();
    }

    @Test
    @DisplayName("non-Java files in the bundle are skipped")
    void nonJavaFilesSkipped() {
        SymbolDictionary dict = new SymbolDictionary(Set.of("com.x.A"), Set.of(), Set.of());
        var result = validator.validate(Map.of(
                "README.md", "GhostService is great",
                "config.yml", "ghost: 'true'"), dict);
        assertThat(result.ok()).isTrue();
    }

    @Test
    @DisplayName("library coordinate matches imported package root")
    void libraryCoordinateMatches() {
        SymbolDictionary dict = new SymbolDictionary(
                Set.of(),
                Set.of("io.netty:netty-all:4.1.100"),
                Set.of());
        String code = """
                package com.assurant.brain;
                import io.netty.buffer.ByteBuf;
                public class N { ByteBuf b; }
                """;
        var result = validator.validate(Map.of("N.java", code), dict);
        assertThat(result.ok()).isTrue();
    }

    @Test
    @DisplayName("unparseable file does not crash validator (returns clean for that file)")
    void unparseableFileSafe() {
        SymbolDictionary dict = new SymbolDictionary(Set.of("com.x.A"), Set.of(), Set.of());
        var result = validator.validate(Map.of("Bad.java", "this is not Java {{{ }"), dict);
        assertThat(result.ok()).isTrue();
    }
}
