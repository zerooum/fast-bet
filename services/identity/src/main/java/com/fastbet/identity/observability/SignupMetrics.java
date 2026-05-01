package com.fastbet.identity.observability;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Micrometer meters for signup. Tags follow Prometheus conventions.
 */
@ApplicationScoped
public class SignupMetrics {

    public static final String COUNTER_ATTEMPTS = "identity.signup.attempts";

    private final MeterRegistry registry;

    @Inject
    public SignupMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** Increment the attempts counter for the given outcome (ok|conflict|invalid|error). */
    public void attempt(String outcome) {
        registry.counter(COUNTER_ATTEMPTS, "outcome", outcome).increment();
    }
}
