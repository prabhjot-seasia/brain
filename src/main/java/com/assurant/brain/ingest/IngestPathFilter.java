package com.assurant.brain.ingest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

public final class IngestPathFilter {

    public static final long MAX_PARSE_BYTES = 10_000_000L;
    public static final long MAX_SCRIPT_BYTES = 5_000_000L;

    private IngestPathFilter() {}

    public static boolean isSkippable(Path path) {
        String fullPath = path.toString();
        String fileName = path.getFileName() != null ? path.getFileName().toString() : "";
        return fullPath.contains("__MACOSX")
                || fileName.startsWith("._")
                || fileName.equals(".DS_Store")
                || fullPath.contains("/node_modules/")
                || fullPath.contains("/.git/")
                || fullPath.contains("/build/")
                || fullPath.contains("/target/")
                || fullPath.contains("/.gradle/")
                || fullPath.contains("/.idea/")
                || fullPath.contains("/dist/")
                || fullPath.contains("/.venv/")
                || fullPath.contains("/__pycache__/")
                || fullPath.contains("/.pytest_cache/");
    }

    public static Stream<Path> safeWalk(Path projectRoot, Path start, int maxDepth) throws IOException {
        return walkUnderRoot(projectRoot, start, maxDepth)
                .filter(p -> !isSkippable(p));
    }

    public static Stream<Path> safeWalk(Path projectRoot, Path start) throws IOException {
        return safeWalk(projectRoot, start, Integer.MAX_VALUE);
    }

    public static Stream<Path> walkUnderRoot(Path projectRoot, Path start, int maxDepth) throws IOException {
        Path canonicalRoot = projectRoot.toRealPath();
        return Files.walk(start, maxDepth)
                .filter(p -> isWithinRoot(p, canonicalRoot));
    }

    public static Stream<Path> walkUnderRoot(Path projectRoot, Path start) throws IOException {
        return walkUnderRoot(projectRoot, start, Integer.MAX_VALUE);
    }

    public static boolean isParseSizeWithinLimit(Path file, long maxBytes) {
        try {
            return Files.size(file) <= maxBytes;
        } catch (IOException e) {
            return false;
        }
    }

    public static byte[] readAllBytesIfWithinLimit(Path file, long maxBytes) throws IOException {
        if (!isParseSizeWithinLimit(file, maxBytes)) {
            throw new IOException("File exceeds size limit (" + maxBytes + " bytes): " + file);
        }
        return Files.readAllBytes(file);
    }

    private static boolean isWithinRoot(Path candidate, Path canonicalRoot) {
        try {
            return candidate.toRealPath().startsWith(canonicalRoot);
        } catch (IOException e) {
            return false;
        }
    }
}
