package com.assurant.brain.ingest;

public interface JavaAstVisitor {

    String name();

    void visit(JavaAstContext context);
}
