package com.assurant.brain.learning;

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
public class StructuralConventionAnalyzer {

    public AnalysisResult analyze(Map<String, String> generatedFiles, Map<String, String> mergedFiles) {
        List<String> followed = new ArrayList<>();
        List<String> violated = new ArrayList<>();

        for (Map.Entry<String, String> entry : generatedFiles.entrySet()) {
            String filePath = entry.getKey();
            String generatedContent = entry.getValue();
            String mergedContent = mergedFiles.get(filePath);

            if (mergedContent == null || !filePath.endsWith(".java")) continue;
            if (generatedContent.equals(mergedContent)) {
                followed.add("all-conventions:" + filePath);
                continue;
            }

            compareStructure(filePath, generatedContent, mergedContent, followed, violated);
        }

        log.info("Structural convention analysis: {} followed, {} violated", followed.size(), violated.size());
        return new AnalysisResult(followed, violated);
    }

    private void compareStructure(String filePath, String generated, String merged,
                                   List<String> followed, List<String> violated) {
        ParseResult<CompilationUnit> genResult = new JavaParser().parse(generated);
        ParseResult<CompilationUnit> mergedResult = new JavaParser().parse(merged);

        if (!genResult.isSuccessful() || !mergedResult.isSuccessful()) return;

        CompilationUnit genCu = genResult.getResult().orElse(null);
        CompilationUnit mergedCu = mergedResult.getResult().orElse(null);
        if (genCu == null || mergedCu == null) return;

        checkConstructorInjection(filePath, genCu, mergedCu, followed, violated);
        checkLoggingAnnotation(filePath, genCu, mergedCu, followed, violated);
        checkFieldInjection(filePath, genCu, mergedCu, followed, violated);
    }

    private void checkConstructorInjection(String filePath, CompilationUnit genCu, CompilationUnit mergedCu,
                                            List<String> followed, List<String> violated) {
        boolean genHasRAC = hasAnnotation(genCu, "RequiredArgsConstructor");
        boolean mergedHasRAC = hasAnnotation(mergedCu, "RequiredArgsConstructor");

        if (genHasRAC && mergedHasRAC) {
            followed.add("constructor-injection");
        } else if (!genHasRAC && mergedHasRAC) {
            violated.add("constructor-injection");
        }
    }

    private void checkLoggingAnnotation(String filePath, CompilationUnit genCu, CompilationUnit mergedCu,
                                         List<String> followed, List<String> violated) {
        boolean genHasLog4j2 = hasAnnotation(genCu, "Log4j2");
        boolean mergedHasLog4j2 = hasAnnotation(mergedCu, "Log4j2");
        boolean genHasSlf4j = hasAnnotation(genCu, "Slf4j");

        if (genHasLog4j2 && mergedHasLog4j2) {
            followed.add("log4j2-usage");
        } else if (genHasSlf4j && mergedHasLog4j2) {
            violated.add("log4j2-usage");
        }
    }

    private void checkFieldInjection(String filePath, CompilationUnit genCu, CompilationUnit mergedCu,
                                      List<String> followed, List<String> violated) {
        long genAutowired = countFieldAnnotation(genCu, "Autowired");
        long mergedAutowired = countFieldAnnotation(mergedCu, "Autowired");

        if (genAutowired == 0 && mergedAutowired == 0) {
            followed.add("no-field-injection");
        } else if (genAutowired > 0 && mergedAutowired == 0) {
            violated.add("no-field-injection");
        }
    }

    private boolean hasAnnotation(CompilationUnit cu, String annotationName) {
        return cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                .anyMatch(c -> c.getAnnotations().stream()
                        .anyMatch(a -> a.getNameAsString().equals(annotationName)));
    }

    private long countFieldAnnotation(CompilationUnit cu, String annotationName) {
        return cu.findAll(FieldDeclaration.class).stream()
                .filter(f -> f.getAnnotations().stream()
                        .anyMatch(a -> a.getNameAsString().equals(annotationName)))
                .count();
    }

    public record AnalysisResult(List<String> followed, List<String> violated) {}
}
