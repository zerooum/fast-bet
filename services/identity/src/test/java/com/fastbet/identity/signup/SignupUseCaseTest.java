package com.fastbet.identity.signup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fastbet.identity.domain.UserAccount;
import com.fastbet.identity.domain.UserAccountRepository;
import com.fastbet.identity.observability.SignupMetrics;
import com.fastbet.identity.outbox.OutboxRepository;
import com.fastbet.identity.security.PasswordHasher;

class SignupUseCaseTest {

    private UserAccountRepository users;
    private OutboxRepository outbox;
    private PasswordHasher hasher;
    private SignupMetrics metrics;
    private SignupUseCase useCase;

    @BeforeEach
    void setUp() {
        users = org.mockito.Mockito.mock(UserAccountRepository.class);
        outbox = org.mockito.Mockito.mock(OutboxRepository.class);
        hasher = org.mockito.Mockito.mock(PasswordHasher.class);
        metrics = org.mockito.Mockito.mock(SignupMetrics.class);
        useCase = new SignupUseCase(users, outbox, hasher, metrics);
    }

    @Test
    void happyPathHashesPasswordPersistsAndAppendsOutbox() {
        when(users.existsByEmailIgnoreCase("alice@example.com")).thenReturn(false);
        when(hasher.hash("Password1234")).thenReturn("$argon2id$stub");

        // Simulate JPA assigning an id on persist.
        org.mockito.Mockito.doAnswer(inv -> {
            UserAccount a = inv.getArgument(0);
            if (a.getId() == null) {
                a.setId(UUID.randomUUID());
            }
            if (a.getCreatedAt() == null) {
                a.setCreatedAt(Instant.now());
            }
            return null;
        }).when(users).persist(any(UserAccount.class));

        UserAccount created = useCase.signup("  ALICE@example.com ", "Password1234", "Alice");

        assertThat(created.getEmail()).isEqualTo("alice@example.com");
        assertThat(created.getDisplayName()).isEqualTo("Alice");
        assertThat(created.getPasswordHash()).isEqualTo("$argon2id$stub");
        assertThat(created.getRoles()).containsExactly("USER");

        ArgumentCaptor<UUID> idCap = ArgumentCaptor.forClass(UUID.class);
        verify(outbox).appendUserRegistered(idCap.capture(), eq("alice@example.com"), eq("Alice"), any(Instant.class));
        assertThat(idCap.getValue()).isEqualTo(created.getId());
        verify(metrics).attempt("ok");
    }

    @Test
    void duplicateEmailIsCaseInsensitiveAndDoesNotPersist() {
        when(users.existsByEmailIgnoreCase("alice@example.com")).thenReturn(true);

        assertThatThrownBy(() -> useCase.signup("Alice@Example.COM", "Password1234", null))
                .isInstanceOf(EmailAlreadyTakenException.class);

        verify(hasher, never()).hash(anyString());
        verify(users, never()).persist(any(UserAccount.class));
        verify(outbox, never()).appendUserRegistered(any(), anyString(), anyString(), any());
        verify(metrics).attempt("conflict");
    }

    @Test
    void displayNameDefaultsToEmailLocalPartWhenBlank() {
        when(users.existsByEmailIgnoreCase("bob@example.com")).thenReturn(false);
        when(hasher.hash(anyString())).thenReturn("$argon2id$stub");
        org.mockito.Mockito.doAnswer(inv -> {
            UserAccount a = inv.getArgument(0);
            if (a.getId() == null) a.setId(UUID.randomUUID());
            if (a.getCreatedAt() == null) a.setCreatedAt(Instant.now());
            return null;
        }).when(users).persist(any(UserAccount.class));

        UserAccount created = useCase.signup("bob@example.com", "Password1234", "   ");
        assertThat(created.getDisplayName()).isEqualTo("bob");
    }

    @Test
    void rolesArrayIsAlwaysExactlyUserRegardlessOfInputs() {
        when(users.existsByEmailIgnoreCase(anyString())).thenReturn(false);
        when(hasher.hash(anyString())).thenReturn("$argon2id$stub");
        org.mockito.Mockito.doAnswer(inv -> {
            UserAccount a = inv.getArgument(0);
            if (a.getId() == null) a.setId(UUID.randomUUID());
            if (a.getCreatedAt() == null) a.setCreatedAt(Instant.now());
            return null;
        }).when(users).persist(any(UserAccount.class));

        UserAccount created = useCase.signup("c@example.com", "Password1234", "C");
        assertThat(created.getRoles()).containsExactly("USER");
    }

    @Test
    void normalizationStripsAndLowercases() {
        assertThat(SignupUseCase.normalizeEmail("  ALICE@example.com  "))
                .isEqualTo("alice@example.com");
    }
}
