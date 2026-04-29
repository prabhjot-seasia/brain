package com.assurant.brain.util;

public final class TokenEstimator {

    private static volatile double charsPerToken = 4.0;

    private TokenEstimator() {}

    public static void configure(double newCharsPerToken) {
        charsPerToken = newCharsPerToken;
    }

    public static int estimate(String text) {
        if (text == null || text.isEmpty()) return 0;
        return (int) Math.ceil(text.length() / charsPerToken);
    }

    public static int estimate(Iterable<String> segments) {
        int total = 0;
        for (String segment : segments) {
            total += estimate(segment);
        }
        return total;
    }

    public static boolean fitsWithinBudget(String text, int tokenBudget) {
        return estimate(text) <= tokenBudget;
    }

    public static String truncateToTokenBudget(String text, int tokenBudget) {
        if (text == null) return "";
        int maxChars = (int) (tokenBudget * charsPerToken);
        if (text.length() <= maxChars) return text;
        return text.substring(0, maxChars);
    }
}
