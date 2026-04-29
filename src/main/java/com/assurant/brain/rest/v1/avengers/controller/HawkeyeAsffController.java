package com.assurant.brain.rest.v1.avengers.controller;

import com.assurant.brain.avenger.hawkeye.AsffMapper;
import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.dao.AvengerReviewRepository;
import com.assurant.brain.domain.AvengerReview;
import com.assurant.brain.enums.AvengerType;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Log4j2
@RestController
@RequestMapping("/api/v1/avengers/hawkeye")
@RequiredArgsConstructor
public class HawkeyeAsffController {

    private static final int DEFAULT_LIMIT = 50;

    private static final String DEFAULT_PRODUCT_ARN =
            "arn:aws:securityhub:::product/project-brain/hawkeye";

    private final AvengerReviewRepository repository;
    private final AsffMapper asffMapper;
    private final BrainProperties brainProperties;

    private String productArn() {
        if (brainProperties.hawkeye() == null
                || brainProperties.hawkeye().asffProductArn() == null
                || brainProperties.hawkeye().asffProductArn().isBlank()) {
            return DEFAULT_PRODUCT_ARN;
        }
        return brainProperties.hawkeye().asffProductArn();
    }

    @GetMapping(value = "/findings.asff", produces = MediaType.APPLICATION_JSON_VALUE)
    @org.springframework.security.access.prepost.PreAuthorize("@projectAccess.canRead(#projectId)")
    public ResponseEntity<JsonNode> findings(@RequestParam String projectId,
                                              @RequestParam(required = false) Integer limit) {
        int pageSize = limit == null ? DEFAULT_LIMIT : Math.min(limit, 200);
        List<AvengerReview> reviews = repository.findByAvengerAndProjectIdOrderByCreatedAtDesc(
                AvengerType.HAWKEYE, projectId, PageRequest.of(0, pageSize));
        log.info("Emitting ASFF batch for project={} reviews={}", projectId, reviews.size());
        return ResponseEntity.ok(asffMapper.toFindingsBatch(reviews, productArn()));
    }
}
