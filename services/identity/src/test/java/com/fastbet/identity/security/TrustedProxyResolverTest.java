package com.fastbet.identity.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class TrustedProxyResolverTest {

    @Test
    void emptyAllowListAlwaysReturnsPeer() {
        TrustedProxyResolver r = new TrustedProxyResolver(List.of());
        assertThat(r.resolveClientIp("10.0.0.1", "1.2.3.4")).isEqualTo("10.0.0.1");
        assertThat(r.resolveClientIp("10.0.0.1", null)).isEqualTo("10.0.0.1");
    }

    @Test
    void untrustedPeerWithSpoofedXffStillReturnsPeer() {
        TrustedProxyResolver r = new TrustedProxyResolver(List.of("10.0.0.0/8"));
        assertThat(r.resolveClientIp("172.20.0.5", "1.2.3.4")).isEqualTo("172.20.0.5");
    }

    @Test
    void trustedPeerWithXffReturnsLeftmostEntry() {
        TrustedProxyResolver r = new TrustedProxyResolver(List.of("10.0.0.0/8"));
        assertThat(r.resolveClientIp("10.5.0.1", "203.0.113.7, 10.5.0.1"))
                .isEqualTo("203.0.113.7");
    }

    @Test
    void trustedPeerWithoutXffFallsBackToPeer() {
        TrustedProxyResolver r = new TrustedProxyResolver(List.of("10.0.0.0/8"));
        assertThat(r.resolveClientIp("10.5.0.1", null)).isEqualTo("10.5.0.1");
        assertThat(r.resolveClientIp("10.5.0.1", "")).isEqualTo("10.5.0.1");
    }

    @Test
    void malformedXffEntriesAreSkipped() {
        TrustedProxyResolver r = new TrustedProxyResolver(List.of("10.0.0.0/8"));
        assertThat(r.resolveClientIp("10.0.0.1", "  ,  , 198.51.100.1 , 10.0.0.1"))
                .isEqualTo("198.51.100.1");
    }

    @Test
    void ipv6CidrIsHonored() {
        TrustedProxyResolver r = new TrustedProxyResolver(List.of("2001:db8::/32"));
        assertThat(r.resolveClientIp("2001:db8:1::1", "203.0.113.7")).isEqualTo("203.0.113.7");
        assertThat(r.resolveClientIp("2001:dead::1", "203.0.113.7")).isEqualTo("2001:dead::1");
    }

    @Test
    void singleHostNoPrefixIsTreatedAsExactMatch() {
        TrustedProxyResolver r = new TrustedProxyResolver(List.of("172.20.0.5"));
        assertThat(r.resolveClientIp("172.20.0.5", "1.2.3.4")).isEqualTo("1.2.3.4");
        assertThat(r.resolveClientIp("172.20.0.6", "1.2.3.4")).isEqualTo("172.20.0.6");
    }

    @Test
    void malformedCidrEntryIsIgnoredButOthersStillWork() {
        TrustedProxyResolver r = new TrustedProxyResolver(List.of("not-a-cidr", "10.0.0.0/8"));
        assertThat(r.resolveClientIp("10.0.0.1", "1.2.3.4")).isEqualTo("1.2.3.4");
    }
}
