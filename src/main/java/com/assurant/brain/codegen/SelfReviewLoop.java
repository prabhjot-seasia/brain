package com.assurant.brain.codegen;

import com.assurant.brain.dao.CodeReviewIterationRepository;
import com.assurant.brain.dao.PullRequestRecordRepository;
import com.assurant.brain.domain.CodeReviewIteration;
import com.assurant.brain.domain.PullRequestRecord;
import com.assurant.brain.enums.ReviewVerdict;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.util.Map;

@Log4j2
@Service
@RequiredArgsConstructor
public class SelfReviewLoop {

    private final CodeGeneratorService codeGeneratorService;
    private final CodeReviewService codeReviewService;
    private final PullRequestRecordRepository prRecordRepository;
    private final CodeReviewIterationRepository reviewIterationRepository;

    public Map<String, String> run(PullRequestRecord record,
                                    Map<String, String> files,
                                    String planJson,
                                    int maxIterations) {
        Map<String, String> currentFiles = files;

        for (int i = 1; i <= maxIterations; i++) {
            log.info("Self-review iteration {} for PR record={}", i, record.getId());

            CodeReviewService.ReviewResult result = codeReviewService.review(currentFiles, planJson);

            CodeReviewIteration iteration = new CodeReviewIteration();
            iteration.setPrRecordId(record.getId());
            iteration.setIterationNumber(i);
            iteration.setVerdict(result.passed() ? ReviewVerdict.PASS : ReviewVerdict.FAIL);
            iteration.setIssuesFound(result.issues());
            reviewIterationRepository.save(iteration);

            record.setSelfReviewIterations(i);
            prRecordRepository.save(record);

            if (result.passed()) {
                log.info("Self-review passed on iteration {} for PR record={}", i, record.getId());
                return currentFiles;
            }

            if (i < maxIterations) {
                log.info("Self-review failed on iteration {} — fixing {} issues", i, result.issues().size());
                currentFiles = codeGeneratorService.fixCode(currentFiles, result.issues(), planJson);

                iteration.setFixesApplied(result.issues());
                reviewIterationRepository.save(iteration);
            }
        }

        log.warn("Self-review did not pass after {} iterations for PR record={} — proceeding anyway",
                maxIterations, record.getId());
        return currentFiles;
    }
}
