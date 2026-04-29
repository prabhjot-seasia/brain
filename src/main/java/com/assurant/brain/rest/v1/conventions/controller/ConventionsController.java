package com.assurant.brain.rest.v1.conventions.controller;

import com.assurant.brain.graph.node.ConventionNode;
import com.assurant.brain.graph.repository.ConventionNodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Log4j2
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1")
public class ConventionsController {

    private final ConventionNodeRepository conventionNodeRepository;

    @GetMapping(value = "/projects/{projectId}/conventions",
                produces = MediaType.APPLICATION_JSON_VALUE)
    @org.springframework.security.access.prepost.PreAuthorize("@projectAccess.canRead(#projectId)")
    public ResponseEntity<List<ConventionNode>> getConventions(
            @PathVariable String projectId,
            @RequestParam(required = false) String category) {

        log.info("Conventions request for project={}, category={}", projectId, category);

        List<ConventionNode> conventions = (category != null)
                ? conventionNodeRepository.findByProjectIdAndCategory(projectId, category)
                : conventionNodeRepository.findByProjectIdOrderByTrustWeightDesc(projectId);

        return ResponseEntity.ok(conventions);
    }
}
