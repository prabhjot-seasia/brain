package com.assurant.brain.testing;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public record TestScenario(
        String scenarioId,
        String description,
        TestScenarioType type,
        TestScenarioStatus status,
        String linkedTest,
        String qaNotes,
        TestScenarioSource source) {

    public static String idFor(String issueKey, String description) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String payload = (issueKey == null ? "_" : issueKey) + "|" + (description == null ? "" : description);
            String hex = HexFormat.of().formatHex(md.digest(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            return "TC-" + (issueKey == null ? "GEN" : issueKey) + "-" + hex.substring(0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
