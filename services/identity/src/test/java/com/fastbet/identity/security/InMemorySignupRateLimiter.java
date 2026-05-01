package com.fastbet.identity.security;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Process-local fixed-window {@link SignupRateLimiter} for tests.
 *
 * <p>Mirrors the semantics of {@link RedisSignupRateLimiter} without an
 * external dependency. Used by tests that want deterministic behavior or
 * that need to drive the limiter independently of Redis.
 */
public class InMemorySignupRateLimiter implements SignupRateLimiter {

    private final long limit;
    private final Duration window;
    private final Supplier<Instant> clock;
    private final Map<String, WindowState> state = new HashMap<>();

    public InMemorySignupRateLimiter(long limit, Duration window) {
        this(limit, window, Instant::now);
    }

    InMemorySignupRateLimiter(long limit, Duration window, Supplier<Instant> clock) {
        this.limit = limit;
        this.window = window;
        this.clock = clock;
    }

    @Override
    public synchronized Decision check(String key) {
        if (limit <= 0 || window.isZero() || window.isNegative()) {
            return Decision.allow();
        }
        Instant now = clock.get();
        WindowState entry = state.computeIfAbsent(key, k -> new WindowState(now));
        if (Duration.between(entry.startedAt, now).compareTo(window) >= 0) {
            entry.startedAt = now;
            entry.count = 0;
        }
        entry.count++;
        long elapsedSecs = Duration.between(entry.startedAt, now).getSeconds();
        long retryAfter = Math.max(window.getSeconds() - elapsedSecs, 1L);
        return new Decision(entry.count <= limit, retryAfter);
    }

    public synchronized void reset() {
        state.clear();
    }

    private static final class WindowState {
        Instant startedAt;
        long count;

        WindowState(Instant startedAt) {
            this.startedAt = startedAt;
            this.count = 0L;
        }
    }
}
