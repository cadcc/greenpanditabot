
CREATE TABLE google_refresh_tokens(
    telegram_id BIGINT PRIMARY KEY,
    refresh_token TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE notify_integrations(
    id SERIAL NOT NULL PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    chat_id BIGINT NOT NULL,
    topic_id INTEGER,
    cron VARCHAR(100),
    forms_id VARCHAR(60) NOT NULL,
    checkpoint TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
