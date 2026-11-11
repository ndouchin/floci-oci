package io.floci.oci.services.oke;

import io.floci.oci.config.EmulatorConfig;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/** {@code token-verification: known-keys}, the default: TokenReviews posted the way k3s posts them. */
@QuarkusTest
class OkeTokenVerificationRestIntegrationTest {

    @Inject
    EmulatorConfig config;

    private static OkeTokenReviewFixture fixture;

    @BeforeEach
    void createFixture() {
        if (fixture == null) {
            fixture = new OkeTokenReviewFixture("knownkeys", config.defaultRegion());
        }
    }

    @Test
    void aCallerWithARegisteredKeyIsItsUserOcidAndItsGroupOcids() {
        String user = fixture.createUser("dev");
        String devs = fixture.createGroup("Devs");
        fixture.addToGroup(user, devs);
        fixture.uploadMinterKey(user);

        fixture.review(fixture.token(user))
            .body("status.authenticated", equalTo(true))
            .body("status.user.username", equalTo(user))
            .body("status.user.uid", equalTo(user))
            .body("status.user.groups", equalTo(List.of(devs, "system:authenticated")))
            .body("status.user.extra.AuthType", equalTo(List.of("Native")))
            .body("status.user.extra.ContextType", equalTo(List.of("OCI")))
            .body("status.user.extra.Principal", equalTo(List.of(user)))
            .body("status.user.extra.TenancyID", equalTo(List.of(fixture.tenancy)))
            .body("status.user.extra.TokenV2", equalTo(List.of("true")));
    }

    @Test
    void anAdministratorsMemberIsClusterAdmin() {
        String user = fixture.createUser("admin");
        String administrators = fixture.createGroup("Administrators");
        fixture.addToGroup(user, administrators);
        fixture.uploadMinterKey(user);

        fixture.review(fixture.token(user))
            .body("status.user.groups", equalTo(List.of(administrators, "system:masters", "system:authenticated")));
    }

    @Test
    void aManageClusterFamilyPolicyMakesClusterAdmin() {
        String user = fixture.createUser("operator");
        String operators = fixture.createGroup("Operators");
        fixture.addToGroup(user, operators);
        fixture.createPolicy("operators-manage-oke", "Allow group Operators to manage cluster-family in tenancy");
        fixture.uploadMinterKey(user);

        fixture.review(fixture.token(user))
            .body("status.user.groups", equalTo(List.of(operators, "system:masters", "system:authenticated")));
    }

    @Test
    void aTamperedSignatureIsNotAuthenticated() {
        String user = fixture.createUser("tampered");
        fixture.uploadMinterKey(user);

        fixture.review(ClusterTokenMinter.tamperSignature(fixture.token(user)))
            .body("status.authenticated", equalTo(false))
            .body("status.user", nullValue());
    }

    @Test
    void anUnregisteredKeyKeepsRoundOnesClusterAdmin() {
        String user = fixture.createUser("legacy");

        fixture.review(fixture.token(user))
            .body("status.authenticated", equalTo(true))
            .body("status.user.username", equalTo(user))
            .body("status.user.groups", equalTo(List.of("system:masters", "system:authenticated")))
            .body("status.user.extra", nullValue());
    }
}
