package com.assurant.brain.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GitHubUrlParser {

    private static final Pattern GITHUB_URL_PATTERN = Pattern.compile(
            "(?:https?://[^/]+/|git@[^:]+:)([^/]+)/([^/.]+)(?:\\.git)?$"
    );

    private GitHubUrlParser() {}

    public static OwnerRepo parse(String repoUrl) {
        Matcher matcher = GITHUB_URL_PATTERN.matcher(repoUrl);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Invalid GitHub repository URL: " + repoUrl);
        }
        return new OwnerRepo(matcher.group(1), matcher.group(2));
    }

    public record OwnerRepo(String owner, String repo) {}
}
