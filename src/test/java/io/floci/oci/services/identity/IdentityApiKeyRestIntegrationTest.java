package io.floci.oci.services.identity;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.testing.GoldenOkeToken;
import io.floci.oci.testing.TestKeys;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class IdentityApiKeyRestIntegrationTest {

    @Inject
    EmulatorConfig config;

    @Inject
    IdentityService identity;

    @Test
    void uploadReturnsTheKeyWithTheFingerprintOciComputes() {
        String user = createUser("apikey-upload");

        given()
            .contentType("application/json")
            .body(Map.of("key", GoldenOkeToken.PUBLIC_KEY_PEM))
            .when().post("/20160918/users/{userId}/apiKeys", user)
            .then()
                .statusCode(200)
                .header("opc-request-id", notNullValue())
                .header("etag", notNullValue())
                .body("fingerprint", equalTo(GoldenOkeToken.FINGERPRINT))
                .body("keyId", equalTo(config.defaultTenancyId() + "/" + user + "/" + GoldenOkeToken.FINGERPRINT))
                .body("userId", equalTo(user))
                .body("keyValue", equalTo(GoldenOkeToken.PUBLIC_KEY_PEM))
                .body("lifecycleState", equalTo("ACTIVE"))
                .body("timeCreated", notNullValue());
    }

    @Test
    void listAndDeleteWork() {
        String user = createUser("apikey-list");
        upload(user, GoldenOkeToken.PUBLIC_KEY_PEM).then().statusCode(200);
        String second = TestKeys.rsaPublicPem(2048);
        String secondFingerprint = upload(user, second).then().statusCode(200).extract().path("fingerprint");

        given().when().get("/20160918/users/{userId}/apiKeys", user)
            .then()
                .statusCode(200)
                .header("opc-request-id", notNullValue())
                .body("$", hasSize(2))
                .body("fingerprint", containsInAnyOrder(
                        GoldenOkeToken.FINGERPRINT, secondFingerprint));

        given().when().delete("/20160918/users/{userId}/apiKeys/{fingerprint}", user, GoldenOkeToken.FINGERPRINT)
            .then().statusCode(204).header("opc-request-id", notNullValue());
        given().when().get("/20160918/users/{userId}/apiKeys", user)
            .then().statusCode(200).body("fingerprint", equalTo(List.of(secondFingerprint)));
        given().when().delete("/20160918/users/{userId}/apiKeys/{fingerprint}", user, GoldenOkeToken.FINGERPRINT)
            .then().statusCode(404).body("code", equalTo("NotAuthorizedOrNotFound"));
    }

    @Test
    void aUserHoldsAtMostThreeKeys() {
        String user = createUser("apikey-limit");
        for (int i = 0; i < 3; i++) {
            upload(user, TestKeys.rsaPublicPem(2048)).then().statusCode(200);
        }

        upload(user, TestKeys.rsaPublicPem(2048))
            .then()
                .statusCode(400)
                .header("opc-request-id", notNullValue())
                .body("code", equalTo("InvalidParameter"));
    }

    @Test
    void theSameKeyTwiceIsAConflict() {
        String user = createUser("apikey-duplicate");
        upload(user, GoldenOkeToken.PUBLIC_KEY_PEM).then().statusCode(200);

        upload(user, GoldenOkeToken.PUBLIC_KEY_PEM)
            .then().statusCode(409).body("code", equalTo("Conflict"));
    }

    @Test
    void shortNonRsaAndMalformedKeysAreRefused() {
        String user = createUser("apikey-bad");

        upload(user, TestKeys.rsaPublicPem(1024)).then().statusCode(400).body("code", equalTo("InvalidParameter"));
        upload(user, TestKeys.ecPublicPem()).then().statusCode(400).body("code", equalTo("InvalidParameter"));
        upload(user, "not a key").then().statusCode(400).body("code", equalTo("InvalidParameter"));
        given().contentType("application/json").body(Map.of())
            .when().post("/20160918/users/{userId}/apiKeys", user)
            .then().statusCode(400).body("code", equalTo("MissingParameter"));
    }

    @Test
    void anUnknownUserIsNotFound() {
        String unknown = "ocid1.user.oc1..nosuchuser";

        upload(unknown, GoldenOkeToken.PUBLIC_KEY_PEM)
            .then().statusCode(404).body("code", equalTo("NotAuthorizedOrNotFound"));
        given().when().get("/20160918/users/{userId}/apiKeys", unknown)
            .then().statusCode(404).body("code", equalTo("NotAuthorizedOrNotFound"));
        given().when().delete("/20160918/users/{userId}/apiKeys/{fingerprint}", unknown, GoldenOkeToken.FINGERPRINT)
            .then().statusCode(404).body("code", equalTo("NotAuthorizedOrNotFound"));
    }

    @Test
    void deletingTheUserRemovesItsKeys() {
        String user = createUser("apikey-cascade");
        upload(user, GoldenOkeToken.PUBLIC_KEY_PEM).then().statusCode(200);
        assertTrue(identity.apiKey(config.defaultTenancyId(), user, GoldenOkeToken.FINGERPRINT).isPresent());

        given().when().delete("/20160918/users/{userId}", user).then().statusCode(204);

        assertTrue(identity.apiKey(config.defaultTenancyId(), user, GoldenOkeToken.FINGERPRINT).isEmpty());
    }

    private static Response upload(String user, String pem) {
        return given()
            .contentType("application/json")
            .body(Map.of("key", pem))
            .when().post("/20160918/users/{userId}/apiKeys", user);
    }

    private static String createUser(String prefix) {
        return given()
            .contentType("application/json")
            .body(Map.of("name", prefix + "-" + System.nanoTime(), "description", "API key test user"))
            .when().post("/20160918/users")
            .then().statusCode(200)
            .extract().path("id");
    }
}
