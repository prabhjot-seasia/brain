package com.assurant.brain.codegen;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Log4j2
@Service
public class StyleFingerprintBuilder {

    private static final int MAX_FILES_SAMPLED = 60;

    public StyleFingerprint build(Collection<String> javaFileContents) {
        if (javaFileContents == null || javaFileContents.isEmpty()) return StyleFingerprint.EMPTY;

        List<Integer> methodLines = new ArrayList<>();
        int methodsWithEarlyReturn = 0;
        int totalMethods = 0;
        int methodsUsingVar = 0;
        int methodsUsingStream = 0;
        int methodsUsingLambda = 0;
        int totalImports = 0;
        int totalLines = 0;
        int commentLines = 0;
        int filesParsed = 0;

        JavaParser parser = new JavaParser();
        for (String content : javaFileContents) {
            if (content == null || content.isBlank()) continue;
            if (filesParsed >= MAX_FILES_SAMPLED) break;
            try {
                ParseResult<CompilationUnit> parsed = parser.parse(content);
                Optional<CompilationUnit> maybe = parsed.getResult();
                if (maybe.isEmpty()) continue;
                CompilationUnit cu = maybe.get();

                totalImports += cu.getImports().size();
                String[] lines = content.split("\n", -1);
                totalLines += lines.length;
                for (String line : lines) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                        commentLines++;
                    }
                }

                for (MethodDeclaration method : cu.findAll(MethodDeclaration.class)) {
                    totalMethods++;
                    int len = method.getRange()
                            .map(r -> r.end.line - r.begin.line + 1)
                            .orElse(0);
                    if (len > 0) methodLines.add(len);

                    long returns = method.findAll(ReturnStmt.class).size();
                    if (returns >= 2) methodsWithEarlyReturn++;

                    boolean usesVar = method.findAll(VariableDeclarationExpr.class).stream()
                            .anyMatch(v -> v.getElementType().toString().equals("var"));
                    if (usesVar) methodsUsingVar++;

                    boolean usesStream = method.findAll(MethodCallExpr.class).stream()
                            .anyMatch(c -> "stream".equals(c.getNameAsString()));
                    if (usesStream) methodsUsingStream++;

                    boolean usesLambda = !method.findAll(LambdaExpr.class).isEmpty();
                    if (usesLambda) methodsUsingLambda++;
                }

                filesParsed++;
            } catch (RuntimeException e) {
                log.debug("StyleFingerprint: skipped a file ({})", e.getMessage());
            }
        }

        if (totalMethods == 0) return StyleFingerprint.EMPTY;

        double avg = mean(methodLines);
        double stdev = stdev(methodLines, avg);
        int p90 = percentile(methodLines, 0.9);
        double returnEarlyRatio = (double) methodsWithEarlyReturn / totalMethods;
        double varRatio = (double) methodsUsingVar / totalMethods;
        double streamRatio = (double) methodsUsingStream / totalMethods;
        double lambdaRatio = (double) methodsUsingLambda / totalMethods;
        int avgImports = filesParsed > 0 ? totalImports / filesParsed : 0;
        double commentDensity = totalLines > 0
                ? 100.0 * commentLines / totalLines
                : 0d;

        return new StyleFingerprint(totalMethods, avg, stdev, p90,
                returnEarlyRatio, varRatio, streamRatio, lambdaRatio,
                avgImports, commentDensity);
    }

    public StyleFingerprint compare(StyleFingerprint candidate, StyleFingerprint baseline) {
        return candidate;
    }

    public double sigmaOff(int candidateMethodLength, StyleFingerprint baseline) {
        if (baseline == null || baseline.isEmpty() || baseline.methodLinesStdev() <= 0) return 0;
        return Math.abs(candidateMethodLength - baseline.avgMethodLines()) / baseline.methodLinesStdev();
    }

    private double mean(List<Integer> values) {
        if (values.isEmpty()) return 0d;
        double total = 0;
        for (int v : values) total += v;
        return total / values.size();
    }

    private double stdev(List<Integer> values, double mean) {
        if (values.size() < 2) return 0d;
        double sumSq = 0;
        for (int v : values) sumSq += Math.pow(v - mean, 2);
        return Math.sqrt(sumSq / (values.size() - 1));
    }

    private int percentile(List<Integer> values, double pct) {
        if (values.isEmpty()) return 0;
        List<Integer> sorted = new ArrayList<>(values);
        java.util.Collections.sort(sorted);
        int idx = (int) Math.ceil(pct * sorted.size()) - 1;
        idx = Math.min(Math.max(idx, 0), sorted.size() - 1);
        return sorted.get(idx);
    }
}
