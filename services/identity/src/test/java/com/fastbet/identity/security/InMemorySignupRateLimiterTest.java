package com.fastbet.identity.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class InMemorySignupRateLimiterTest {

    @Test
    void allowsUpToLimitThenRejects() {
        SignupRateLimiter limiter = new InMemorySignupRateLimiter(5, Duration.ofSeconds(60));
        for (int i = 1; i <= 5; i++) {
            assertThat(limiter.check("1.2.3.4").allowed)
                    .as("call %d should be allowed", i)
                    .isTrue();
        }
        SignupRateLimiter.Decision sixth = limiter.check("1.2.3.4");
        assertThat(sixth.allowed).isFalse();
        assertThat(sixth.retryAfterSeconds).isGreaterThanOrEqualTo(1L);
    }

    @Test
    void countersAreScopedPerKey() {
        SignupRateLimiter limiter = new InMemorySignupRateLimiter(2, Duration.ofSeconds(60));
        assertThat(limiter.check("a").allowed).isTrue();
        assertThat(limiter.check("a").allowed).isTrue();
        assertThat(limiter.check("a").allowed).isFalse();
        assertThat(limiter.check("b").allowed).isTrue();
    }

    @Test
    void zeroLimitDisablesLimiter() {
        SignupRateLimiter limiter = new InMemorySignupRateLimiter(0, Duration.ofSeconds(60));
        for (int i = 0; i < 100; i++) {
            assertThat(limiter.check("x").allowed).isTrue();
        }
    }

    @Test
    void windowResetsAfterDuration() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.EPOCH);
        InMemorySignupRateLimiter limiter =
                new InMemorySignupRateLimiter(1, Duration.ofSeconds(1), now::get);
        assertThat(limiter.check("k").allowed).isTrue();
        assertThat(limiter.check("k").allowed).isFalse();
        // Advance clock past the window boundary — no sleep required.
        now.set(Instant.EPOCH.plusSeconds(2));
        assertThat(limiter.check("k").allowed).isTrue();
    }
}
