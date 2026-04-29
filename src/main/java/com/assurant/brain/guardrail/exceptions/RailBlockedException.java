package com.assurant.brain.guardrail.exceptions;

import com.assurant.brain.enums.RailType;
import lombok.Getter;

import java.util.List;

@Getter
public class RailBlockedException extends RuntimeException {

    private final RailType rail;
    private final List<String> violations;

    public RailBlockedException(RailType rail, List<String> violations) {
        super("Guardrail " + rail + " blocked request: " + String.join("; ", violations));
        this.rail = rail;
        this.violations = violations;
    }
}
