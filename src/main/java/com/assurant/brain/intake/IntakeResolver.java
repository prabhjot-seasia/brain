package com.assurant.brain.intake;

import com.assurant.brain.dao.IntakeRecordRepository;
import com.assurant.brain.domain.IntakeRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Log4j2
@Service
@RequiredArgsConstructor
public class IntakeResolver {

    private final IntakeRecordRepository intakeRecordRepository;

    public String resolve(UUID intakeId) {
        IntakeRecord record = intakeRecordRepository.findById(intakeId)
                .orElseThrow(() -> new IllegalArgumentException("IntakeRecord not found: " + intakeId));
        if (record.getExtractedText() == null || record.getExtractedText().isBlank()) {
            throw new IllegalStateException("IntakeRecord " + intakeId + " has no extracted text");
        }
        log.debug("Resolved intake {} → {} chars ({})",
                intakeId, record.getExtractedText().length(), record.getSourceType());
        return record.getExtractedText();
    }
}
