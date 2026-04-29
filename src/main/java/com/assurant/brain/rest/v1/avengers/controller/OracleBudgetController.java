package com.assurant.brain.rest.v1.avengers.controller;

import com.assurant.brain.avenger.oracle.OraclePromptOptimizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Log4j2
@RestController
@RequestMapping("/api/v1/avengers/oracle")
@RequiredArgsConstructor
public class OracleBudgetController {

    private final OraclePromptOptimizer oraclePromptOptimizer;

    @GetMapping("/budget/{projectId}")
    @org.springframework.security.access.prepost.PreAuthorize("@projectAccess.canRead(#projectId)")
    public ResponseEntity<OraclePromptOptimizer.RetrievalBudget> budget(@PathVariable String projectId) {
        return ResponseEntity.ok(oraclePromptOptimizer.computeBudget(projectId));
    }
}
