package com.assurant.brain.codegen;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Log4j2
@Component
public class AstValidator {

    public ValidationResult validate(Map<String, String> generatedFiles) {
        List<String> issues = new ArrayList<>();

        for (Map.Entry<String, String> entry : generatedFiles.entrySet()) {
            String filePath = entry.getKey();
            String content = entry.getValue();

            if (!filePath.endsWith(".java")) continue;

            List<String> fileIssues = validateJavaFile(filePath, content);
            issues.addAll(fileIssues);
        }

        boolean passed = issues.isEmpty();
        log.info("AST validation: {} files checked, {} issues found", generatedFiles.size(), issues.size());
        return new ValidationResult(passed, issues);
    }

    private List<String> validateJavaFile(String filePath, String content) {
        List<String> issues = new ArrayList<>();

        ParseResult<CompilationUnit> parseResult = new JavaParser().parse(content);

        if (!parseResult.isSuccessful()) {
            parseResult.getProblems().forEach(p ->
                    issues.add("[SYNTAX] " + filePath + ": " + p.getMessage()));
            return issues;
        }

        CompilationUnit cu = parseResult.getResult().orElse(null);
        if (cu == null) {
            issues.add("[SYNTAX] " + filePath + ": Failed to produce compilation unit");
            return issues;
        }

        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
            String className = clazz.getNameAsString();
            if (!Character.isUpperCase(className.charAt(0))) {
                issues.add("[NAMING] " + filePath + ": Class '" + className + "' must be PascalCase");
            }
        });

        cu.findAll(MethodDeclaration.class).forEach(method -> {
            String methodName = method.getNameAsString();
            if (Character.isUpperCase(methodName.charAt(0)) && !methodName.equals(methodName.toUpperCase())) {
                issues.add("[NAMING] " + filePath + ": Method '" + methodName + "' must be camelCase");
            }
        });

        cu.findAll(FieldDeclaration.class).forEach(field -> {
            if (field.isStatic() && field.isFinal()) {
                field.getVariables().forEach(v -> {
                    String name = v.getNameAsString();
                    if (!name.equals(name.toUpperCase()) && !name.equals("serialVersionUID") && !name.equals("log")) {
                        issues.add("[NAMING] " + filePath + ": Constant '" + name + "' must be UPPER_SNAKE_CASE");
                    }
                });
            }
        });

        cu.getAllComments().forEach(comment ->
                issues.add("[COMMENT] " + filePath + ": Contains " + comment.getClass().getSimpleName() +
                        " at line " + comment.getRange().map(r -> String.valueOf(r.begin.line)).orElse("unknown")));

        return issues;
    }

    public record ValidationResult(boolean passed, List<String> issues) {}
}
