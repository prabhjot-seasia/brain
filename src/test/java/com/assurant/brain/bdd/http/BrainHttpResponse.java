package com.assurant.brain.bdd.http;

public record BrainHttpResponse(
        int status,
        String body
) {
    public boolean isSuccess() {
        return status >= 200 && status < 300;
    }
}
