package com.assurant.brain.docs;

public record EndpointSummary(String qualifiedName, String filePath) {

    public String simpleName() {
        if (qualifiedName == null) return "";
        int dot = qualifiedName.lastIndexOf('.');
        return dot >= 0 ? qualifiedName.substring(dot + 1) : qualifiedName;
    }
}
