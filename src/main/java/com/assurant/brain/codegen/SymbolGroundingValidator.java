package com.assurant.brain.codegen;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Log4j2
@Component
@RequiredArgsConstructor
public class SymbolGroundingValidator {

    private static final Set<String> JDK_PREFIXES = Set.of(
            "java.", "javax.", "jakarta.");

    private static final Set<String> WELL_KNOWN_PREFIXES = Set.of(
            "org.springframework.", "org.junit.", "org.mockito.", "org.assertj.",
            "org.hamcrest.", "lombok.", "com.fasterxml.", "org.slf4j.",
            "org.apache.logging.", "org.apache.commons.", "io.cucumber.",
            "io.swagger.", "com.github.javaparser.", "org.openqa.selenium.",
            "org.springframework.security.", "org.springframework.data.",
            "org.springframework.web.", "org.springframework.boot.");

    public record GroundingResult(boolean ok, List<String> issues) {
        public static GroundingResult clean() {
            return new GroundingResult(true, List.of());
        }
    }

    private static final java.util.regex.Pattern CAMEL_CLASS_REF = java.util.regex.Pattern.compile(
            "\\b([A-Z][A-Za-z0-9]+(?:Service|Controller|Repository|Client|Manager|Facade|Resolver|Handler|Provider|Factory|Helper|Builder|Listener|Strategy))\\b");

    private static final Set<String> MARKDOWN_ALLOWLIST = Set.of(
            "RestController", "Controller", "Service", "Repository", "Component",
            "Configuration", "Bean", "Autowired", "RequiredArgsConstructor",
            "Transactional", "Async", "Slf4j", "Log4j2", "ChatModel", "VectorStore",
            "JpaRepository", "Neo4jRepository", "ApiClient", "JsonNode", "ObjectMapper",
            "MockMvc", "WebTestClient", "TestContainer", "ContextManager");

    public GroundingResult validateMarkdown(String md, SymbolDictionary dictionary) {
        if (md == null || md.isBlank()) return GroundingResult.clean();
        if (dictionary == null || dictionary == SymbolDictionary.EMPTY) return GroundingResult.clean();

        Set<String> simpleNamesInDict = new LinkedHashSet<>();
        for (String fqn : dictionary.classes()) {
            int dot = fqn.lastIndexOf('.');
            simpleNamesInDict.add(dot >= 0 ? fqn.substring(dot + 1) : fqn);
        }

        Set<String> referencedButUnknown = new LinkedHashSet<>();
        java.util.regex.Matcher m = CAMEL_CLASS_REF.matcher(stripFencedBlocks(md));
        while (m.find()) {
            String name = m.group(1);
            if (MARKDOWN_ALLOWLIST.contains(name)) continue;
            if (simpleNamesInDict.contains(name)) continue;
            referencedButUnknown.add(name);
        }
        if (referencedButUnknown.isEmpty()) return GroundingResult.clean();
        return new GroundingResult(false, referencedButUnknown.stream().toList());
    }

    private String stripFencedBlocks(String md) {
        return md.replaceAll("(?s)```.*?```", "");
    }

    public GroundingResult validate(Map<String, String> generatedFiles, SymbolDictionary dictionary) {
        if (dictionary == null || dictionary == SymbolDictionary.EMPTY) {
            log.debug("SymbolGroundingValidator: empty dictionary, skipping (project not yet ingested?)");
            return GroundingResult.clean();
        }
        List<String> issues = new ArrayList<>();
        for (Map.Entry<String, String> entry : generatedFiles.entrySet()) {
            String filePath = entry.getKey();
            String content = entry.getValue();
            if (filePath == null || !filePath.endsWith(".java")) continue;
            issues.addAll(validateOne(filePath, content, dictionary));
        }
        return new GroundingResult(issues.isEmpty(), issues);
    }

