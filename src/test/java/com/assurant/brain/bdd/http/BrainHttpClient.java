package com.assurant.brain.bdd.http;

import java.util.Map;

public interface BrainHttpClient {

    BrainHttpResponse get(String path);

    BrainHttpResponse get(String path, Map<String, String> queryParams);

    BrainHttpResponse postJson(String path, String jsonBody);
}
