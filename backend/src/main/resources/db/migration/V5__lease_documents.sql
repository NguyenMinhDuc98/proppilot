-- The lease corpus: one generated PDF per lease and the chunks it is searched through.

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE lease_documents (
    id                BIGSERIAL PRIMARY KEY,
    lease_id          BIGINT       NOT NULL UNIQUE REFERENCES leases (id),
    -- LSE-<unit code>, the prefix of every citation marker such as [LSE-A-504#7]
    code              VARCHAR(32)  NOT NULL UNIQUE,
    title             VARCHAR(200) NOT NULL,
    language          VARCHAR(2)   NOT NULL CHECK (language IN ('en', 'ar')),
    page_count        INT          NOT NULL,
    -- bumped whenever the generator would write another PDF, so stored documents can be told from stale ones
    generator_version INT          NOT NULL,
    pdf               BYTEA        NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE lease_chunks (
    id              BIGSERIAL PRIMARY KEY,
    document_id     BIGINT       NOT NULL REFERENCES lease_documents (id) ON DELETE CASCADE,
    -- the citation token without brackets: LSE-A-504#7, or LSE-A-504#7a for a part of a long clause
    ref             VARCHAR(48)  NOT NULL UNIQUE,
    ordinal         INT          NOT NULL,
    -- NULL for the preamble that comes before the first clause
    section_no      INT,
    section_title   VARCHAR(200),
    page            INT          NOT NULL,
    content         TEXT         NOT NULL,
    -- content with diacritics and tatweel removed and one spelling for each Arabic letter, lower case
    search_text     TEXT         NOT NULL,
    token_estimate  INT          NOT NULL,
    -- NULL until an embedding provider is configured; keyword search needs none
    embedding       VECTOR(1024),
    embedding_model VARCHAR(64),
    fts             TSVECTOR GENERATED ALWAYS AS (
                        to_tsvector('arabic', search_text) || to_tsvector('english', search_text)) STORED,
    UNIQUE (document_id, ordinal)
);

CREATE INDEX idx_lease_chunks_fts ON lease_chunks USING gin (fts);
CREATE INDEX idx_lease_chunks_trgm ON lease_chunks USING gin (search_text gin_trgm_ops);
CREATE INDEX idx_lease_chunks_embedding ON lease_chunks USING hnsw (embedding vector_cosine_ops);
