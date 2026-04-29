package com.assurant.brain.service;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.dto.response.ClarificationResponse;
import com.assurant.brain.enums.LlmOperation;
import com.assurant.brain.graph.repository.ProjectNodeRepository;
import com.assurant.brain.guardrail.RailChain;
import com.assurant.brain.guardrail.RailContext;
import com.assurant.brain.monitor.TokenUsageTracker;
import com.assurant.brain.util.LlmJsonParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Log4j2
@Service("clarifierService")
@RequiredArgsConstructor
public class ClarifierService {

    private static final String SYSTEM_PROMPT_TEMPLATE = """
            You are the Project Brain — an expert software architect who INVESTIGATES
            before drafting implementation plans.

            YOUR FIRST DUTY IS TO ASK, NOT TO ASSUME.

            On the FIRST round (when "Previous Q&A rounds" is "none"), you are an investigator:
            - Ask questions that uncover hidden complexity and architectural decisions
            - Probe for: 3rd party integrations, external APIs, data sources, validation rules,
              authentication/authorization requirements, error handling expectations,
              whether this is new functionality or extending existing code
            - Do NOT assume defaults for architectural decisions — ASK
            - Score dimensions LOW (0.2–0.4) when the requirement does not explicitly specify them
            - A one-line requirement like "add validator" should score WHAT at 0.2–0.3 because
              it does not specify what it validates, against what data, or how

            On SUBSEQUENT rounds (when previous Q&A exists), you may start accepting
            reasonable defaults for questions the developer chose not to answer in detail,
            but still probe any NEW gaps the answers revealed.

            SCORING CALIBRATION — be honest, not generous:
            - 0.0–0.2: No information at all about this dimension
            - 0.2–0.4: Vague hint but no concrete detail (e.g., "add validator" without
                        specifying what it validates, against what, or how)
            - 0.4–0.6: Partial info — some specifics given but key decisions still open
            - 0.6–0.8: Most details known, only minor assumptions documented
            - 0.8–1.0: Fully specified, no assumptions needed

            Score each of the four dimensions 0.0–1.0:
              WHY:   business reason / motivation — why is this change needed?
              WHAT:  scope — what changes, what stays the same, what are the boundaries?
              WHERE: which project / module / files are involved?
              HOW:   technical approach — constraints, NFRs, integration method, data flow?

            Set "confident" to true when the AVERAGE of the four dimensions is
            ≥ %.2f AND no individual dimension is below %.2f.

            QUESTION QUALITY MATTERS:
            - Ask questions that a senior architect would ask in a design review
            - Each question should uncover a decision point, not confirm an obvious fact
            - Prioritize: integration points > data flow > business rules > implementation details
            - Do NOT ask questions whose answers are already in the "Previous Q&A rounds"

            FOR EACH QUESTION, provide 2-4 suggested answers based on what you know about
            the project's codebase, architecture, and conventions. These help the developer
            understand what you are asking and choose quickly. Always include the most likely
            approach based on existing patterns as the first option.

            If an unknown project or system is mentioned that you have no context
            about, flag it in "unknownReferences" — that IS a blocker.

            Return a JSON object with this exact structure:
            {
              "confident": true/false,
              "dimensions": {
                "why":   { "score": 0.0-1.0, "summary": "what you know or assume" },
                "what":  { "score": 0.0-1.0, "summary": "what you know or assume" },
                "where": { "score": 0.0-1.0, "summary": "what you know or assume" },
                "how":   { "score": 0.0-1.0, "summary": "what you know or assume" }
              },
              "unknownReferences": ["list of systems/projects mentioned but not in context"],
              "questions": [
                {
                  "text": "the question",
                  "options": ["suggested answer 1", "suggested answer 2", "suggested answer 3"]
                }
              ]
            }

            Limit questions to %d per round, prioritizing the most impactful questions first.
            """;

    private final ChatModel chatModel;
    private final ProjectNodeRepository projectNodeRepository;
    private final TokenUsageTracker tokenUsageTracker;
    private final BrainProperties brainProperties;
    private final RailChain railChain;

