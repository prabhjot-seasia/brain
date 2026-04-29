package com.assurant.brain.ingest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RepoKindClassifier")
class RepoKindClassifierTest {

    private final RepoKindClassifier classifier = new RepoKindClassifier();

    @Test
    @DisplayName("classifies a Java service repo with Dockerfile + Controller as APPLICATION")
    void classifiesJavaServiceAsApplication(@TempDir Path projectRoot) throws IOException {
        Files.writeString(projectRoot.resolve("Dockerfile"), "FROM amazoncorretto:21");
        Path javaDir = projectRoot.resolve("src/main/java/com/example");
        Files.createDirectories(javaDir);
        Files.writeString(javaDir.resolve("HealthController.java"), "class HealthController {}");

        assertThat(classifier.classify(projectRoot, "ce-api")).isEqualTo(RepoKind.APPLICATION);
    }

    @Test
    @DisplayName("classifies a CDK-only repo with no application code as CROSS_CUTTING_INFRA")
    void classifiesCdkInfraRepo(@TempDir Path projectRoot) throws IOException {
        Files.createDirectories(projectRoot.resolve(".cdk"));
        Files.writeString(projectRoot.resolve(".cdk/app.py"), "# cdk entry");

        assertThat(classifier.classify(projectRoot, "platform-cdk-constructs"))
                .isEqualTo(RepoKind.CROSS_CUTTING_INFRA);
    }

    @Test
    @DisplayName("classifies a config-files repo by name as REGISTRY")
    void classifiesRegistryRepo(@TempDir Path projectRoot) throws IOException {
        Files.writeString(projectRoot.resolve("project-brain.yml"), "spring:\n  datasource: ...");
        assertThat(classifier.classify(projectRoot, "gl-dls-ce-config-files"))
                .isEqualTo(RepoKind.REGISTRY);
    }

    @Test
    @DisplayName("classifies a publishConfig package.json without Dockerfile as CLIENT_LIBRARY")
    void classifiesClientLibraryByPublishConfig(@TempDir Path projectRoot) throws IOException {
        Files.writeString(projectRoot.resolve("package.json"),
                "{\"name\": \"foo-client\", \"publishConfig\": { \"registry\": \"https://registry.npmjs.org\" }}");
        assertThat(classifier.classify(projectRoot, "foo-client")).isEqualTo(RepoKind.CLIENT_LIBRARY);
    }

    @Test
    @DisplayName("classifies a script-only repo as SCRIPTS")
    void classifiesScriptsRepo(@TempDir Path projectRoot) throws IOException {
        Files.writeString(projectRoot.resolve("ftp-feed.sh"), "#!/bin/bash\necho hello");
        assertThat(classifier.classify(projectRoot, "ftp-scripts")).isEqualTo(RepoKind.SCRIPTS);
    }

    @Test
    @DisplayName("returns UNKNOWN when no signals match")
    void returnsUnknownForEmptyRepo(@TempDir Path projectRoot) {
        assertThat(classifier.classify(projectRoot, "mystery-repo")).isEqualTo(RepoKind.UNKNOWN);
    }
}
