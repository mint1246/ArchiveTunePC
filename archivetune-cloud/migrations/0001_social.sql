PRAGMA foreign_keys = ON;

CREATE TABLE users (
    id TEXT PRIMARY KEY,
    discord_id TEXT NOT NULL UNIQUE,
    discord_username TEXT NOT NULL,
    display_name TEXT,
    avatar_hash TEXT,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE INDEX users_discord_username_ci
    ON users(lower(discord_username));

CREATE TABLE sessions (
    token_hash TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    last_used_at INTEGER NOT NULL
);

CREATE INDEX sessions_user_id ON sessions(user_id);
CREATE INDEX sessions_expires_at ON sessions(expires_at);

CREATE TABLE auth_codes (
    code_hash TEXT PRIMARY KEY,
    nonce_hash TEXT NOT NULL,
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    consumed_at INTEGER
);

CREATE INDEX auth_codes_expires_at ON auth_codes(expires_at);

CREATE TABLE friend_requests (
    sender_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    receiver_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at INTEGER NOT NULL,
    PRIMARY KEY(sender_id, receiver_id),
    CHECK(sender_id <> receiver_id)
);

CREATE INDEX friend_requests_receiver_id
    ON friend_requests(receiver_id, created_at DESC);

CREATE TABLE friendships (
    user_low TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    user_high TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at INTEGER NOT NULL,
    PRIMARY KEY(user_low, user_high),
    CHECK(user_low < user_high)
);

CREATE INDEX friendships_high ON friendships(user_high);

CREATE TABLE blocks (
    blocker_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    blocked_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at INTEGER NOT NULL,
    PRIMARY KEY(blocker_id, blocked_id),
    CHECK(blocker_id <> blocked_id)
);

CREATE INDEX blocks_blocked_id ON blocks(blocked_id);
