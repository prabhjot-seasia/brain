package com.assurant.brain.rest.v1.incident.controller;

import com.assurant.brain.incident.IncidentExplainerService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Log4j2
@RestController
@RequestMapping("/api/v1/incidents")
@RequiredArgsConstructor
public class IncidentExplainerController {

    private final IncidentExplainerService incidentExplainerService;

    public record ExplainRequest(@Valid @NotBlank String projectId,
                                  @Valid @NotBlank String classHint) {}

    @PostMapping(value = "/explain", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> explain(@Valid @RequestBody ExplainRequest body) {
        String markdown = incidentExplainerService.explainIncident(body.projectId(), body.classHint());
        return ResponseEntity.ok(Map.of("markdown", markdown));
    }
}
