package com.assurant.brain.sage;

import com.assurant.brain.sage.dao.ContextGapResolutionRepository;
import com.assurant.brain.sage.domain.ContextGapResolution;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

@Log4j2
@Component
@RequiredArgsConstructor
public class SageCrossProjectMemory {

    private final ContextGapResolutionRepository repository;

    public Optional<String> tryResolve(SageInquisitor.DetectedGap gap) {
        if (gap == null || gap.identifier() == null) return Optional.empty();
        String signature = signatureFor(gap);
        try {
            List<ContextGapResolution> sameSignatureAcrossProjects = repository.findAll().stream()
                    .filter(r -> signature.equals(r.getGapSignature()))
                    .filter(r -> r.getStatus() == GapStatus.RESOLVED)
                    .filter(r -> r.getAnswerValue() != null && !r.getAnswerValue().isBlank())
                    .toList();
            if (sameSignatureAcrossProjects.isEmpty()) return Optional.empty();
            return Optional.of(sameSignatureAcrossProjects.get(0).getAnswerValue());
        } catch (RuntimeException e) {
            log.debug("SageCrossProjectMemory: lookup failed for gap={}: {}", gap.identifier(), e.getMessage());
            return Optional.empty();
        }
    }

    private String signatureFor(SageInquisitor.DetectedGap gap) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String payload = gap.type().name() + "|" + gap.identifier();
            return HexFormat.of().formatHex(md.digest(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    .substring(0, 64);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
