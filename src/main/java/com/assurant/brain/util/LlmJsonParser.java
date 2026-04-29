package com.assurant.brain.util;

public final class LlmJsonParser {

    private LlmJsonParser() {}

    public static String stripFences(String rawResponse) {
        String json = rawResponse.trim();
        if (json.contains("```json")) {
            return json.substring(json.indexOf("```json") + 7, json.lastIndexOf("```")).trim();
        }
        if (json.contains("```")) {
            return json.substring(json.indexOf("```") + 3, json.lastIndexOf("```")).trim();
        }
        return json;
    }
}
