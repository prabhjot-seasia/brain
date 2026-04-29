package com.assurant.brain.codegen;

import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Log4j2
@Component
public class DiffApplier {

    private static final Pattern HUNK_HEADER = Pattern.compile(
            "^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@");

    public String apply(String originalContent, String unifiedDiff) {
        List<String> originalLines = new ArrayList<>(List.of(originalContent.split("\n", -1)));

        List<Hunk> hunks = parseHunks(unifiedDiff);
        if (hunks.isEmpty()) {
            log.warn("No hunks found in diff — returning original content");
            return originalContent;
        }

        int offset = 0;
        for (Hunk hunk : hunks) {
            int startLine = hunk.originalStart() - 1 + offset;

            for (DiffLine line : hunk.lines()) {
                switch (line.type()) {
                    case CONTEXT -> startLine++;
                    case REMOVE -> {
                        if (startLine < originalLines.size()) {
                            originalLines.remove(startLine);
                            offset--;
                        }
                    }
                    case ADD -> {
                        originalLines.add(startLine, line.content());
                        startLine++;
                        offset++;
                    }
                }
            }
        }

        return String.join("\n", originalLines);
    }

    private List<Hunk> parseHunks(String diff) {
        List<Hunk> hunks = new ArrayList<>();
        String[] lines = diff.split("\n");

        int i = 0;
        while (i < lines.length) {
            Matcher matcher = HUNK_HEADER.matcher(lines[i]);
            if (matcher.find()) {
                int originalStart = Integer.parseInt(matcher.group(1));
                List<DiffLine> diffLines = new ArrayList<>();
                i++;

                while (i < lines.length && !lines[i].startsWith("@@") &&
                        !lines[i].startsWith("---") && !lines[i].startsWith("+++")) {
                    String line = lines[i];
                    if (line.startsWith("-")) {
                        diffLines.add(new DiffLine(LineType.REMOVE, line.substring(1)));
                    } else if (line.startsWith("+")) {
                        diffLines.add(new DiffLine(LineType.ADD, line.substring(1)));
                    } else if (line.startsWith(" ") || line.isEmpty()) {
                        diffLines.add(new DiffLine(LineType.CONTEXT, line.isEmpty() ? "" : line.substring(1)));
                    }
                    i++;
                }

                hunks.add(new Hunk(originalStart, diffLines));
            } else {
                i++;
            }
        }

        return hunks;
    }

    private record Hunk(int originalStart, List<DiffLine> lines) {}

    private record DiffLine(LineType type, String content) {}

    private enum LineType { CONTEXT, ADD, REMOVE }
}
