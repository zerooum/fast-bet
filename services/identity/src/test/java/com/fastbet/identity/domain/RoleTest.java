package com.fastbet.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

class RoleTest {

    @Test
    void canonicalSetMatchesJwtWhitelist() {
        var enumNames = Arrays.stream(Role.values())
                .map(Enum::name)
                .collect(Collectors.toSet());
        assertThat(enumNames)
                .as("Role enum and JWT whitelist must stay in lockstep")
                .containsExactlyInAnyOrderElementsOf(Role.JWT_WHITELIST);
    }
}
