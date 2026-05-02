package com.assurant.brain.codegen;

import java.util.List;

public record EditBoundary(
        List<String> mayTouchFiles,
        List<String> mayTouchSymbols,
        List<String> mustNotTouchSymbols,
        Integer maxEditRatioPct) {

    private static final int DEFAULT_MAX_EDIT_RATIO_PCT = 30;

    public static EditBoundary unbounded() {
        return new EditBoundary(null, null, null, null);
    }

    public static EditBoundary forFile(String filePath) {
        return new EditBoundary(List.of(filePath), null, null, DEFAULT_MAX_EDIT_RATIO_PCT);
    }

    public boolean isUnbounded() {
        return (mayTouchFiles == null || mayTouchFiles.isEmpty())
                && (mayTouchSymbols == null || mayTouchSymbols.isEmpty())
                && (mustNotTouchSymbols == null || mustNotTouchSymbols.isEmpty())
                && (maxEditRatioPct == null || maxEditRatioPct <= 0);
    }

    public boolean fileAllowed(String path) {
        if (mayTouchFiles == null || mayTouchFiles.isEmpty()) return true;
        return mayTouchFiles.contains(path);
    }

    public boolean symbolForbidden(String symbol) {
        if (mustNotTouchSymbols == null || mustNotTouchSymbols.isEmpty()) return false;
        return mustNotTouchSymbols.contains(symbol);
    }

    public boolean symbolAllowed(String symbol) {
        if (mayTouchSymbols == null || mayTouchSymbols.isEmpty()) return true;
        return mayTouchSymbols.contains(symbol);
    }

    public int effectiveMaxEditRatioPct() {
        return maxEditRatioPct == null || maxEditRatioPct <= 0
                ? DEFAULT_MAX_EDIT_RATIO_PCT
                : maxEditRatioPct;
    }
}
