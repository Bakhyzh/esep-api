CREATE TABLE identity_projection_state (
 user_id BIGINT PRIMARY KEY REFERENCES ledger_principals(id),
 issuer VARCHAR(512) NOT NULL,
 subject VARCHAR(255) NOT NULL,
 source_version BIGINT NOT NULL CHECK(source_version>0),
 status VARCHAR(10) NOT NULL CHECK(status IN ('ACTIVE','DISABLED')),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(issuer,subject)
);
CREATE TABLE identity_processed_events (
 event_id UUID PRIMARY KEY,
 payload_hash VARCHAR(64) NOT NULL,
 processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
