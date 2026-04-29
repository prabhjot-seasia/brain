package com.assurant.brain.guardrail;

import com.assurant.brain.enums.RailDecision;
import com.assurant.brain.enums.RailPhase;
import com.assurant.brain.guardrail.exceptions.RailBlockedException;
import com.assurant.brain.observability.BrainMetrics;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Log4j2
@Service
public class RailChain {

    private final List<Rail> preLlmRails;
    private final List<Rail> postLlmRails;
    private final BrainMetrics brainMetrics;

    public RailChain(List<Rail> rails, BrainMetrics brainMetrics) {
        this.preLlmRails = rails.stream()
                .filter(r -> r.phase() == RailPhase.PRE_LLM)
                .sorted(Comparator.comparingInt(Rail::priority))
                .toList();
        this.postLlmRails = rails.stream()
                .filter(r -> r.phase() == RailPhase.POST_LLM)
                .sorted(Comparator.comparingInt(Rail::priority))
                .toList();
        this.brainMetrics = brainMetrics;
        log.info("RailChain initialized: {} pre-LLM rails, {} post-LLM rails",
                preLlmRails.size(), postLlmRails.size());
    }

    public ChainResult applyPreLlm(RailContext context) {
        return apply(context, preLlmRails);
    }

    public ChainResult applyPostLlm(RailContext context) {
        return apply(context, postLlmRails);
    }

    private ChainResult apply(RailContext context, List<Rail> rails) {
        RailContext current = context;
        List<RailResult> results = new ArrayList<>();

        for (Rail rail : rails) {
            RailResult result = rail.apply(current);
            results.add(result);
            brainMetrics.recordGuardrailOutcome(rail.type(), result.decision());

            if (result.decision() == RailDecision.BLOCK) {
                log.warn("Rail {} BLOCKED in {}: {}", rail.type(), context.sourceService(), result.violations());
                throw new RailBlockedException(rail.type(), result.violations());
            }

            if (result.decision() == RailDecision.MODIFY && result.sanitizedPayload() != null) {
                current = context.phase() == RailPhase.PRE_LLM
                        ? current.withSanitizedInput(result.sanitizedPayload())
                        : current;
                log.debug("Rail {} MODIFIED input in {}, masked entities: {}",
                        rail.type(), context.sourceService(), result.maskedEntities());
            }
        }

        return new ChainResult(current, results);
    }

    public record ChainResult(RailContext context, List<RailResult> rails) {
        public String sanitized() {
            return context.phase() == RailPhase.PRE_LLM ? context.sanitizedInput() : context.rawOutput();
        }
    }
}
