CREATE TABLE IF NOT EXISTS message_feedback (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    message_id  UUID NOT NULL REFERENCES chat_messages(id) ON DELETE CASCADE,
    user_id     UUID REFERENCES app_users(id) ON DELETE SET NULL,
    rating      SMALLINT NOT NULL CHECK (rating BETWEEN 1 AND 5),
    comment     TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX idx_feedback_message_user
    ON message_feedback(message_id, user_id);

CREATE INDEX idx_message_feedback_message_id
    ON message_feedback(message_id);
