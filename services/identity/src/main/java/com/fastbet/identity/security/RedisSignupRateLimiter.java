package com.fastbet.identity.security;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import com.fastbet.identity.observability.RequestIdFilter;

import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.keys.KeyCommands;
import io.quarkus.redis.datasource.value.ValueCommands;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Redis-backed fixed-window rate limiter for {@code POST /signup}.
 *
 * <p>Algorithm (mirrors {@code gateway/src/rate_limit.rs::RedisLimiter}):
 * <ol>
 *   <li>{@code INCR ratelimit:identity:signup:<ip>}</li>
 *   <li>If the returned count is {@code 1}, set {@code EXPIRE} with the window</li>
 *   <li>If the count exceeds the configured limit, deny; compute
 *       {@code Retry-After} from {@code PTTL} (clamped to ≥ 1 second)</li>
 * </ol>
 *
 * <p>Fail-open: any Redis error allows the request, increments
 * {@code identity.signup.ratelimit.errors}, and logs a warning. The gateway's
 * own limiter remains the primary defense for traffic flowing through it.
 */
@ApplicationScoped
public class RedisSignupRateLimiter implements SignupRateLimiter {

    private static final Logger LOG = Logger.getLogger(RedisSignupRateLimiter.class);
    public static final String KEY_PREFIX = "ratelimit:identity:signup:";
    public static final String COUNTER_ERRORS = "identity.signup.ratelimit.errors";

    @Inject
    RedisDataSource redis;

    @Inject
    MeterRegistry registry;

    @Inject
    @ConfigProperty(name = "fastbet.identity.signup.rate-limit.requests", defaultValue = "5")
    long limit;

    @Inject
    @ConfigProperty(name = "fastbet.identity.signup.rate-limit.window-seconds", defaultValue = "60")
    long windowSeconds;

    private ValueCommands<String, String> values;
    private KeyCommands<String> keys;

    public RedisSignupRateLimiter() {
        // CDI no-arg ctor
    }

    /** Test-only constructor. */
    RedisSignupRateLimiter(
            RedisDataSource redis,
            MeterRegistry registry,
            long limit,
            long windowSeconds) {
        this.redis = redis;
        this.registry = registry;
        this.limit = limit;
        this.windowSeconds = windowSeconds;
    }

    @PostConstruct
    void init() {
        this.values = redis.value(String.class);
        this.keys = redis.key();
    }

    @Override
    public Decision check(String ip) {
        if (limit <= 0 || windowSeconds <= 0) {
            return Decision.allow();
        }
        String key = KEY_PREFIX + ip;
        try {
            long count = values.incr(key);
            if (count == 1L) {
                keys.expire(key, windowSeconds);
            }
            if (count <= limit) {
                return Decision.allow();
            }
            long retry = retryAfterSeconds(key);
            return new Decision(false, retry);
        } catch (RuntimeException e) {
            String requestId = currentRequestId();
            LOG.warnf(e, "signup rate-limit failed open (request_id=%s, key=%s): %s",
                    requestId == null ? "-" : requestId, key, e.getMessage());
            registry.counter(COUNTER_ERRORS).increment();
            return Decision.allow();
        }
    }

    private long retryAfterSeconds(String key) {
        try {
            long pttl = keys.pttl(key);
            if (pttl <= 0) {
                return windowSeconds;
            }
            long secs = (pttl + 999) / 1000;
            return Math.max(secs, 1L);
        } catch (RuntimeException e) {
            return windowSeconds;
        }
    }

    private static String currentRequestId() {
        Object v = org.jboss.logging.MDC.get(RequestIdFilter.MDC_KEY);
        return v == null ? null : v.toString();
    }
}
