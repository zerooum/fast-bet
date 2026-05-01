package com.fastbet.identity.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fastbet.identity.domain.Role;
import com.fastbet.identity.domain.UserAccount;
import com.fastbet.identity.domain.UserAccountRepository;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.reactive.messaging.memory.InMemoryConnector;
import io.smallrye.reactive.messaging.memory.InMemorySink;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;

@QuarkusTest
class OutboxRelayIT {

    @Inject
    OutboxRepository outboxRepository;

    @Inject
    UserAccountRepository userRepository;

    @Inject
    @Any
    InMemoryConnector connector;

    @Test
    void unpublishedEventIsRelayedAndStamped() {
        InMemorySink<String> sink = connector.sink("user-registered");
        sink.clear();

        UUID[] outboxId = new UUID[1];
        QuarkusTransaction.requiringNew().run(() -> {
            UserAccount user = new UserAccount();
            user.setEmail("relay+" + System.nanoTime() + "@example.com");
            user.setDisplayName("Relay");
            user.setPasswordHash("$argon2id$stub");
            user.setRoles(new String[] { Role.USER.name() });
            userRepository.persist(user);

            OutboxEvent ev = outboxRepository.appendUserRegistered(
                    user.getId(),
                    user.getEmail(),
                    user.getDisplayName(),
                    Instant.now());
            outboxId[0] = ev.getId();
        });

        await().atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(500))
                .until(() -> {
                    if (sink.received().isEmpty()) {
                        return false;
                    }
                    Instant publishedAt = QuarkusTransaction.requiringNew().call(() -> {
                        OutboxEvent e = outboxRepository.findById(outboxId[0]);
                        return e == null ? null : e.getPublishedAt();
                    });
                    return publishedAt != null;
                });

        assertThat(sink.received()).isNotEmpty();
        String payload = sink.received().get(sink.received().size() - 1).getPayload();
        assertThat(payload).contains("identity.user-registered");
    }
}
