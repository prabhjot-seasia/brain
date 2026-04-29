package com.assurant.brain.bdd.http;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Map;

public class RestTemplateBrainHttpClient implements BrainHttpClient {

    private final RestTemplate rest;
    private final String baseUrl;

    public RestTemplateBrainHttpClient(String baseUrl) {
        this.baseUrl = baseUrl != null && baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.rest = new RestTemplate();
    }

    @Override
    public BrainHttpResponse get(String path) {
        return get(path, Map.of());
    }

    @Override
    public BrainHttpResponse get(String path, Map<String, String> queryParams) {
        UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(baseUrl + path);
        queryParams.forEach(uri::queryParam);
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        return execute(uri.build().toUriString(), HttpMethod.GET, new HttpEntity<>(headers));
    }

    @Override
    public BrainHttpResponse postJson(String path, String jsonBody) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return execute(baseUrl + path, HttpMethod.POST, new HttpEntity<>(jsonBody, headers));
    }

    private BrainHttpResponse execute(String url, HttpMethod method, HttpEntity<?> entity) {
        try {
            ResponseEntity<String> response = rest.exchange(url, method, entity, String.class);
            return new BrainHttpResponse(response.getStatusCode().value(),
                    response.getBody() != null ? response.getBody() : "");
        } catch (RestClientResponseException e) {
            return new BrainHttpResponse(e.getStatusCode().value(), e.getResponseBodyAsString());
        }
    }
}
