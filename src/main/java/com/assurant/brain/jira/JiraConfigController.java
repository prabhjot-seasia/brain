package com.assurant.brain.jira;

import com.assurant.brain.config.properties.BrainProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/jira")
@RequiredArgsConstructor
public class JiraConfigController {

    private final BrainProperties brainProperties;

    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> config() {
        BrainProperties.Jira jira = brainProperties.jira();
        String baseUrl = jira == null ? null : jira.baseUrl();
        boolean configured = jira != null
                && notBlank(jira.baseUrl())
                && notBlank(jira.email())
                && notBlank(jira.apiToken());
        return ResponseEntity.ok(Map.of(
                "configured", configured,
                "baseUrl", baseUrl == null ? "" : stripTrailingSlash(baseUrl)
        ));
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
