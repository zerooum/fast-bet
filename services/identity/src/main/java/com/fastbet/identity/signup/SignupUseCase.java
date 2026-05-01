package com.fastbet.identity.signup;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

import org.eclipse.microprofile.faulttolerance.Bulkhead;

import com.fastbet.identity.domain.Role;
import com.fastbet.identity.domain.UserAccount;
import com.fastbet.identity.domain.UserAccountRepository;
import com.fastbet.identity.observability.SignupMetrics;
import com.fastbet.identity.outbox.OutboxRepository;
import com.fastbet.identity.security.PasswordHasher;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/**
 * Pure application use-case for public signup.
 *
 * <p>Side-effects (DB write + outbox row) are wrapped in a single
 * {@code @Transactional} method so the {@code user_account} insert and the
 * {@code outbox} insert commit or roll back together.
 *
 * <p>The {@code @Bulkhead} caps how many Argon2 hashes can run in parallel
 * — Argon2id at the configured cost uses 64 MiB of RAM per call, so an
 * unbounded number of inflight signups would trivially OOM a small container.
 */
@ApplicationScoped
public class SignupUseCase {

    private final UserAccountRepository users;
    private final OutboxRepository outbox;
    private final PasswordHasher hasher;
    private final SignupMetrics metrics;

    @Inject
    public SignupUseCase(UserAccountRepository users,
                         OutboxRepository outbox,
                         PasswordHasher hasher,
                         SignupMetrics metrics) {
        this.users = users;
        this.outbox = outbox;
        this.hasher = hasher;
        this.metrics = metrics;
    }

    @Transactional
    @Bulkhead(value = 4, waitingTaskQueue = 8)
    public UserAccount signup(String rawEmail, String rawPassword, String rawDisplayName) {
        String email = normalizeEmail(rawEmail);
        String displayName = resolveDisplayName(rawDisplayName, email);

        if (users.existsByEmailIgnoreCase(email)) {
            metrics.attempt("conflict");
            throw new EmailAlreadyTakenException(email);
        }

        String passwordHash = hasher.hash(rawPassword);

        UserAccount account = new UserAccount();
        account.setEmail(email);
        account.setDisplayName(displayName);
        account.setPasswordHash(passwordHash);
        account.setRoles(new String[] { Role.USER.name() });
        users.persist(account);

        Instant registeredAt = account.getCreatedAt() != null ? account.getCreatedAt() : Instant.now();
        outbox.appendUserRegistered(account.getId(), email, displayName, registeredAt);

        metrics.attempt("ok");
        return account;
    }

    static String normalizeEmail(String raw) {
        if (raw == null) {
            return null;
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    static String resolveDisplayName(String raw, String normalizedEmail) {
        if (raw != null) {
            String trimmed = raw.trim();
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }
        if (normalizedEmail == null) {
            return null;
        }
        int at = normalizedEmail.indexOf('@');
        return at > 0 ? normalizedEmail.substring(0, at) : normalizedEmail;
    }

    /** Helper for callers / tests. */
    public static List<String> defaultRoles() {
        return List.of(Role.USER.name());
    }
}
