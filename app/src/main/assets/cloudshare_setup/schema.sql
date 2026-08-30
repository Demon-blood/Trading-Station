CREATE TABLE IF NOT EXISTS invites (
    id TEXT PRIMARY KEY,
    code_hash TEXT NOT NULL UNIQUE,
    label TEXT NOT NULL DEFAULT '',
    max_uses INTEGER NOT NULL DEFAULT 1,
    uses INTEGER NOT NULL DEFAULT 0,
    expires_at TEXT,
    revoked INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS clients (
    client_id TEXT PRIMARY KEY,
    token_hash TEXT NOT NULL,
    contributor_id TEXT NOT NULL,
    client_name TEXT NOT NULL DEFAULT '',
    enabled INTEGER NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL,
    last_seen_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_clients_contributor ON clients(contributor_id);

CREATE TABLE IF NOT EXISTS events (
    seq INTEGER PRIMARY KEY AUTOINCREMENT,
    event_id TEXT NOT NULL UNIQUE,
    contributor_id TEXT NOT NULL,
    source_table TEXT NOT NULL,
    aggregate_key TEXT NOT NULL DEFAULT '',
    event_timestamp TEXT NOT NULL,
    received_at TEXT NOT NULL,
    schema_version INTEGER NOT NULL DEFAULT 1,
    payload_json TEXT NOT NULL,
    payload_sha256 TEXT NOT NULL DEFAULT ''
);

CREATE INDEX IF NOT EXISTS idx_events_seq ON events(seq);
CREATE INDEX IF NOT EXISTS idx_events_source ON events(source_table);
CREATE INDEX IF NOT EXISTS idx_events_contributor ON events(contributor_id);

CREATE TABLE IF NOT EXISTS engine_leases (
    account_key TEXT PRIMARY KEY,
    holder_client_id TEXT NOT NULL,
    holder_engine_id TEXT NOT NULL,
    platform TEXT NOT NULL DEFAULT '',
    expires_at_epoch_ms INTEGER NOT NULL,
    updated_at TEXT NOT NULL,
    fence_token INTEGER NOT NULL DEFAULT 1,
    schema_version INTEGER NOT NULL DEFAULT 2
);

CREATE INDEX IF NOT EXISTS idx_engine_leases_expiry ON engine_leases(expires_at_epoch_ms);