    public ClarificationResponse analyze(String projectId, String requirement, List<Map<String, Object>> previousRounds) {
        log.info("Clarifying requirement for project={}, rounds-so-far={}", projectId, previousRounds.size());

        BrainProperties.Clarifier cfg = resolveClarifierConfig();

        RailChain.ChainResult preLlm = railChain.applyPreLlm(
                RailContext.preLlm(projectId, "ClarifierService", requirement));
        String sanitizedRequirement = preLlm.sanitized();

        String knownProjects = fetchKnownProjectNames();
        String conversationContext = buildConversationContext(sanitizedRequirement, previousRounds);

        String systemPrompt = SYSTEM_PROMPT_TEMPLATE.formatted(
                cfg.confidenceAverageThreshold(),
                cfg.confidencePerDimensionFloor(),
                cfg.maxQuestionsPerRound());

        Prompt prompt = new Prompt(List.of(
                new SystemMessage(systemPrompt),
                new UserMessage("""
                        Known projects in the Brain: %s

                        Requirement: %s

                        Previous Q&A rounds: %s

                        Analyze and respond with JSON only.
                        """.formatted(knownProjects, sanitizedRequirement, conversationContext))
        ));

        long startMs = System.currentTimeMillis();
        String rawResponse = chatModel.call(prompt).getResult().getOutput().getText();
        long latencyMs = System.currentTimeMillis() - startMs;
        log.debug("Clarifier LLM response: {}", rawResponse);

        String modelName = brainProperties.llm() != null ? brainProperties.llm().planModel() : "unknown";
        tokenUsageTracker.track("ClarifierService", LlmOperation.CLARIFY, projectId,
                sanitizedRequirement, rawResponse, latencyMs, false, modelName);

        railChain.applyPostLlm(RailContext.postLlm(projectId, "ClarifierService", rawResponse,
                Map.of()));

        return parseClarificationResponse(rawResponse, previousRounds.size());
    }

    private String fetchKnownProjectNames() {
        return projectNodeRepository.findAll().stream()
                .map(p -> p.getId() + " (" + StringUtils.defaultString(p.getFramework()) + ")")
                .reduce((a, b) -> a + ", " + b)
                .orElse("none");
    }

    @SuppressWarnings("unchecked")
    private String buildConversationContext(String requirement, List<Map<String, Object>> rounds) {
        if (rounds.isEmpty()) return "none";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rounds.size(); i++) {
            Map<String, Object> round = rounds.get(i);
            sb.append("Round ").append(i + 1).append(":\n");
            Object questions = round.getOrDefault("questions", "");
            if (questions instanceof List<?> qList) {
                for (int q = 0; q < qList.size(); q++) {
                    Object qItem = qList.get(q);
                    if (qItem instanceof Map<?, ?> qMap) {
                        sb.append("  Q").append(q + 1).append(": ").append(((Map<String, Object>) qMap).getOrDefault("text", "")).append("\n");
                        Object opts = qMap.get("options");
                        if (opts instanceof List<?> optList && !optList.isEmpty()) {
                            sb.append("    Options: ").append(optList).append("\n");
                        }
                    } else {
                        sb.append("  Q").append(q + 1).append(": ").append(qItem).append("\n");
                    }
                }
            } else {
                sb.append("  Q: ").append(questions).append("\n");
            }
            sb.append("  A: ").append(round.getOrDefault("answers", "")).append("\n");
        }
        return sb.toString();
    }

    private ClarificationResponse parseClarificationResponse(String rawJson, int currentRound) {
        String json = LlmJsonParser.stripFences(rawJson);

        ClarificationResponse parsed = ClarificationResponse.fromRawJson(json);
        return enforceServerSideConfidence(parsed, currentRound);
    }

    ClarificationResponse enforceServerSideConfidence(ClarificationResponse response, int currentRound) {
        BrainProperties.Clarifier cfg = resolveClarifierConfig();

        if (!response.getUnknownReferences().isEmpty()) {
            return response.withConfident(false);
        }

        if (currentRound == 0 && response.getQuestions().size() < cfg.firstRoundMinQuestions()) {
            log.info("First-round enforcement: only {} questions (min {}), forcing not-confident",
                    response.getQuestions().size(), cfg.firstRoundMinQuestions());
            return response.withConfident(false);
        }

        double[] scores = response.dimensionScores();
        double sum = 0.0;
        for (double s : scores) {
            if (s < cfg.confidencePerDimensionFloor()) {
                log.debug("Server-side confidence: dimension below floor ({} < {}), forcing not-confident",
                        s, cfg.confidencePerDimensionFloor());
                return response.withConfident(false);
            }
            sum += s;
        }
        double avg = sum / scores.length;
        boolean serverSideConfident = avg >= cfg.confidenceAverageThreshold();

        if (serverSideConfident != response.isConfident()) {
            log.info("Server-side confidence override: LLM said confident={}, server says confident={} (avg={}, scores={})",
                    response.isConfident(), serverSideConfident, avg, scores);
        }
        return response.withConfident(serverSideConfident);
    }

    private BrainProperties.Clarifier resolveClarifierConfig() {
        BrainProperties.Clarifier cfg = brainProperties.clarifier();
        if (cfg != null) return cfg;
        return new BrainProperties.Clarifier(0.75, 0.5, 3, 5, 5);
    }
}
