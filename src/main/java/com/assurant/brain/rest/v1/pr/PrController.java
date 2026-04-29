package com.assurant.brain.rest.v1.pr;

import com.assurant.brain.dao.CodeReviewIterationRepository;
import com.assurant.brain.dao.PullRequestRecordRepository;
import com.assurant.brain.domain.CodeReviewIteration;
import com.assurant.brain.domain.PullRequestRecord;
import com.assurant.brain.dto.request.CreatePrRequest;
import com.assurant.brain.dto.response.PrRecordResponse;
import com.assurant.brain.facade.PrCreationFacade;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/pr")
@RequiredArgsConstructor
public class PrController {

    private final PrCreationFacade prCreationFacade;
    private final PullRequestRecordRepository prRecordRepository;
    private final CodeReviewIterationRepository reviewIterationRepository;

    @PostMapping("/create")
    public ResponseEntity<PrRecordResponse> createPr(@Valid @RequestBody CreatePrRequest request) {
        PullRequestRecord record = prCreationFacade.createPr(
                UUID.fromString(request.sessionId()),
                request.repoUrl(),
                request.baseBranch()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(PrRecordResponse.from(record));
    }

    @GetMapping("/{id}")
    public ResponseEntity<PrRecordResponse> getPr(@PathVariable UUID id) {
        return prRecordRepository.findById(id)
                .map(PrRecordResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    public ResponseEntity<List<PrRecordResponse>> listPrs(
            @RequestParam(required = false) UUID sessionId) {
        List<PullRequestRecord> records = sessionId != null
                ? prRecordRepository.findBySessionIdOrderByCreatedAtDesc(sessionId)
                : prRecordRepository.findAllByOrderByCreatedAtDesc();

        return ResponseEntity.ok(records.stream().map(PrRecordResponse::from).toList());
    }

    @GetMapping("/{id}/reviews")
    public ResponseEntity<List<CodeReviewIteration>> getReviewIterations(@PathVariable UUID id) {
        return ResponseEntity.ok(
                reviewIterationRepository.findByPrRecordIdOrderByIterationNumberAsc(id));
    }
}
