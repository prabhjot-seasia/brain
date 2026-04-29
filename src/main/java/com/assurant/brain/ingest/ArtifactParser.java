package com.assurant.brain.ingest;

public interface ArtifactParser {

    String name();

    boolean supports(IngestionContext context);

    ParseResult parse(IngestionContext context);
}
