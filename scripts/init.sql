-- init.sql runs as the Postgres superuser on first container start.
-- Its ONLY job is to enable the pgvector extension, which requires superuser.
-- All table/index DDL is owned by Liquibase — do NOT add table creation here.
CREATE EXTENSION IF NOT EXISTS vector;
