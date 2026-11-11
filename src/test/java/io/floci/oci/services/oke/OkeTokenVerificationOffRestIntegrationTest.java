package io.floci.oci.services.oke;

import io.floci.oci.config.EmulatorConfig;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/** {@code token-verification: off}: round 1's behavior, whatever identity holds. */
@QuarkusTest
@TestProfile(OkeTokenVerificationOffRestIntegrationTest.OffProfile.class)
class OkeTokenVerificationOffRestIntegrationTest {

    @Inject
    EmulatorConfig config;

    private static OkeTokenReviewFixture fixture;

    @BeforeEach
    void createFixture() {
        if (fixture == null) {
            fixture = new OkeTokenReviewFixture("off", config.defaultRegion());
        }
    }

    @Test
    void everyWellFormedTokenIsClusterAdminEvenWithARegisteredKeyAndABadSignature() {
        String user = fixture.createUser("dev");
        fixture.addToGroup(user, fixture.createGroup("Devs"));
        fixture.uploadMinterKey(user);

        for (String token : List.of(fixture.token(user), ClusterTokenMinter.tamperSignature(fixture.token(user)))) {
            fixture.review(token)
                .body("status.authenticated", equalTo(true))
                .body("status.user.username", equalTo(user))
                .body("status.user.groups", equalTo(List.of("system:masters", "system:authenticated")))
                .body("status.user.extra", nullValue());
        }
    }

    public static class OffProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-oci.services.oke.token-verification", "off");
        }
    }
}
