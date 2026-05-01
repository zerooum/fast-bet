-- V2__outbox.sql
--
-- Local outbox table for the identity service. Every state change that must
-- be observable by other services (e.g., user-registered) is written here in
-- the same DB transaction; a relay process publishes pending rows to Kafka
-- and stamps `published_at` after a successful broker ack.
--
-- The partial index gives the relay an O(log n) lookup of unpublished rows
-- without scanning the whole table once it grows.

CREATE TABLE outbox (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate     TEXT         NOT NULL,
    aggregate_id  TEXT         NOT NULL,
    event_type    TEXT         NOT NULL,
    payload       JSONB        NOT NULL,
    occurred_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ
);

CREATE INDEX outbox_unpublished_idx
    ON outbox (occurred_at)
    WHERE published_at IS NULL;
