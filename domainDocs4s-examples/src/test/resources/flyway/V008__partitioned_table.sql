CREATE TABLE IF NOT EXISTS daily_snapshots
(
    user_id TEXT        NOT NULL,
    day     DATE        NOT NULL,
    balance TEXT        NOT NULL,
    PRIMARY KEY (user_id, day)
) PARTITION BY RANGE (day);

CREATE TABLE events
(
    id      BIGINT PRIMARY KEY,
    kind    TEXT NOT NULL,
    payload TEXT
) PARTITION BY LIST (kind);

CREATE TABLE metrics
(
    id    BIGINT PRIMARY KEY,
    value DOUBLE PRECISION NOT NULL
) PARTITION BY HASH (id);
