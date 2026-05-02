package com.assurant.brain.codegen;

import com.assurant.brain.enums.ChangeKind;
import com.assurant.brain.enums.SeamFlag;

public record PlanNode(
        String blockId,
        String projectId,
        String filePath,
        String targetSymbol,
        String instruction,
        ChangeKind kind,
        boolean derived,
        SeamFlag seamFlag,
        EditBoundary boundary
) {
    public PlanNode(String blockId, String projectId, String filePath, String targetSymbol,
                    String instruction, ChangeKind kind, boolean derived, SeamFlag seamFlag) {
        this(blockId, projectId, filePath, targetSymbol, instruction, kind, derived, seamFlag, null);
    }

    public PlanNode withSeamFlag(SeamFlag flag) {
        return new PlanNode(blockId, projectId, filePath, targetSymbol,
                instruction, kind, derived, flag, boundary);
    }

    public PlanNode withBoundary(EditBoundary newBoundary) {
        return new PlanNode(blockId, projectId, filePath, targetSymbol,
                instruction, kind, derived, seamFlag, newBoundary);
    }

    public EditBoundary effectiveBoundary() {
        if (boundary != null && !boundary.isUnbounded()) return boundary;
        if (filePath != null && !filePath.isBlank()) return EditBoundary.forFile(filePath);
        return EditBoundary.unbounded();
    }
}
