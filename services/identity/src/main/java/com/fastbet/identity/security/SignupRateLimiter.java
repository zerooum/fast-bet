package com.fastbet.identity.security;

/**
 * Per-key fixed-window rate limiter for the public signup endpoint.
 *
 * <p>Mirrors the shape of {@code gateway/src/rate_limit.rs::Decision} so the
 * two layers behave identically (both return a "should-allow" boolean and a
 * suggested {@code Retry-After} in seconds).
 */
public interface SignupRateLimiter {

    /** Record one hit against {@code key} and return whether it is allowed. */
    Decision check(String key);

    /** Decision returned by a rate limiter. */
    final class Decision {
        public final boolean allowed;
        public final long retryAfterSeconds;

        public Decision(boolean allowed, long retryAfterSeconds) {
            this.allowed = allowed;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public static Decision allow() {
            return new Decision(true, 0L);
        }
    }
}
