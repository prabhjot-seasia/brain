package com.assurant.brain.conventions;

import java.util.List;

public record RulePack(
        String id,
        String version,
        String description,
        List<PackConvention> conventions) {

    public record PackConvention(
            String rule,
            String category,
            double trustWeight) {}

    public String sourceTag() {
        return "rulepack:" + id + "@" + version;
    }
}
