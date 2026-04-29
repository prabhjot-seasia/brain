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

@DisplayName("CdkPythonParser")
class CdkPythonParserTest {

    private final CdkPythonParser parser = new CdkPythonParser();

    @Test
    @DisplayName("detects Python class *Stack and aws_* construct usage")
    void detectsCdkStacks(@TempDir Path projectRoot) throws IOException {
        Path infraDir = projectRoot.resolve(".infra/stacks");
        Files.createDirectories(infraDir);
        Files.writeString(infraDir.resolve("api_gateway_stack.py"), """
                from aws_cdk import aws_apigateway, aws_lambda

                class ApiGatewayStack(Stack):
                    def __init__(self, scope, id_, **kwargs):
                        super().__init__(scope, id_, **kwargs)
                        api = aws_apigateway.RestApi(self, "api")
                        fn = aws_lambda.Function(self, "fn")
                """);

        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());

        assertThat(parser.supports(context)).isTrue();
        ParseResult result = parser.parse(context);

        assertThat(projectNode.getInfraStacks()).hasSize(1);
        assertThat(projectNode.getInfraStacks().get(0).getName()).isEqualTo("ApiGatewayStack");
        assertThat(projectNode.getInfraStacks().get(0).getInfraType()).isEqualTo("CDK_PYTHON");
        assertThat(projectNode.getInfraStacks().get(0).getConstructTypes())
                .anyMatch(c -> c.contains("apigateway"));
        assertThat(result.stats().get("stacks")).isEqualTo(1);
    }
}
