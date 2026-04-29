package com.assurant.brain.guardrail;

import com.assurant.brain.enums.RailPhase;
import com.assurant.brain.enums.RailType;

public interface Rail {

    RailResult apply(RailContext context);

    RailPhase phase();

    int priority();

    RailType type();
}
