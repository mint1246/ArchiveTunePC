CREATE TABLE friend_messages (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    sender_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    recipient_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    conversation_low TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    conversation_high TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind TEXT NOT NULL CHECK(kind IN ('text', 'song', 'queue', 'playlist')),
    payload_json TEXT NOT NULL CHECK(length(payload_json) <= 262144),
    created_at INTEGER NOT NULL,
    CHECK(sender_id <> recipient_id),
    CHECK(conversation_low < conversation_high),
    CHECK(
        (sender_id = conversation_low AND recipient_id = conversation_high)
        OR (sender_id = conversation_high AND recipient_id = conversation_low)
    )
);

CREATE INDEX friend_messages_conversation
    ON friend_messages(conversation_low, conversation_high, id DESC);
