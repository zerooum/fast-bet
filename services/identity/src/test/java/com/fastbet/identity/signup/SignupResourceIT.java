package com.fastbet.identity.signup;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

@QuarkusTest
class SignupResourceIT {

    // Must match %test.fastbet.identity.edge-token in application.properties.
    private static final String EDGE_TOKEN = "test-edge-secret";

    @Test
    void successfulSignupReturns201WithSanitizedBodyAndLocation() {
        Map<String, Object> body = Map.of(
                "email", "alice." + uniq() + "@example.com",
                "password", "Password1234",
                "displayName", "Alice");

        given()
                .contentType(ContentType.JSON)
                .header("X-Edge-Token", EDGE_TOKEN)
                .body(body)
                .when()
                .post("/signup")
                .then()
                .statusCode(201)
                .header("Location", startsWith("/users/"))
                .body("id", notNullValue())
                .body("email", equalTo(body.get("email")))
                .body("displayName", equalTo("Alice"))
                .body("roles", equalTo(java.util.List.of("USER")))
                .body("createdAt", notNullValue())
                .body("$", not(hasKey("passwordHash")))
                .body("$", not(hasKey("password")));
    }

    @Test
    void duplicateEmailReturns409EmailTaken() {
        String email = "dup." + uniq() + "@example.com";
        Map<String, Object> body = Map.of("email", email, "password", "Password1234");

        given().contentType(ContentType.JSON).header("X-Edge-Token", EDGE_TOKEN).body(body)
                .when().post("/signup").then().statusCode(201);

        Map<String, Object> body2 = Map.of(
                "email", email.toUpperCase(),
                "password", "Password1234");

        given().contentType(ContentType.JSON).header("X-Edge-Token", EDGE_TOKEN).body(body2)
                .when().post("/signup")
                .then().statusCode(409)
                .body("error", equalTo("email_taken"));
    }

    @Test
    void invalidEmailReturns400Validation() {
        Map<String, Object> body = Map.of(
                "email", "not-an-email",
                "password", "Password1234");

        given().contentType(ContentType.JSON).header("X-Edge-Token", EDGE_TOKEN).body(body)
                .when().post("/signup")
                .then().statusCode(400)
                .body("error", equalTo("validation"))
                .body("fields.email", notNullValue());
    }

    @Test
    void weakPasswordReturns400Validation() {
        Map<String, Object> body = Map.of(
                "email", "weak." + uniq() + "@example.com",
                "password", "short");

        given().contentType(ContentType.JSON).header("X-Edge-Token", EDGE_TOKEN).body(body)
                .when().post("/signup")
                .then().statusCode(400)
                .body("error", equalTo("validation"))
                .body("fields.password", notNullValue());
    }

    @Test
    void roleFieldInPayloadIsSilentlyDropped() {
        String email = "esc." + uniq() + "@example.com";
        String json = "{\"email\":\"" + email + "\","
                + "\"password\":\"Password1234\","
                + "\"displayName\":\"Esc\","
                + "\"roles\":[\"ADMIN\"],"
                + "\"role\":\"ADMIN\"}";

        given().contentType(ContentType.JSON).header("X-Edge-Token", EDGE_TOKEN).body(json)
                .when().post("/signup")
                .then().statusCode(201)
                .body("roles", equalTo(java.util.List.of("USER")));
    }

    @Test
    void responseBodyNeverContainsPasswordHash() {
        String email = "leak." + uniq() + "@example.com";
        Map<String, Object> body = Map.of(
                "email", email,
                "password", "Password1234");

        String response = given().contentType(ContentType.JSON).header("X-Edge-Token", EDGE_TOKEN).body(body)
                .when().post("/signup")
                .then().statusCode(201)
                .extract().asString();
        assertThat(response).doesNotContain("argon2id");
        assertThat(response).doesNotContain("password");
    }

    @Test
    void displayNameDefaultsToEmailLocalPart() {
        String localPart = "lp" + System.nanoTime();
        Map<String, Object> body = Map.of(
                "email", localPart + "@example.com",
                "password", "Password1234");

        given().contentType(ContentType.JSON).header("X-Edge-Token", EDGE_TOKEN).body(body)
                .when().post("/signup")
                .then().statusCode(201)
                .body("displayName", equalTo(localPart));
    }

    @Test
    void responseEchoesValidLocationHeader() {
        Map<String, Object> body = Map.of(
                "email", "loc." + uniq() + "@example.com",
                "password", "Password1234");

        given().contentType(ContentType.JSON).header("X-Edge-Token", EDGE_TOKEN).body(body)
                .when().post("/signup")
                .then().statusCode(201)
                .header("Location", matchesPattern("^/users/[0-9a-fA-F-]{36}$"));
    }

    private static String uniq() {
        return Long.toHexString(System.nanoTime());
    }
}
