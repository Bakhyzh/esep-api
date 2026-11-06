ALTER TABLE outbox_events ADD COLUMN target_topic VARCHAR(200);
CREATE TABLE ledger_control (
 id INTEGER PRIMARY KEY CHECK(id=1),
 writes_enabled BOOLEAN NOT NULL DEFAULT true,
 epoch BIGINT NOT NULL DEFAULT 0,
 changed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO ledger_control(id) VALUES (1);
