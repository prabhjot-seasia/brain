package com.assurant.brain.enums;

public enum AvengerType {
    STARK(AvengerRole.WORKER, AvengerDomain.CODE_CORRECTNESS),
    HAWKEYE(AvengerRole.WORKER, AvengerDomain.SECURITY),
    VISION(AvengerRole.WORKER, AvengerDomain.UI_UX),
    WIDOW(AvengerRole.WORKER, AvengerDomain.TESTING),
    HULK(AvengerRole.WORKER, AvengerDomain.PERFORMANCE),
    FURY(AvengerRole.WORKER, AvengerDomain.DOCUMENTATION),
    FORGE(AvengerRole.SERVICE, AvengerDomain.CODE_VALIDATION),
    ORACLE(AvengerRole.SERVICE, AvengerDomain.TOKEN_ECONOMY),
    MANTIS(AvengerRole.SERVICE, AvengerDomain.LEARNING),
    JARVIS(AvengerRole.SUPPORT, AvengerDomain.INFRASTRUCTURE),
    THANOS(AvengerRole.SUPPORT, AvengerDomain.OVERSIGHT),
    MIRAGE(AvengerRole.SERVICE, AvengerDomain.STYLE_FIT);

    private final AvengerRole role;
    private final AvengerDomain domain;

    AvengerType(AvengerRole role, AvengerDomain domain) {
        this.role = role;
        this.domain = domain;
    }

    public AvengerRole role() { return role; }
    public AvengerDomain domain() { return domain; }

    public String personaPromptPath() {
        return "avengers/" + name() + ".md";
    }
}
