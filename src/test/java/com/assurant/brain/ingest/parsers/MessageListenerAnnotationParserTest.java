package com.assurant.brain.ingest.parsers;

import com.assurant.brain.graph.node.ProjectNode;
import com.assurant.brain.ingest.JavaAstContext;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MessageListenerAnnotationParser")
class MessageListenerAnnotationParserTest {

    private final MessageListenerAnnotationParser visitor = new MessageListenerAnnotationParser();

    @Test
    @DisplayName("captures @KafkaListener with topics array")
    void capturesKafkaListener() {
        CompilationUnit cu = parse("""
                package com.example;
                public class Listener {
                    @KafkaListener(topics = {"orders.created", "shipments.created"})
                    public void onMessage(String msg) {}
                }
                """);
        ProjectNode projectNode = new ProjectNode();
        JavaAstContext ctx = new JavaAstContext(cu, Path.of("Listener.java"), "proj-1", projectNode);
        visitor.visit(ctx);

        assertThat(projectNode.getConsumedQueues())
                .anyMatch(q -> q.getQueueType().equals("KAFKA") && q.getName().equals("orders.created"));
        assertThat(projectNode.getConsumedQueues())
                .anyMatch(q -> q.getQueueType().equals("KAFKA") && q.getName().equals("shipments.created"));
    }

    @Test
    @DisplayName("captures @SqsListener with single value")
    void capturesSqsListener() {
        CompilationUnit cu = parse("""
                package com.example;
                public class Listener {
                    @SqsListener("payment-events")
                    public void onMessage(String msg) {}
                }
                """);
        ProjectNode projectNode = new ProjectNode();
        JavaAstContext ctx = new JavaAstContext(cu, Path.of("Listener.java"), "proj-1", projectNode);
        visitor.visit(ctx);

        assertThat(projectNode.getConsumedQueues()).hasSize(1);
        assertThat(projectNode.getConsumedQueues().get(0).getQueueType()).isEqualTo("SQS");
        assertThat(projectNode.getConsumedQueues().get(0).getName()).isEqualTo("payment-events");
    }

    @Test
    @DisplayName("captures @RabbitListener with queues attribute")
    void capturesRabbitListener() {
        CompilationUnit cu = parse("""
                package com.example;
                public class Listener {
                    @RabbitListener(queues = "shipments.queue")
                    public void onMessage(String msg) {}
                }
                """);
        ProjectNode projectNode = new ProjectNode();
        JavaAstContext ctx = new JavaAstContext(cu, Path.of("Listener.java"), "proj-1", projectNode);
        visitor.visit(ctx);

        assertThat(projectNode.getConsumedQueues()).hasSize(1);
        assertThat(projectNode.getConsumedQueues().get(0).getQueueType()).isEqualTo("RABBITMQ");
        assertThat(projectNode.getConsumedQueues().get(0).getName()).isEqualTo("shipments.queue");
    }

    @Test
    @DisplayName("ignores classes with no listener annotation")
    void ignoresPlainClass() {
        CompilationUnit cu = parse("""
                package com.example;
                public class PlainBean {
                    public void doWork() {}
                }
                """);
        ProjectNode projectNode = new ProjectNode();
        visitor.visit(new JavaAstContext(cu, Path.of("PlainBean.java"), "proj-1", projectNode));

        assertThat(projectNode.getConsumedQueues()).isEmpty();
    }

    private CompilationUnit parse(String source) {
        Optional<CompilationUnit> result = new JavaParser().parse(source).getResult();
        assertThat(result).isPresent();
        return result.get();
    }
}
