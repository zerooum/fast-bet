-- V1__user_account.sql
--
-- Identity service: first migration. Creates the `user_account` table that
-- backs public signup and (later) login. Email is stored as CITEXT so
-- uniqueness is case-insensitive without app-side normalization being the
-- only safeguard. Roles are a TEXT[] mirroring the JWT `roles` claim shape;
-- a CHECK constraint pins them to the canonical set.
--
-- Required extensions:
--   * pgcrypto — for gen_random_uuid()
--   * citext   — for case-insensitive email column

CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS citext;

CREATE TABLE user_account (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    email         CITEXT       NOT NULL UNIQUE,
    display_name  TEXT         NOT NULL,
    password_hash TEXT         NOT NULL,
    roles         TEXT[]       NOT NULL DEFAULT ARRAY['USER']::TEXT[],
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT user_account_roles_nonempty CHECK (array_length(roles, 1) >= 1),
    CONSTRAINT user_account_roles_canonical CHECK (
        roles <@ ARRAY['USER','SCHEDULER','ODD_MAKER','ADMIN']::TEXT[]
    )
);
