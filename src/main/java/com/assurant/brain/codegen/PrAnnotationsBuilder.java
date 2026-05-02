package com.assurant.brain.codegen;

import com.assurant.brain.avenger.MirageReviewer;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Log4j2
@Service
@RequiredArgsConstructor
public class PrAnnotationsBuilder {

    private static final int BASELINE_SAMPLES = 30;
    private static final List<String> CONVENTIONS_ENFORCED = List.of(
            "@RequiredArgsConstructor (no @Autowired field injection)",
            "@Log4j2 (not @Slf4j)",
            "no comments",
            "no wildcard imports",
            "Bean Validation on DTOs",
            "FilterExpressionBuilder for vector queries");

    private final MirageReviewer mirageReviewer;
    private final VectorStore vectorStore;
    private final PrDescriptionRenderer prDescriptionRenderer;

    public String renderPrBody(String projectId, Map<String, String> generatedFiles, String planSummary) {
        MirageReviewer.Report mirageReport = runMirage(projectId, generatedFiles);
        PrDescriptionRenderer.PrAnnotations ann = new PrDescriptionRenderer.PrAnnotations(
                Map.of(),
                generatedFiles,
                Set.of(),
                CONVENTIONS_ENFORCED,
                mirageReport,
                List.of(),
                0,
                0,
                planSummary,
                planSummary);
        return prDescriptionRenderer.render(ann);
    }

    private MirageReviewer.Report runMirage(String projectId, Map<String, String> generatedFiles) {
        try {
            List<String> baseline = sampleBaseline(projectId);
            return mirageReviewer.review(generatedFiles, baseline);
        } catch (RuntimeException e) {
            log.debug("MIRAGE skipped for project={}: {}", projectId, e.getMessage());
            return null;
        }
    }

    private List<String> sampleBaseline(String projectId) {
        if (projectId == null || projectId.isBlank()) return List.of();
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        List<Document> docs = vectorStore.similaritySearch(SearchRequest.builder()
                .query("class implementation")
                .topK(BASELINE_SAMPLES)
                .filterExpression(b.and(
                        b.eq("projectId", projectId),
                        b.eq("sourceType", "CODE")).build())
                .build());
        if (docs == null || docs.isEmpty()) return List.of();
        List<String> out = new ArrayList<>(docs.size());
        for (Document d : docs) {
            if (d.getText() != null && !d.getText().isBlank()) out.add(d.getText());
        }
        return out;
    }
}
