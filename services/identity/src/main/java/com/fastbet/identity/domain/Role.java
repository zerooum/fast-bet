package com.fastbet.identity.domain;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Canonical Fast-Bet roles.
 *
 * <p>The set declared here is the source of truth for role names that may
 * appear in {@code user_account.roles} and the JWT {@code roles} claim. Any
 * change here MUST be matched by the DB CHECK constraint in
 * {@code V1__user_account.sql} and by the JWT issuer's whitelist
 * ({@link #JWT_WHITELIST}).
 */
public enum Role {
    USER,
    SCHEDULER,
    ODD_MAKER,
    ADMIN;

    /**
     * Whitelist of role names that the JWT issuer accepts. Kept as a separate
     * constant so a unit test can pin it against {@link #values()} and fail
     * loudly if the two ever drift.
     */
    public static final List<String> JWT_WHITELIST = Collections.unmodifiableList(
            Arrays.asList("USER", "SCHEDULER", "ODD_MAKER", "ADMIN"));
}
