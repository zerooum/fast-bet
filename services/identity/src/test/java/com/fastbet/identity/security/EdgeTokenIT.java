package com.fastbet.identity.security;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

/**
 * Integration tests for the {@link EdgeTokenFilter} edge-token requirement
 * on Identity business API paths.
 *
 * <p>The %test profile configures {@code fastbet.identity.edge-token=test-edge-secret}
 * so tests can target the expected behavior without a real env var.
 *
 * <p>Filter ordering is also verified: the existing
 * {@link PublicEndpointGuardFilter} fires at priority 900 (before
 * EdgeTokenFilter at priority 1000), so forbidden-header rejections remain
 * {@code 400} even when the edge token is absent.
 */
@QuarkusTest
class EdgeTokenIT {

    // Must match %test.fastbet.identity.edge-token in application.properties.
    private static final String EDGE_TOKEN = "test-edge-secret";

    // --- 3.2: missing edge token → 401 ---

    @Test
    void missingEdgeTokenOnSignupReturns401() {
        given()
                .contentType(ContentType.JSON)
                .body(validBody("no-token"))
                .when().post("/signup")
                .then()
                .statusCode(401)
                .body("error", equalTo("edge_unauthorized"))
                .header("X-Request-Id", notNullValue());
    }

    // --- 3.3: wrong edge token → 401 ---

    @Test
    void wrongEdgeTokenOnSignupReturns401() {
        given()
                .contentType(ContentType.JSON)
                .header("X-Edge-Token", "attacker-value")
                .body(validBody("wrong-token"))
                .when().post("/signup")
                .then()
                .statusCode(401)
                .body("error", equalTo("edge_unauthorized"));
    }

    // --- 3.4: valid edge token → signup flow proceeds ---

    @Test
    void validEdgeTokenAllowsSignupToComplete() {
        given()
                .contentType(ContentType.JSON)
                .header("X-Edge-Token", EDGE_TOKEN)
                .body(validBody("valid-token"))
                .when().post("/signup")
                .then()
                .statusCode(201)
                .header("Location", notNullValue())
                .body("id", notNullValue());
    }

    // --- 3.5: forbidden headers still return 400 even without edge token ---

    @Test
    void authorizationHeaderReturnsForbiddenHeaderEvenWithoutEdgeToken() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer spoofed")
                .body(validBody("auth-no-edge"))
                .when().post("/signup")
                .then()
                .statusCode(400)
                .body("error", equalTo("forbidden_header"))
                .body("header", equalTo("Authorization"));
    }

    @Test
    void xUserIdHeaderReturnsForbiddenHeaderEvenWithoutEdgeToken() {
        given()
                .contentType(ContentType.JSON)
                .header("X-User-Id", "00000000-0000-0000-0000-000000000001")
                .body(validBody("xuid-no-edge"))
                .when().post("/signup")
                .then()
                .statusCode(400)
                .body("error", equalTo("forbidden_header"))
                .body("header", equalTo("X-User-Id"));
    }

    @Test
    void xUserRolesHeaderReturnsForbiddenHeaderEvenWithoutEdgeToken() {
        given()
                .contentType(ContentType.JSON)
                .header("X-User-Roles", "ADMIN")
                .body(validBody("xur-no-edge"))
                .when().post("/signup")
                .then()
                .statusCode(400)
                .body("error", equalTo("forbidden_header"))
                .body("header", equalTo("X-User-Roles"));
    }

    // --- Health and metrics remain accessible without edge token ---

    @Test
    void healthReadyIsAccessibleWithoutEdgeToken() {
        given()
                .when().get("/q/health/ready")
                .then()
                .statusCode(200);
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
