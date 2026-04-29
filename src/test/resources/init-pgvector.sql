-- Enable pgvector extension before Liquibase runs migrations.
-- Mirrors scripts/init.sql used in Docker Compose.
CREATE EXTENSION IF NOT EXISTS vector;
