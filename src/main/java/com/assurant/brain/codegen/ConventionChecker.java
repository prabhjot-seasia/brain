package com.assurant.brain.codegen;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Log4j2
@Component
public class ConventionChecker {

    public List<String> check(Map<String, String> generatedFiles) {
        List<String> violations = new ArrayList<>();

        for (Map.Entry<String, String> entry : generatedFiles.entrySet()) {
            String filePath = entry.getKey();
            String content = entry.getValue();

            if (!filePath.endsWith(".java")) continue;

            ParseResult<CompilationUnit> parseResult = new JavaParser().parse(content);
            if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) continue;

            CompilationUnit cu = parseResult.getResult().get();
            violations.addAll(checkClass(filePath, cu));
        }

        log.info("Convention check: {} violations found across {} files", violations.size(), generatedFiles.size());
        return violations;
    }

    private List<String> checkClass(String filePath, CompilationUnit cu) {
        List<String> violations = new ArrayList<>();

        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
            boolean hasFinalFields = clazz.getFields().stream()
                    .anyMatch(FieldDeclaration::isFinal);
            boolean hasRequiredArgsConstructor = clazz.getAnnotations().stream()
                    .anyMatch(a -> a.getNameAsString().equals("RequiredArgsConstructor"));
            boolean hasAllArgsConstructor = clazz.getAnnotations().stream()
                    .anyMatch(a -> a.getNameAsString().equals("AllArgsConstructor"));

            if (hasFinalFields && !hasRequiredArgsConstructor && !hasAllArgsConstructor
                    && !clazz.isInterface() && !clazz.isAbstract()) {
                violations.add("[CONVENTION] " + filePath + ": Class '" + clazz.getNameAsString() +
                        "' has final fields but no @RequiredArgsConstructor — use constructor injection");
            }

            clazz.getFields().forEach(field -> {
                boolean hasAutowired = field.getAnnotations().stream()
                        .anyMatch(a -> a.getNameAsString().equals("Autowired"));
                if (hasAutowired) {
                    violations.add("[CONVENTION] " + filePath + ": Field injection (@Autowired) detected on '" +
                            field.getVariables().get(0).getNameAsString() +
                            "' — use constructor injection with @RequiredArgsConstructor");
                }
            });

            if (clazz.getAnnotations().stream().anyMatch(a -> a.getNameAsString().equals("Slf4j"))) {
                violations.add("[CONVENTION] " + filePath + ": Uses @Slf4j — must use @Log4j2");
            }
            if (clazz.getAnnotations().stream().anyMatch(a -> a.getNameAsString().equals("Log"))) {
                violations.add("[CONVENTION] " + filePath + ": Uses @Log — must use @Log4j2");
            }

            clazz.getFields().stream()
                    .filter(f -> !f.isStatic() && !f.isFinal())
                    .filter(f -> f.getVariables().stream()
                            .anyMatch(v -> v.getNameAsString().equals("status")
                                    && v.getTypeAsString().equals("String")))
                    .forEach(f -> violations.add("[CONVENTION] " + filePath +
                            ": Field 'status' is raw String — use an @Enumerated enum type"));
        });

        return violations;
    }
}
