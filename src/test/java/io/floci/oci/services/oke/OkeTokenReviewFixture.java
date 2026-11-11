package io.floci.oci.services.oke;

import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;

/**
 * A tenancy of its own, so IAM records such as an {@code Administrators} group stay out of other
 * tests, holding one mock-mode cluster and the users, groups and API keys a test asks for, all
 * created through the APIs a real client uses, signed as that tenancy.
 */
final class OkeTokenReviewFixture {

    final String tenancy;
    final String region;
    final String clusterId;

    OkeTokenReviewFixture(String tenancySuffix, String region) {
        this.tenancy = "ocid1.tenancy.oc1..tokenreview" + tenancySuffix;
        this.region = region;
        String workRequest = signed()
            .contentType(ContentType.JSON)
            .body(Map.of("compartmentId", tenancy, "name", "token-review", "vcnId", "ocid1.vcn.oc1.iad.tokenreview",
                    "kubernetesVersion", OkeVersions.DEFAULT_VERSION))
            .when().post("/20180222/clusters")
            .then().statusCode(202).extract().header("opc-work-request-id");
        this.clusterId = signed().when().get("/20180222/workRequests/{id}", workRequest)
            .then().statusCode(200).extract().path("resources[0].identifier");
    }

    String createUser(String name) {
        return signed().contentType(ContentType.JSON).body(Map.of("name", name, "description", "token review user"))
            .when().post("/20160918/users").then().statusCode(200).extract().path("id");
    }

    String createGroup(String name) {
        return signed().contentType(ContentType.JSON).body(Map.of("name", name, "description", "token review group"))
            .when().post("/20160918/groups").then().statusCode(200).extract().path("id");
    }

    void addToGroup(String userId, String groupId) {
        signed().contentType(ContentType.JSON).body(Map.of("userId", userId, "groupId", groupId))
            .when().post("/20160918/userGroupMemberships").then().statusCode(200);
    }

    void createPolicy(String name, String statement) {
        signed().contentType(ContentType.JSON)
            .body(Map.of("compartmentId", tenancy, "name", name, "description", "token review policy",
                    "statements", List.of(statement)))
            .when().post("/20160918/policies").then().statusCode(200);
    }

    /** Uploads the public key of {@link ClusterTokenMinter#KEY_PAIR} as one of the user's API keys. */
    void uploadMinterKey(String userId) {
        signed().contentType(ContentType.JSON).body(Map.of("key", ClusterTokenMinter.publicKeyPem()))
            .when().post("/20160918/users/{userId}/apiKeys", userId).then().statusCode(200);
    }

    String token(String userId) {
        return ClusterTokenMinter.mint(region, clusterId, tenancy, userId, Instant.now());
    }

    ValidatableResponse review(String token) {
        return given()
            .contentType(ContentType.JSON)
            .body(Map.of("apiVersion", "authentication.k8s.io/v1", "kind", "TokenReview",
                    "spec", Map.of("token", token)))
            .when().post("/_floci-oci/oke/token-webhook/{tenancy}/{cluster}", tenancy, clusterId)
            .then().statusCode(200);
    }

    private RequestSpecification signed() {
        return given().header("Authorization", "Signature version=\"1\",keyId=\"" + tenancy
                + "/ocid1.user.oc1..tokenreviewadmin/aa:bb\",algorithm=\"rsa-sha256\","
                + "headers=\"date (request-target) host\",signature=\"ZmFrZQ==\"");
    }
}
