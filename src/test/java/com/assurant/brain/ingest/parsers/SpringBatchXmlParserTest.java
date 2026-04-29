package com.assurant.brain.ingest.parsers;

import com.assurant.brain.graph.node.ProjectNode;
import com.assurant.brain.ingest.IngestionContext;
import com.assurant.brain.ingest.ParseResult;
import com.assurant.brain.ingest.RepoKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SpringBatchXmlParser")
class SpringBatchXmlParserTest {

    private final SpringBatchXmlParser parser = new SpringBatchXmlParser();

    @Test
    @DisplayName("extracts <batch:job> definitions into BatchJobNode")
    void extractsJobs(@TempDir Path projectRoot) throws IOException {
        Path springDir = projectRoot.resolve("src/main/resources/spring/batch");
        Files.createDirectories(springDir);
        Files.writeString(springDir.resolve("batchContext.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans"
                       xmlns:batch="http://www.springframework.org/schema/batch"
                       xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                  <batch:job id="dailyPromotionFeed">
                    <batch:step id="loadFeed"/>
                  </batch:job>
                  <batch:job id="cleanupExpiredOrders">
                    <batch:step id="purge"/>
                  </batch:job>
                </beans>
                """);

        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());

        assertThat(parser.supports(context)).isTrue();
        ParseResult result = parser.parse(context);

        assertThat(projectNode.getBatchJobs()).hasSize(2);
        assertThat(projectNode.getBatchJobs())
                .anyMatch(j -> j.getJobName().equals("dailyPromotionFeed")
                        && j.getFramework().equals("SPRING_BATCH"));
        assertThat(result.stats().get("batchJobs")).isEqualTo(2);
    }
}
