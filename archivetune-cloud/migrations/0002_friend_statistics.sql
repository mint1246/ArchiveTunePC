CREATE TABLE user_statistics (
    user_id TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    snapshot_json TEXT NOT NULL CHECK(json_valid(snapshot_json)) CHECK(length(snapshot_json) <= 524288),
    updated_at INTEGER NOT NULL CHECK(updated_at > 0)
);
