package com.fastbet.identity.security;

import de.mkammerer.argon2.Argon2;
import de.mkammerer.argon2.Argon2Factory;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Argon2id password hasher.
 *
 * <p>Parameters: {@code memory=65536 KiB (64 MiB)}, {@code iterations=3},
 * {@code parallelism=1}, {@code saltLength=16}, {@code hashLength=32} —
 * matching the OWASP-recommended baseline and the design document.
 *
 * <p>The {@code argon2-jvm} library zeroes the raw password buffer in
 * {@link Argon2#wipeArray(char[])}; we follow that pattern here.
 */
@ApplicationScoped
public class Argon2idPasswordHasher implements PasswordHasher {

    static final int SALT_LENGTH_BYTES = 16;
    static final int HASH_LENGTH_BYTES = 32;
    static final int ITERATIONS = 3;
    static final int MEMORY_KIB = 65_536;
    static final int PARALLELISM = 1;

    private final Argon2 argon2 =
            Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id, SALT_LENGTH_BYTES, HASH_LENGTH_BYTES);

    @Override
    public String hash(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("raw password must not be null");
        }
        char[] buffer = raw.toCharArray();
        try {
            return argon2.hash(ITERATIONS, MEMORY_KIB, PARALLELISM, buffer);
        } finally {
            argon2.wipeArray(buffer);
        }
    }

    @Override
    public boolean verify(String raw, String stored) {
        if (raw == null || stored == null) {
            return false;
        }
        char[] buffer = raw.toCharArray();
        try {
            return argon2.verify(stored, buffer);
        } finally {
            argon2.wipeArray(buffer);
        }
    }
}
