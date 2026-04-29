package com.assurant.brain.config;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.util.TokenEstimator;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.bedrock.converse.BedrockProxyChatModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
@RequiredArgsConstructor
public class LlmConfig {

    private final BrainProperties brainProperties;

    @PostConstruct
    void configureTokenEstimator() {
        if (brainProperties.llm() != null && brainProperties.llm().charsPerToken() > 0) {
            TokenEstimator.configure(brainProperties.llm().charsPerToken());
        }
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "brain.llm.provider", havingValue = "anthropic", matchIfMissing = true)
    public ChatModel anthropicChatModelPrimary(AnthropicChatModel model) {
        return model;
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "brain.llm.provider", havingValue = "ollama")
    public ChatModel ollamaChatModelPrimary(OllamaChatModel model) {
        return model;
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "brain.llm.provider", havingValue = "bedrock")
    public ChatModel bedrockChatModelPrimary(BedrockProxyChatModel model) {
        return model;
    }
}