    private List<String> validateOne(String filePath, String content, SymbolDictionary dictionary) {
        List<String> issues = new ArrayList<>();
        try {
            ParseResult<CompilationUnit> parsed = new JavaParser().parse(content);
            Optional<CompilationUnit> maybeCu = parsed.getResult();
            if (maybeCu.isEmpty()) return issues;
            CompilationUnit cu = maybeCu.get();

            Set<String> declaredImports = new LinkedHashSet<>();
            for (ImportDeclaration imp : cu.getImports()) {
                String name = imp.getNameAsString();
                if (name == null || name.isBlank()) continue;
                declaredImports.add(name);
                if (isThirdPartyOrJdk(name)) continue;
                if (!resolvesToProject(name, dictionary)) {
                    issues.add(filePath + ": hallucinated import — '" + name
                            + "' not found in project graph or known libraries");
                }
            }

            cu.findAll(ClassOrInterfaceType.class).forEach(type -> {
                String name = type.getNameAsString();
                if (name == null || name.isBlank()) return;
                if (Character.isLowerCase(name.charAt(0))) return;
                if (isCommonLanguageType(name)) return;
                if (resolvedByImport(name, declaredImports, dictionary)) return;
                if (dictionary.classes().stream().anyMatch(fqn -> fqn.endsWith("." + name))) return;
                issues.add(filePath + ": potentially hallucinated type '" + name
                        + "' — no matching class in project graph and no resolving import");
            });

            cu.findAll(ObjectCreationExpr.class).forEach(expr -> {
                String name = expr.getType().getNameAsString();
                if (isCommonLanguageType(name)) return;
                if (resolvedByImport(name, declaredImports, dictionary)) return;
                if (dictionary.classes().stream().anyMatch(fqn -> fqn.endsWith("." + name))) return;
                issues.add(filePath + ": potentially hallucinated constructor 'new " + name
                        + "()' — no matching class in project graph");
            });

            cu.findAll(MethodCallExpr.class).forEach(call -> {
                Optional<NameExpr> scope = call.getScope().filter(s -> s.isNameExpr())
                        .map(s -> s.asNameExpr());
                if (scope.isEmpty()) return;
                String scopeName = scope.get().getNameAsString();
                if (scopeName == null || scopeName.isBlank()) return;
                if (Character.isLowerCase(scopeName.charAt(0))) return;
                if (isCommonLanguageType(scopeName)) return;
                if (resolvedByImport(scopeName, declaredImports, dictionary)) return;
                if (dictionary.classes().stream().anyMatch(fqn -> fqn.endsWith("." + scopeName))) return;
                issues.add(filePath + ": potentially hallucinated static call '" + scopeName + "."
                        + call.getNameAsString() + "()' — '" + scopeName
                        + "' not found in project graph");
            });

        } catch (RuntimeException e) {
            log.debug("SymbolGroundingValidator: parse failure for {} — skipping ({})",
                    filePath, e.getMessage());
        }
        return issues;
    }

    private boolean isThirdPartyOrJdk(String fqn) {
        for (String p : JDK_PREFIXES) if (fqn.startsWith(p)) return true;
        for (String p : WELL_KNOWN_PREFIXES) if (fqn.startsWith(p)) return true;
        return false;
    }

    private boolean resolvesToProject(String fqn, SymbolDictionary dictionary) {
        if (dictionary.hasClass(fqn)) return true;
        if (dictionary.libraries().stream().anyMatch(l -> coordinateContains(l, fqn))) return true;
        if (dictionary.symbols().stream().anyMatch(s -> s.startsWith(fqn))) return true;
        return false;
    }

    private boolean coordinateContains(String libraryCoord, String importedFqn) {
        if (libraryCoord == null) return false;
        String groupId = libraryCoord.contains(":")
                ? libraryCoord.substring(0, libraryCoord.indexOf(':'))
                : libraryCoord;
        if (groupId.isBlank()) return false;
        return importedFqn.equals(groupId)
                || importedFqn.startsWith(groupId + ".");
    }

    private boolean resolvedByImport(String simpleName, Set<String> imports, SymbolDictionary dictionary) {
        for (String imp : imports) {
            if (imp.endsWith("." + simpleName)) return true;
            if (imp.endsWith(".*")) {
                String pkg = imp.substring(0, imp.length() - 2);
                if (isThirdPartyOrJdk(pkg + ".x")) return true;
                if (dictionary.classes().stream().anyMatch(c -> c.startsWith(pkg + "."))) return true;
            }
        }
        return false;
    }

    private boolean isCommonLanguageType(String name) {
        return switch (name) {
            case "String", "Integer", "Long", "Boolean", "Double", "Float", "Byte", "Short",
                 "Character", "Object", "Number", "Math", "System", "Thread", "Runnable",
                 "Exception", "RuntimeException", "IllegalArgumentException", "IllegalStateException",
                 "NullPointerException", "UnsupportedOperationException", "Throwable", "Error",
                 "List", "ArrayList", "LinkedList", "Map", "HashMap", "LinkedHashMap", "TreeMap",
                 "Set", "HashSet", "LinkedHashSet", "TreeSet", "Optional", "Collection", "Iterable",
                 "Iterator", "Stream", "Collectors", "Arrays", "Collections", "Objects",
                 "Function", "Supplier", "Consumer", "BiFunction", "BiConsumer", "Predicate",
                 "UUID", "Instant", "Duration", "LocalDate", "LocalDateTime", "OffsetDateTime",
                 "ZoneId", "ZonedDateTime", "Path", "Paths", "Files", "BufferedReader",
                 "InputStream", "OutputStream", "Reader", "Writer", "IOException",
                 "void", "Void", "T", "E", "K", "V", "R" -> true;
            default -> false;
        };
    }
}
