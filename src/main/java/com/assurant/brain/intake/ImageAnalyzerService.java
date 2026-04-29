package com.assurant.brain.intake;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.enums.LlmOperation;
import com.assurant.brain.monitor.TokenUsageTracker;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Log4j2
@Service
@RequiredArgsConstructor
public class ImageAnalyzerService {

    private static final String SYSTEM_PROMPT = """
            You are a requirements analyst extracting structured information from images.
            The image may contain: whiteboard sketches, architecture diagrams, UI mockups,
            screenshots of documents, handwritten notes, or flowcharts.

            Extract ALL text, requirements, user stories, acceptance criteria, and technical
            details visible in the image. Structure the output as:

            ## Extracted Requirements
            - Each requirement as a bullet point

            ## Technical Details
            - Any architecture, flow, or technical decisions visible

            ## Notes
            - Any other relevant information

            If the image is unclear or partially readable, extract what you can and note
            what was unreadable.
            """;

    private final ChatModel chatModel;
    private final TokenUsageTracker tokenUsageTracker;
    private final BrainProperties brainProperties;

    public String analyzeImage(MultipartFile file) {
        try {
            MimeType mimeType = MimeType.valueOf(
                    file.getContentType() != null ? file.getContentType() : "image/png");

            Media imageMedia = Media.builder()
                    .mimeType(mimeType)
                    .data(file.getResource())
                    .build();

            UserMessage userMessage = UserMessage.builder()
                    .text("Extract all requirements and technical details from this image.")
                    .media(imageMedia)
                    .build();

            Prompt prompt = new Prompt(List.of(
                    new SystemMessage(SYSTEM_PROMPT),
                    userMessage
            ));

            long startMs = System.currentTimeMillis();
            String result = chatModel.call(prompt).getResult().getOutput().getText();
            long latencyMs = System.currentTimeMillis() - startMs;

            String modelName = brainProperties.llm() != null ? brainProperties.llm().extractModel() : "unknown";
            tokenUsageTracker.track("ImageAnalyzerService", LlmOperation.IMAGE_ANALYZE, null,
                    file.getOriginalFilename(), result, latencyMs, false, modelName);

            log.info("Image analysis complete for file={}, extracted {} chars",
                    file.getOriginalFilename(), result.length());
            return result;
        } catch (Exception e) {
            log.error("Image analysis failed for {}", file.getOriginalFilename(), e);
            throw new IllegalStateException("Image analysis failed: " + e.getMessage());
        }
    }
}
