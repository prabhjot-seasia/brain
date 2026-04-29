package com.assurant.brain.codegen;

import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Log4j2
@Service
public class AiderDiffApplier {

    private static final Pattern BLOCK = Pattern.compile(
            "(?m)^([^\\n<>=]+?)\\s*\\n<<<<<<< SEARCH\\s*\\n(.*?)\\n?=======\\s*\\n(.*?)\\n?>>>>>>> REPLACE\\s*$",
            Pattern.DOTALL);

    public record Block(String path, String search, String replace) {}

    public record ApplyResult(Map<String, String> updatedFiles, List<String> errors) {
        public boolean ok() { return errors.isEmpty(); }
    }

    public List<Block> parse(String llmOutput) {
        if (llmOutput == null) return List.of();
        List<Block> blocks = new ArrayList<>();
        Matcher m = BLOCK.matcher(llmOutput);
        while (m.find()) {
            String path = m.group(1).trim();
            String search = m.group(2);
            String replace = m.group(3);
            blocks.add(new Block(path, search, replace));
        }
        return blocks;
    }

    public ApplyResult apply(Map<String, String> existingFiles, List<Block> blocks) {
        Map<String, String> mutable = new LinkedHashMap<>(existingFiles);
        List<String> errors = new ArrayList<>();

        for (Block block : blocks) {
            String search = block.search();
            String replace = block.replace();
            String path = block.path();

            if (path == null || path.isBlank() || path.startsWith("/") || path.startsWith("\\")
                    || path.contains("..") || path.contains("\0")) {
                errors.add("rejected unsafe path in SEARCH/REPLACE block: " + path);
                continue;
            }

            if (search.isBlank()) {
                if (mutable.containsKey(path)) {
                    errors.add("file already exists, cannot create from empty SEARCH: " + path);
                    continue;
                }
                mutable.put(path, replace);
                continue;
            }

            String existing = mutable.get(path);
            if (existing == null) {
                errors.add("no existing file at path: " + path);
                continue;
            }

            String matched = findVerbatim(existing, search);
            if (matched == null) {
                errors.add("SEARCH block does not match verbatim in " + path);
                continue;
            }

            mutable.put(path, existing.replace(matched, replace));
        }

        return new ApplyResult(mutable, errors);
    }

    private String findVerbatim(String existing, String search) {
        if (existing.contains(search)) return search;
        String normalizedSearch = search.replace("\r\n", "\n");
        if (existing.contains(normalizedSearch)) return normalizedSearch;
        String stripped = stripCommonIndent(search);
        if (existing.contains(stripped)) return stripped;
        return null;
    }

    private String stripCommonIndent(String text) {
        String[] lines = text.split("\n", -1);
        int min = Integer.MAX_VALUE;
        for (String line : lines) {
            if (line.isBlank()) continue;
            int leading = 0;
            while (leading < line.length() && line.charAt(leading) == ' ') leading++;
            if (leading < min) min = leading;
        }
        if (min == Integer.MAX_VALUE || min == 0) return text;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            sb.append(line.length() >= min ? line.substring(min) : line);
            if (i < lines.length - 1) sb.append('\n');
        }
        return sb.toString();
    }
}
