package com.assurant.brain.intake;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.web.multipart.MultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ImageAnalyzerService")
class ImageAnalyzerServiceTest {

    private ChatModel chatModel;
    private ImageAnalyzerService service;

    @BeforeEach
    void setup() {
        chatModel = mock(ChatModel.class);
        var tracker = mock(com.assurant.brain.monitor.TokenUsageTracker.class);
        var props = new com.assurant.brain.config.properties.BrainProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        service = new ImageAnalyzerService(chatModel, tracker, props);
    }

    @Test
    @DisplayName("analyzes image and returns extracted text")
    void analyzesImage() throws Exception {
        mockChatResponse("## Extracted Requirements\n- Login page must have SSO");

        MultipartFile file = mock(MultipartFile.class);
        when(file.getContentType()).thenReturn("image/png");
        when(file.getOriginalFilename()).thenReturn("mockup.png");
        when(file.getResource()).thenReturn(new org.springframework.core.io.ByteArrayResource(new byte[]{1, 2, 3}));

        String result = service.analyzeImage(file);

        assertThat(result).contains("Extracted Requirements");
        assertThat(result).contains("Login page must have SSO");
    }

    @Test
    @DisplayName("defaults to image/png when content type is null")
    void defaultsContentType() throws Exception {
        mockChatResponse("## Notes\n- Diagram detected");

        MultipartFile file = mock(MultipartFile.class);
        when(file.getContentType()).thenReturn(null);
        when(file.getOriginalFilename()).thenReturn("sketch.jpg");
        when(file.getResource()).thenReturn(new org.springframework.core.io.ByteArrayResource(new byte[]{1}));

        String result = service.analyzeImage(file);
        assertThat(result).contains("Diagram detected");
    }

    @Test
    @DisplayName("wraps exception in IllegalStateException")
    void wrapsException() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("LLM down"));

        MultipartFile file = mock(MultipartFile.class);
        when(file.getContentType()).thenReturn("image/png");
        when(file.getOriginalFilename()).thenReturn("broken.png");
        when(file.getResource()).thenReturn(new org.springframework.core.io.ByteArrayResource(new byte[]{1}));

        assertThatThrownBy(() -> service.analyzeImage(file))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Image analysis failed");
    }

    private void mockChatResponse(String text) {
        Generation generation = mock(Generation.class);
        AssistantMessage output = mock(AssistantMessage.class);
        when(output.getText()).thenReturn(text);
        when(generation.getOutput()).thenReturn(output);
        ChatResponse response = mock(ChatResponse.class);
        when(response.getResult()).thenReturn(generation);
        when(chatModel.call(any(Prompt.class))).thenReturn(response);
    }
}
