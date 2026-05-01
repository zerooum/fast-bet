package com.fastbet.identity.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class Argon2idPasswordHasherTest {

    private final Argon2idPasswordHasher hasher = new Argon2idPasswordHasher();

    @Test
    void hashStartsWithArgon2idPrefix() {
        String hash = hasher.hash("CorrectHorseBattery9");
        assertThat(hash).startsWith("$argon2id$");
    }

    @Test
    void verifyAcceptsOriginalAndRejectsAltered() {
        String raw = "CorrectHorseBattery9";
        String hash = hasher.hash(raw);
        assertThat(hasher.verify(raw, hash)).isTrue();
        assertThat(hasher.verify(raw + "x", hash)).isFalse();
        assertThat(hasher.verify("CorrectHorseBattery8", hash)).isFalse();
    }

    @Test
    void twoHashesOfSameRawDifferDueToRandomSalt() {
        String raw = "CorrectHorseBattery9";
        String a = hasher.hash(raw);
        String b = hasher.hash(raw);
        assertThat(a).isNotEqualTo(b);
        assertThat(hasher.verify(raw, a)).isTrue();
        assertThat(hasher.verify(raw, b)).isTrue();
    }

    @Test
    void verifyReturnsFalseOnNullArgs() {
        assertThat(hasher.verify(null, "x")).isFalse();
        assertThat(hasher.verify("x", null)).isFalse();
        assertThat(hasher.verify(null, null)).isFalse();
    }
}
