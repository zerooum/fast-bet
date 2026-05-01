package com.fastbet.identity.outbox;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.jboss.logging.Logger;

import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Polls the {@code outbox} table every 5s and publishes pending events to
 * the {@code identity.user-registered} Kafka topic.
 *
 * <p>Failures are logged and counted; the row is left unpublished and will
 * be retried on the next tick. The relay never deletes rows — this keeps
 * the table auditable and lets us replay events if a downstream consumer
 * needs to backfill.
 */
@ApplicationScoped
public class OutboxRelay {

    private static final Logger LOG = Logger.getLogger(OutboxRelay.class);

    public static final String METRIC_PUBLISHED = "identity.outbox.published";
    public static final String METRIC_FAILED = "identity.outbox.failed";

    private final OutboxRepository repository;
    private final Emitter<String> emitter;
    private final MeterRegistry registry;
    private final int batchSize;

    @Inject
    public OutboxRelay(
            OutboxRepository repository,
            @Channel("user-registered") Emitter<String> emitter,
            MeterRegistry registry,
            @ConfigProperty(name = "fastbet.identity.outbox.batch-size", defaultValue = "50") int batchSize) {
        this.repository = repository;
        this.emitter = emitter;
        this.registry = registry;
        this.batchSize = batchSize;
    }

    @Scheduled(every = "5s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void drain() {
        // QuarkusTransaction.requiringNew() is used here (and below) rather than
        // @Transactional methods on this bean, because CDI @Transactional
        // is interceptor-based and self-invocation bypasses the proxy.
        List<OutboxEvent> pending = QuarkusTransaction.requiringNew()
                .call(() -> repository.findUnpublished(batchSize));
        if (pending.isEmpty()) {
            return;
        }
        for (OutboxEvent ev : pending) {
            try {
                emitter.send(ev.getPayload())
                        .toCompletableFuture()
                        .orTimeout(10, TimeUnit.SECONDS)
                        .join();
            } catch (RuntimeException e) {
                registry.counter(METRIC_FAILED, "event", ev.getEventType(), "stage", "kafka").increment();
                LOG.warnf(e, "outbox publish to kafka failed event_id=%s event_type=%s",
                        ev.getId(), ev.getEventType());
                continue;
            }

            UUID eventId = ev.getId();
            Instant publishedAt = Instant.now();
            try {
                QuarkusTransaction.requiringNew().run(() -> {
                    OutboxEvent attached = repository.findById(eventId);
                    if (attached != null) {
                        attached.setPublishedAt(publishedAt);
                    }
                });
                registry.counter(METRIC_PUBLISHED, "event", ev.getEventType()).increment();
            } catch (RuntimeException e) {
                registry.counter(METRIC_FAILED, "event", ev.getEventType(), "stage", "db").increment();
                LOG.errorf(e, "outbox mark-published failed (event already on kafka, will be republished) "
                        + "event_id=%s event_type=%s", ev.getId(), ev.getEventType());
            }
        }
    }
}
