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

/** {@code token-verification: strict}: only tokens signed with a registered key get in. */
@QuarkusTest
@TestProfile(OkeTokenVerificationStrictRestIntegrationTest.StrictProfile.class)
class OkeTokenVerificationStrictRestIntegrationTest {

    @Inject
    EmulatorConfig config;

    private static OkeTokenReviewFixture fixture;

    @BeforeEach
    void createFixture() {
        if (fixture == null) {
            fixture = new OkeTokenReviewFixture("strict", config.defaultRegion());
        }
    }

    @Test
    void aRegisteredKeyIsMappedThroughIam() {
        String user = fixture.createUser("dev");
        String devs = fixture.createGroup("Devs");
        fixture.addToGroup(user, devs);
        fixture.uploadMinterKey(user);

        fixture.review(fixture.token(user))
            .body("status.authenticated", equalTo(true))
            .body("status.user.groups", equalTo(List.of(devs, "system:authenticated")));
    }

    @Test
    void anUnregisteredKeyIsNotAuthenticated() {
        fixture.review(fixture.token(fixture.createUser("legacy")))
            .body("status.authenticated", equalTo(false));
    }

    public static class StrictProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-oci.services.oke.token-verification", "strict");
        }
    }
}
