package com.assurant.brain.confluence;

import com.assurant.brain.config.properties.BrainProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Log4j2
@Service
@RequiredArgsConstructor
public class ConfluenceClient {

    private final BrainProperties brainProperties;
    private final RestClient.Builder restClientBuilder;

    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> getPage(String pageId) {
        Map<String, Object> body = client().get()
                .uri(host() + "/wiki/rest/api/content/{id}?expand=version,space,ancestors", pageId)
                .retrieve()
                .body(Map.class);
        return Optional.ofNullable(body);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> createPage(String spaceKey, String parentPageId, String title,
                                            String storageBody) {
        Map<String, Object> request = Map.of(
                "type", "page",
                "title", title,
                "space", Map.of("key", spaceKey),
                "ancestors", List.of(Map.of("id", parentPageId)),
                "body", Map.of("storage", Map.of("value", storageBody, "representation", "storage")));
        return client().post()
                .uri(host() + "/wiki/rest/api/content")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(Map.class);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> updatePage(String pageId, int newVersion, String title, String storageBody) {
        Map<String, Object> request = Map.of(
                "id", pageId,
                "type", "page",
                "title", title,
                "version", Map.of("number", newVersion),
                "body", Map.of("storage", Map.of("value", storageBody, "representation", "storage")));
        return client().put()
                .uri(host() + "/wiki/rest/api/content/{id}", pageId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(Map.class);
    }

    public void uploadAttachment(String pageId, String filename, byte[] content,
                                   String contentType, String comment) {
        org.springframework.core.io.ByteArrayResource resource = new org.springframework.core.io.ByteArrayResource(content) {
            @Override public String getFilename() { return filename; }
        };
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", resource);
        if (comment != null) body.add("comment", comment);
        body.add("minorEdit", "true");

        client().post()
                .uri(host() + "/wiki/rest/api/content/{id}/child/attachment", pageId)
                .header("X-Atlassian-Token", "no-check")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    private RestClient client() {
        BrainProperties.Confluence c = brainProperties.confluence();
        if (c == null || c.email() == null || c.apiToken() == null
                || c.email().isBlank() || c.apiToken().isBlank()) {
            throw new IllegalStateException("Confluence credentials not configured");
        }
        String basic = Base64.getEncoder().encodeToString(
                (c.email() + ":" + c.apiToken()).getBytes(StandardCharsets.UTF_8));
        return restClientBuilder.build().mutate()
                .defaultHeader("Authorization", "Basic " + basic)
                .defaultHeader("Accept", "application/json")
                .build();
    }

    private String host() {
        BrainProperties.Confluence c = brainProperties.confluence();
        if (c == null || c.host() == null || c.host().isBlank()) {
            throw new IllegalStateException("brain.confluence.host not configured");
        }
        return c.host().endsWith("/") ? c.host().substring(0, c.host().length() - 1) : c.host();
    }
}
