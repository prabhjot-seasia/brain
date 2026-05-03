package com.assurant.brain.avenger.checks;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Log4j2
@Component
public class ItControllerEntryCheck {

    public record CheckResult(boolean violation, List<String> findings) {
        public static CheckResult clean() { return new CheckResult(false, List.of()); }
    }

    public CheckResult check(String content) {
        if (content == null || content.isBlank()) return CheckResult.clean();

        List<String> findings = new ArrayList<>();
        try {
            Optional<CompilationUnit> maybe = new JavaParser().parse(content).getResult();
            if (maybe.isEmpty()) return CheckResult.clean();
            CompilationUnit cu = maybe.get();

            cu.findAll(FieldDeclaration.class).forEach(field -> {
                boolean hasMockBean = field.getAnnotations().stream()
                        .anyMatch(a -> "MockBean".equals(a.getNameAsString())
                                || "org.springframework.boot.test.mock.mockito.MockBean".equals(a.getNameAsString()));
                if (!hasMockBean) return;
                field.getVariables().forEach(v -> {
                    String typeName = v.getTypeAsString();
                    if (typeName != null && typeName.endsWith("Controller")) {
                        findings.add("@MockBean " + typeName
                                + " — IT must hit the real @RestController via MockMvc; mocking the controller defeats the purpose of an IT.");
                    }
                });
            });

            cu.findAll(ObjectCreationExpr.class).forEach(expr -> {
                String typeName = expr.getType().getNameAsString();
                if (typeName != null && typeName.endsWith("Controller")) {
                    findings.add("instantiates `new " + typeName
                            + "(...)` — IT must enter through MockMvc against the Spring-managed bean.");
                }
            });
        } catch (RuntimeException e) {
            log.debug("ItControllerEntryCheck parse failure: {}", e.getMessage());
            return CheckResult.clean();
        }

        return findings.isEmpty() ? CheckResult.clean() : new CheckResult(true, findings);
    }
}
