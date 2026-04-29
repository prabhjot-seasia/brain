package com.assurant.brain.ingest.parsers;

import com.assurant.brain.graph.node.ProjectNode;
import com.assurant.brain.graph.node.QueueNode;
import com.assurant.brain.ingest.JavaAstContext;
import com.assurant.brain.ingest.JavaAstVisitor;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Log4j2
@Component
public class MessageListenerAnnotationParser implements JavaAstVisitor {

    private static final Map<String, String> ANNOTATION_TO_TYPE = Map.of(
            "KafkaListener", "KAFKA",
            "SqsListener", "SQS",
            "RabbitListener", "RABBITMQ",
            "JmsListener", "JMS",
            "StreamListener", "KAFKA"
    );

    private static final List<String> NAME_ATTRIBUTES = List.of(
            "topics", "topic", "destination", "queues", "queue", "value", "bindings");

    @Override
    public String name() {
        return "MessageListenerAnnotationParser";
    }

    @Override
    public void visit(JavaAstContext context) {
        Set<String> alreadyConsumed = new HashSet<>();
        context.projectNode().getConsumedQueues().forEach(q -> alreadyConsumed.add(q.getId()));

        context.cu().findAll(AnnotationExpr.class).forEach(annotation -> {
            String type = ANNOTATION_TO_TYPE.get(annotation.getNameAsString());
            if (type == null) return;
            for (String name : extractTargetNames(annotation)) {
                addQueue(context.projectNode(), context.projectId(), type, name, alreadyConsumed);
            }
        });
    }

    private List<String> extractTargetNames(AnnotationExpr annotation) {
        if (annotation instanceof SingleMemberAnnotationExpr single) {
            String value = stripQuotes(single.getMemberValue().toString());
            return value.isBlank() ? List.of() : List.of(value);
        }
        if (annotation instanceof NormalAnnotationExpr normal) {
            for (MemberValuePair pair : normal.getPairs()) {
                if (NAME_ATTRIBUTES.contains(pair.getNameAsString())) {
                    return splitArrayLiteral(pair.getValue().toString());
                }
            }
        }
        return List.of();
    }

    private List<String> splitArrayLiteral(String literal) {
        String trimmed = literal.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        return java.util.Arrays.stream(trimmed.split(","))
                .map(this::stripQuotes)
                .filter(s -> !s.isBlank())
                .toList();
    }

    private String stripQuotes(String value) {
        String trimmed = value.trim();
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() >= 2) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private void addQueue(ProjectNode projectNode, String projectId, String type, String name,
                          Set<String> alreadyConsumed) {
        if (name.isBlank()) return;
        String id = projectId + ":queue:" + type.toLowerCase() + ":" + name;
        if (alreadyConsumed.contains(id)) return;
        if (projectNode.getConsumedQueues().stream().anyMatch(q -> id.equals(q.getId()))) return;

        QueueNode queue = new QueueNode();
        queue.setId(id);
        queue.setQueueType(type);
        queue.setName(name);
        queue.setSource("ANNOTATION");
        projectNode.getConsumedQueues().add(queue);
        alreadyConsumed.add(id);
    }
}
