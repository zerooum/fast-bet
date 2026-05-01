package com.fastbet.identity.security;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;

/**
 * Integration tests for the public-endpoint guard + per-IP rate limit on
 * /signup. The %test profile trusts loopback (127.0.0.0/8) so we can drive
 * client-IP resolution deterministically via X-Forwarded-For.
 */
@QuarkusTest
@TestProfile(SignupHardeningIT.HardeningProfile.class)
class SignupHardeningIT {

    // Must match %test.fastbet.identity.edge-token in application.properties.
    private static final String EDGE_TOKEN = "test-edge-secret";

    // Counter seeded from nanotime so successive JVM runs land in different
    // parts of the 198.51.100-x space, avoiding collisions with Redis keys
    // that survive from a previous run within the rate-limit window.
    private static final AtomicInteger IP_SEQ =
            new AtomicInteger((int) (System.nanoTime() & 0xFFFF));

    private static String nextTestIp() {
        int seq = IP_SEQ.getAndIncrement() & 0xFFFF;
        return "198.51." + (100 + (seq >> 8)) + "." + (seq & 0xFF);
    }

    public static class HardeningProfile implements QuarkusTestProfile {
        @Override
        public java.util.Map<String, String> getConfigOverrides() {
            return java.util.Map.of(
                    "fastbet.identity.signup.rate-limit.requests", "5",
                    "fastbet.identity.signup.rate-limit.window-seconds", "60");
        }
    }

    @Test
    void authorizationHeaderOnSignupReturns400() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer something")
                .body(validBody("auth-hdr"))
                .when().post("/signup")
                .then()
                .statusCode(400)
                .body("error", equalTo("forbidden_header"))
                .body("header", equalTo("Authorization"))
                .header("X-Request-Id", notNullValue());
    }

    @Test
    void xUserIdOnSignupReturns400CaseInsensitive() {
        given()
                .contentType(ContentType.JSON)
                .header("x-user-id", "00000000-0000-0000-0000-000000000001")
                .body(validBody("xuid"))
                .when().post("/signup")
                .then()
                .statusCode(400)
                .body("error", equalTo("forbidden_header"))
                .body("header", equalTo("X-User-Id"));
    }

    @Test
    void xUserRolesOnSignupReturns400() {
        given()
                .contentType(ContentType.JSON)
                .header("X-User-Roles", "ADMIN")
                .body(validBody("xur"))
                .when().post("/signup")
                .then()
                .statusCode(400)
                .body("error", equalTo("forbidden_header"))
                .body("header", equalTo("X-User-Roles"));
    }

    @Test
    void sixthSignupFromSameIpReturns429WithRetryAfter() {
        // Each invocation uses a fresh IP bucket so re-runs and parallel
        // executions can't collide on the same Redis key.
        String ip = nextTestIp();
        for (int i = 0; i < 5; i++) {
            given()
                    .contentType(ContentType.JSON)
                    .header("X-Edge-Token", EDGE_TOKEN)
                    .header("X-Forwarded-For", ip)
                    .body(validBody("rl-ok-" + i + "-" + uniq()))
                    .when().post("/signup")
                    .then()
                    .statusCode(201);
        }

        given()
                .contentType(ContentType.JSON)
                .header("X-Edge-Token", EDGE_TOKEN)
                .header("X-Forwarded-For", ip)
                .body(validBody("rl-blocked-" + uniq()))
                .when().post("/signup")
                .then()
                .statusCode(429)
                .body("error", equalTo("rate_limited"))
                .header("Retry-After", notNullValue());
    }

    @Test
    void untrustedProxyXffIsIgnoredForKey() {
        // Both requests share the loopback peer (trusted). Sending two
        // distinct XFF IPs gives two separate buckets, so 5 successes per
        // bucket are allowed independently. This indirectly proves the
        // resolver consults XFF only when the peer is trusted -- which
        // it is here. To prove the negative case (untrusted peer), see
        // TrustedProxyResolverTest.untrustedPeerIgnoresSpoofedXff.
        String ipA = nextTestIp();
        String ipB = nextTestIp();
        for (int i = 0; i < 5; i++) {
            given().contentType(ContentType.JSON)
                    .header("X-Edge-Token", EDGE_TOKEN)
                    .header("X-Forwarded-For", ipA)
                    .body(validBody("u-a-" + i + "-" + uniq()))
                    .when().post("/signup").then().statusCode(201);
        }
        // 6th from ipA is blocked.
        given().contentType(ContentType.JSON)
                .header("X-Edge-Token", EDGE_TOKEN)
                .header("X-Forwarded-For", ipA)
                .body(validBody("u-a-block-" + uniq()))
                .when().post("/signup").then().statusCode(429);
        // But ipB still has its own budget.
        given().contentType(ContentType.JSON)
                .header("X-Edge-Token", EDGE_TOKEN)
                .header("X-Forwarded-For", ipB)
                .body(validBody("u-b-" + uniq()))
                .when().post("/signup").then().statusCode(201);
    }

    private static Map<String, Object> validBody(String tag) {
        return Map.of(
                "email", tag + "." + uniq() + "@example.com",
                "password", "Password1234");
    }

    private static String uniq() {
        return Long.toHexString(System.nanoTime());
    }
}
