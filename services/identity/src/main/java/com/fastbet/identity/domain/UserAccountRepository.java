package com.fastbet.identity.domain;

import java.util.Optional;
import java.util.UUID;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class UserAccountRepository implements PanacheRepositoryBase<UserAccount, UUID> {

    /** Case-insensitive lookup. The column is {@code CITEXT}, so {@code =} is enough. */
    public Optional<UserAccount> findByEmailIgnoreCase(String email) {
        if (email == null) {
            return Optional.empty();
        }
        return find("email", email.trim()).firstResultOptional();
    }

    public boolean existsByEmailIgnoreCase(String email) {
        if (email == null) {
            return false;
        }
        return count("email", email.trim()) > 0;
    }
}
