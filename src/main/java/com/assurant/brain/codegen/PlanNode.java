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
        SeamFlag seamFlag
) {
    public PlanNode withSeamFlag(SeamFlag flag) {
        return new PlanNode(blockId, projectId, filePath, targetSymbol,
                instruction, kind, derived, flag);
    }
}
