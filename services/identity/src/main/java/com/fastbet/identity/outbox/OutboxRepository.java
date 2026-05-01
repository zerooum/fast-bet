package com.fastbet.identity.outbox;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class OutboxRepository implements PanacheRepositoryBase<OutboxEvent, UUID> {

    public static final String EVENT_USER_REGISTERED = "identity.user-registered";

    private final ObjectMapper objectMapper;

    @Inject
    public OutboxRepository(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Append an {@code identity.user-registered} event in the current transaction.
     *
     * @return the created outbox event (already persisted)
     */
    public OutboxEvent appendUserRegistered(UUID userId, String email, String displayName, Instant at) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("eventType", EVENT_USER_REGISTERED);
        payload.put("version", 1);
        payload.put("userId", userId.toString());
        payload.put("email", email);
        payload.put("displayName", displayName);
        payload.put("registeredAt", at.toString());

        OutboxEvent ev = new OutboxEvent();
        ev.setId(UUID.randomUUID());
        ev.setAggregate("user_account");
        ev.setAggregateId(userId.toString());
        ev.setEventType(EVENT_USER_REGISTERED);
        try {
            ev.setPayload(objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            // ObjectNode → String never fails in practice; rethrow to surface bugs loudly.
            throw new IllegalStateException("failed to serialize outbox payload", e);
        }
        ev.setOccurredAt(at);
        persist(ev);
        return ev;
    }

    /** Read up to {@code limit} unpublished events ordered by {@code occurred_at}. */
    public List<OutboxEvent> findUnpublished(int limit) {
        return find("publishedAt IS NULL ORDER BY occurredAt ASC")
                .page(0, limit)
                .list();
    }
}
