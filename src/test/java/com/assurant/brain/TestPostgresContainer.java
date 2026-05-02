package com.assurant.brain;

import org.testcontainers.containers.PostgreSQLContainer;

public final class TestPostgresContainer {

    private static final PostgreSQLContainer<?> INSTANCE =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16")
                    .withDatabaseName("brain")
                    .withUsername("brain")
                    .withPassword("brain_local")
                    .withInitScript("init-pgvector.sql")
                    .withCommand("postgres", "-c", "max_connections=400");

    static {
        INSTANCE.start();
    }

    private TestPostgresContainer() {}

    public static PostgreSQLContainer<?> getInstance() {
        return INSTANCE;
    }
}
