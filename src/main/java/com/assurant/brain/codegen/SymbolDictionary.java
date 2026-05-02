package com.assurant.brain.codegen;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

public record SymbolDictionary(
        Set<String> classes,
        Set<String> libraries,
        Set<String> symbols) {

    public static final SymbolDictionary EMPTY = new SymbolDictionary(
            Set.of(), Set.of(), Set.of());

    public boolean hasClass(String fqn) {
        return fqn != null && classes.contains(fqn);
    }

    public boolean hasSymbol(String fqn) {
        return fqn != null && symbols.contains(fqn);
    }

    public boolean knowsAnything(String prefix) {
        if (prefix == null || prefix.isBlank()) return true;
        if (classes.stream().anyMatch(c -> c.startsWith(prefix))) return true;
        if (symbols.stream().anyMatch(s -> s.startsWith(prefix))) return true;
        return libraries.stream().anyMatch(l -> l.startsWith(prefix));
    }

    public String renderForPrompt(int maxChars) {
        if (classes.isEmpty() && libraries.isEmpty() && symbols.isEmpty()) {
            return "(no project symbol dictionary available — fall back to general best practice but flag uncertainty)";
        }
        Set<String> classCap = capped(classes, 80);
        Set<String> libCap = capped(libraries, 30);
        Set<String> symCap = capped(symbols, 60);
        StringBuilder sb = new StringBuilder();
        sb.append("PROJECT_SYMBOLS — only call methods / reference classes from this list. ")
          .append("If a needed symbol is missing, surface that as an open question rather than inventing.\n\n");
        sb.append("classes:\n");
        classCap.forEach(c -> sb.append("  - ").append(c).append('\n'));
        if (classes.size() > classCap.size()) {
            sb.append("  ... (+").append(classes.size() - classCap.size()).append(" more)\n");
        }
        sb.append("libraries:\n");
        libCap.forEach(l -> sb.append("  - ").append(l).append('\n'));
        if (libraries.size() > libCap.size()) {
            sb.append("  ... (+").append(libraries.size() - libCap.size()).append(" more)\n");
        }
        if (!symCap.isEmpty()) {
            sb.append("symbols (top ").append(symCap.size()).append("):\n");
            symCap.forEach(s -> sb.append("  - ").append(s).append('\n'));
            if (symbols.size() > symCap.size()) {
                sb.append("  ... (+").append(symbols.size() - symCap.size()).append(" more)\n");
            }
        }
        if (sb.length() > maxChars && maxChars > 0) {
            return sb.substring(0, maxChars) + "\n... (truncated)";
        }
        return sb.toString();
    }

    private Set<String> capped(Set<String> source, int limit) {
        if (source.size() <= limit) return source;
        return source.stream().limit(limit).collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
