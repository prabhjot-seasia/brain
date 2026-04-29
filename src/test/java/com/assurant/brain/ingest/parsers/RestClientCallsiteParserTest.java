package com.assurant.brain.ingest.parsers;

import com.assurant.brain.graph.node.ProjectNode;
import com.assurant.brain.ingest.JavaAstContext;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RestClientCallsiteParser")
class RestClientCallsiteParserTest {

    private final RestClientCallsiteParser visitor = new RestClientCallsiteParser();

    @Test
    @DisplayName("captures Feign @FeignClient annotation as ServiceNode")
    void capturesFeignClient() {
        CompilationUnit cu = parse("""
                package com.example;
                public interface CatalogClient {
                    @FeignClient(name = "catalog")
                    String fetch();
                }
                """);
        ProjectNode projectNode = new ProjectNode();
        visitor.visit(new JavaAstContext(cu, Path.of("CatalogClient.java"), "proj-1", projectNode));

        assertThat(projectNode.getCalledServices()).hasSize(1);
        assertThat(projectNode.getCalledServices().get(0).getName()).isEqualTo("catalog");
        assertThat(projectNode.getCalledServices().get(0).getSource()).isEqualTo("FEIGN_CLIENT");
    }

    @Test
    @DisplayName("captures RestTemplate.getForObject placeholder reference")
    void capturesRestTemplatePlaceholder() {
        CompilationUnit cu = parse("""
                package com.example;
                public class CatalogService {
                    void load(org.springframework.web.client.RestTemplate rest) {
                        rest.getForObject("${apps.catalog}/products", String.class);
                    }
                }
                """);
        ProjectNode projectNode = new ProjectNode();
        visitor.visit(new JavaAstContext(cu, Path.of("CatalogService.java"), "proj-1", projectNode));

        assertThat(projectNode.getCalledServices()).hasSize(1);
        assertThat(projectNode.getCalledServices().get(0).getName()).isEqualTo("apps");
        assertThat(projectNode.getCalledServices().get(0).getSource()).isEqualTo("REST_PLACEHOLDER");
    }

    @Test
    @DisplayName("captures literal HTTPS URL and infers service name from path version segment")
    void capturesLiteralUrl() {
        CompilationUnit cu = parse("""
                package com.example;
                public class PromoterService {
                    void load(org.springframework.web.client.RestTemplate rest) {
                        rest.getForObject("https://promoter.example.com/v1/promotions", String.class);
                    }
                }
                """);
        ProjectNode projectNode = new ProjectNode();
        visitor.visit(new JavaAstContext(cu, Path.of("PromoterService.java"), "proj-1", projectNode));

        assertThat(projectNode.getCalledServices()).hasSize(1);
        assertThat(projectNode.getCalledServices().get(0).getName()).isEqualTo("v1");
        assertThat(projectNode.getCalledServices().get(0).getSource()).isEqualTo("REST_LITERAL");
    }

    @Test
    @DisplayName("ignores classes with no REST client usage")
    void ignoresPlainClass() {
        CompilationUnit cu = parse("""
                package com.example;
                public class Helper {
                    public int add(int a, int b) { return a + b; }
                }
                """);
        ProjectNode projectNode = new ProjectNode();
        visitor.visit(new JavaAstContext(cu, Path.of("Helper.java"), "proj-1", projectNode));

        assertThat(projectNode.getCalledServices()).isEmpty();
    }

    private CompilationUnit parse(String source) {
        Optional<CompilationUnit> result = new JavaParser().parse(source).getResult();
        assertThat(result).isPresent();
        return result.get();
    }
}
