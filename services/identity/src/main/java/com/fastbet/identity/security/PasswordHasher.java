package com.fastbet.identity.security;

/**
 * Port for password hashing. The default adapter is {@link Argon2idPasswordHasher}.
 *
 * <p>Implementations MUST embed all parameters in the stored value (e.g. the
 * Argon2 PHC string) so {@link #verify(String, String)} can re-derive them
 * without consulting external configuration.
 */
public interface PasswordHasher {

    /** Hash a raw password and return the encoded representation suitable for storage. */
    String hash(String raw);

    /** Verify a raw password against a previously hashed/encoded value. */
    boolean verify(String raw, String stored);
}
