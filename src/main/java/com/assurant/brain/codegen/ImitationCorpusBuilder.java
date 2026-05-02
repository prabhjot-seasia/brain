package com.assurant.brain.codegen;

import lombok.extern.log4j.Log4j2;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Log4j2
@Component
public class ImitationCorpusBuilder {

    public enum TaskType { ADD_ENDPOINT, EDIT_SERVICE, ADD_LIQUIBASE_CHANGESET, ADD_TEST, GENERIC }

    public record Example(String filePath, String content) {}

    private static final int MAX_EXAMPLES = 3;
    private static final int CONTENT_BUDGET_CHARS = 3500;

    private final VectorStore vectorStore;

    public ImitationCorpusBuilder(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public List<Example> findExamples(String projectId, TaskType taskType) {
        if (projectId == null || projectId.isBlank()) return List.of();
        String query = queryFor(taskType);
        try {
            FilterExpressionBuilder b = new FilterExpressionBuilder();
            List<Document> docs = vectorStore.similaritySearch(SearchRequest.builder()
                    .query(query)
                    .topK(MAX_EXAMPLES * 2)
                    .filterExpression(b.and(
                            b.eq("projectId", projectId),
                            b.eq("sourceType", "CODE")).build())
                    .build());
            if (docs == null || docs.isEmpty()) return List.of();
            List<Example> examples = new ArrayList<>(MAX_EXAMPLES);
            int budget = CONTENT_BUDGET_CHARS;
            for (Document d : docs) {
                if (examples.size() >= MAX_EXAMPLES) break;
                String text = d.getText();
                if (text == null || text.isBlank()) continue;
                int take = Math.min(text.length(), budget);
                if (take <= 0) break;
                String filePath = String.valueOf(d.getMetadata().getOrDefault("filePath", "unknown"));
                examples.add(new Example(filePath, text.substring(0, take)));
                budget -= take;
            }
            return examples;
        } catch (RuntimeException e) {
            log.debug("ImitationCorpusBuilder: lookup failed for project={} type={}: {}",
                    projectId, taskType, e.getMessage());
            return List.of();
        }
    }

    public String renderForPrompt(List<Example> examples) {
        if (examples == null || examples.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n\n--- HOUSE STYLE EXAMPLES (this project's own merged code) ---\n");
        for (Example ex : examples) {
            sb.append("// File: ").append(ex.filePath()).append("\n");
            sb.append(ex.content()).append("\n\n");
        }
        sb.append("Mirror the structure, naming, and patterns shown above. Do not invent new patterns when these already work.");
        return sb.toString();
    }

    private String queryFor(TaskType taskType) {
        return switch (taskType) {
            case ADD_ENDPOINT -> "RestController GetMapping PostMapping";
            case EDIT_SERVICE -> "Service class business logic";
            case ADD_LIQUIBASE_CHANGESET -> "liquibase changeset createTable addColumn";
            case ADD_TEST -> "Test DisplayName assertThat";
            case GENERIC -> "class implementation";
        };
    }
}
