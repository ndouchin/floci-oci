package io.floci.oci.services.oke;

import io.floci.oci.core.auth.OciRequestSignature;
import io.floci.oci.services.oke.model.StoredOkeCluster;
import io.floci.oci.testing.GoldenOkeToken;
import io.floci.oci.testing.SecurityTokens;
import io.floci.oci.testing.TestKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OkeTokenValidatorTest {

    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");
    private static final String REGION = "us-ashburn-1";
    private static final String CLUSTER_ID = "ocid1.cluster.oc1.iad.tokentest001";
    private static final String TENANCY = "ocid1.tenancy.oc1..tokentenancy";
    private static final String USER = "ocid1.user.oc1..tokenuser";

    private OkeTokenValidator validator;
    private StoredOkeCluster cluster;

    @BeforeEach
    void setUp() {
        validator = new OkeTokenValidator(Clock.fixed(NOW, ZoneOffset.UTC));
        cluster = new StoredOkeCluster();
        cluster.setId(CLUSTER_ID);
        cluster.setRegion(REGION);
        cluster.setTenancyId(TENANCY);
    }

    @Test
    void acceptsATokenMintedLikeTheOciCli() {
        Optional<OkeCaller> caller = validator.validate(
                ClusterTokenMinter.mint(REGION, CLUSTER_ID, TENANCY, USER, NOW), cluster);

        assertTrue(caller.isPresent());
        assertEquals(TENANCY, caller.get().tenancyId());
        assertEquals(USER, caller.get().principalId());
    }

    @Test
    void acceptsStandardBase64AsThirdPartyMintersUseIt() {
        String urlSafe = ClusterTokenMinter.mint(REGION, CLUSTER_ID, TENANCY, USER, NOW);
        String standard = Base64.getEncoder().encodeToString(Base64.getUrlDecoder().decode(urlSafe));

        assertTrue(validator.validate(standard, cluster).isPresent());
    }

    @Test
    void acceptsDatesWithinFiveMinutesEitherSide() {
        assertTrue(validator.validate(mintAt(NOW.minus(Duration.ofMinutes(5))), cluster).isPresent());
        assertTrue(validator.validate(mintAt(NOW.plus(Duration.ofMinutes(5))), cluster).isPresent());
    }

    @Test
    void rejectsStaleAndFutureDates() {
        assertRejected(mintAt(NOW.minus(Duration.ofMinutes(5)).minusSeconds(1)));
        assertRejected(mintAt(NOW.plus(Duration.ofMinutes(5)).plusSeconds(1)));
    }

    @Test
    void rejectsATokenForAnotherCluster() {
        assertRejected(ClusterTokenMinter.mint(REGION, "ocid1.cluster.oc1.iad.othercluster", TENANCY, USER, NOW));
    }

    @Test
    void rejectsATokenFromAnotherTenancy() {
        assertRejected(ClusterTokenMinter.mint(REGION, CLUSTER_ID, "ocid1.tenancy.oc1..someoneelse", USER, NOW));
    }

    @Test
    void rejectsATokenSignedForAnotherRegion() {
        assertRejected(ClusterTokenMinter.mint("us-phoenix-1", CLUSTER_ID, TENANCY, USER, NOW));
    }

    @Test
    void rejectsUrlsThatAreNotTheRealContainerEngineHost() {
        String minted = decoded(ClusterTokenMinter.mint(REGION, CLUSTER_ID, TENANCY, USER, NOW));
        assertRejected(ClusterTokenMinter.encode(minted.replace("https://", "http://")));
        assertRejected(ClusterTokenMinter.encode(minted.replace("oraclecloud.com", "oraclecloud.com.evil.example")));
        assertRejected(ClusterTokenMinter.encode(minted.replace(".oraclecloud.com/", ".oraclecloud.com:8443/")));
        assertRejected(ClusterTokenMinter.encode(minted.replace("/cluster_request/", "/clusters/")));
        assertRejected(ClusterTokenMinter.encode(minted.replace("https://", "https://user@")));
        assertRejected(ClusterTokenMinter.encode(minted + "#fragment"));
    }

    @Test
    void rejectsMissingDuplicateAndUnknownQueryParameters() {
        String minted = decoded(ClusterTokenMinter.mint(REGION, CLUSTER_ID, TENANCY, USER, NOW));
        String withoutDate = minted.substring(0, minted.indexOf("&date="));
        String withoutAuthorization = minted.substring(0, minted.indexOf('?') + 1)
                + minted.substring(minted.indexOf("date="));

        assertRejected(ClusterTokenMinter.encode(withoutDate));
        assertRejected(ClusterTokenMinter.encode(withoutAuthorization));
        assertRejected(ClusterTokenMinter.encode(minted + "&date=Thu%2C+01+Oct+2026+12%3A00%3A00+GMT"));
        assertRejected(ClusterTokenMinter.encode(minted + "&extra=1"));
    }

    @Test
    void acceptsTheOptionalOboToken() {
        String minted = decoded(ClusterTokenMinter.mint(REGION, CLUSTER_ID, TENANCY, USER, NOW));

        assertTrue(validator.validate(ClusterTokenMinter.encode(minted + "&opc-obo-token=delegation"), cluster).isPresent());
    }

    @Test
    void rejectsSignaturesOverOtherHeadersOrAlgorithms() {
        String minted = decoded(ClusterTokenMinter.mint(REGION, CLUSTER_ID, TENANCY, USER, NOW));

        assertRejected(ClusterTokenMinter.encode(minted.replace("headers%3D%22date+%28request-target%29+host%22",
                "headers%3D%22date+host%22")));
        assertRejected(ClusterTokenMinter.encode(minted.replace("rsa-sha256", "hmac-sha256")));
        assertRejected(ClusterTokenMinter.encode(minted.replaceAll("signature%3D%22.*?%22", "signature%3D%22%22")));
    }

    @Test
    void acceptsASessionTokenFromTheClustersTenancy() {
        String token = ClusterTokenMinter.mintWithSecurityToken(
                REGION, CLUSTER_ID, SecurityTokens.session(TENANCY, USER), NOW);

        Optional<OkeCaller> caller = validator.validate(token, cluster);

        assertTrue(caller.isPresent(), () -> "rejected: " + decoded(token));
        assertEquals(TENANCY, caller.get().tenancyId());
        assertEquals(USER, caller.get().principalId());
        assertTrue(token.length() <= OkeTokenValidator.MAX_TOKEN_LENGTH,
                () -> "a session-auth token is " + token.length() + " characters");
    }

    @Test
    void rejectsASessionTokenFromAnotherTenancy() {
        assertRejected(ClusterTokenMinter.mintWithSecurityToken(
                REGION, CLUSTER_ID, SecurityTokens.session("ocid1.tenancy.oc1..someoneelse", USER), NOW));
    }

    @Test
    void rejectsSecurityTokenKeyIdsItCannotBindToATenancy() {
        assertRejected(ClusterTokenMinter.mintWithKeyId(REGION, CLUSTER_ID, "ST$eyJhbGciOi", NOW));
        assertRejected(ClusterTokenMinter.mintWithSecurityToken(
                REGION, CLUSTER_ID, SecurityTokens.jwt("{\"sub\":\"" + USER + "\"}"), NOW));
    }

    @Test
    void rejectsGarbageAndOversizedTokens() {
        assertRejected(null);
        assertRejected("");
        assertRejected("!!!not base64!!!");
        assertRejected(ClusterTokenMinter.encode("not a url at all"));
        assertRejected("A".repeat(OkeTokenValidator.MAX_TOKEN_LENGTH + 1));
    }

    // ── Signature verification modes ─────────────────────────────────────────

    @Test
    void theRealOciCliTokenIsVerifiedOnceItsKeyIsRegistered() {
        FakeDirectory keys = new FakeDirectory();
        keys.register(GoldenOkeToken.TENANCY, GoldenOkeToken.USER,
                OciRequestSignature.publicKeyFromPem(GoldenOkeToken.PUBLIC_KEY_PEM));
        StoredOkeCluster golden = cluster(GoldenOkeToken.CLUSTER, GoldenOkeToken.REGION, GoldenOkeToken.TENANCY);

        for (OkeTokenVerification mode : List.of(OkeTokenVerification.KNOWN_KEYS, OkeTokenVerification.STRICT)) {
            Optional<OkeCaller> caller = validatorAt(GoldenOkeToken.SIGNED_AT, keys, mode)
                    .validate(GoldenOkeToken.TOKEN, golden);

            assertTrue(caller.isPresent(), mode::toString);
            assertTrue(caller.get().verified(), mode::toString);
            assertEquals(GoldenOkeToken.USER, caller.get().principalId());
            assertEquals(GoldenOkeToken.TENANCY, caller.get().tenancyId());
            assertEquals(OkeCaller.Kind.USER, caller.get().kind());
        }
    }

    @Test
    void aRegisteredKeyWithABadSignatureIsRejectedWhereverSignaturesAreVerified() {
        FakeDirectory keys = registeredMinterKey();
        String tampered = ClusterTokenMinter.tamperSignature(ClusterTokenMinter.mint(REGION, CLUSTER_ID, TENANCY, USER, NOW));

        assertTrue(validator(keys, OkeTokenVerification.KNOWN_KEYS).validate(tampered, cluster).isEmpty());
        assertTrue(validator(keys, OkeTokenVerification.STRICT).validate(tampered, cluster).isEmpty());
        Optional<OkeCaller> off = validator(keys, OkeTokenVerification.OFF).validate(tampered, cluster);
        assertTrue(off.isPresent() && !off.get().verified(), "off checks the token's shape only, as round 1 did");
    }

    @Test
    void aRegisteredKeyWithAGoodSignatureIsVerified() {
        Optional<OkeCaller> caller = validator(registeredMinterKey(), OkeTokenVerification.KNOWN_KEYS)
                .validate(ClusterTokenMinter.mint(REGION, CLUSTER_ID, TENANCY, USER, NOW), cluster);

        assertTrue(caller.isPresent());
        assertTrue(caller.get().verified());
    }

    @Test
    void anUnregisteredKeyIsLegacyInKnownKeysRejectedInStrictAndLegacyInOff() {
        String token = ClusterTokenMinter.mint(REGION, CLUSTER_ID, TENANCY, USER, NOW);
        FakeDirectory none = new FakeDirectory();

        Optional<OkeCaller> knownKeys = validator(none, OkeTokenVerification.KNOWN_KEYS).validate(token, cluster);
        assertTrue(knownKeys.isPresent());
        assertFalse(knownKeys.get().verified());
        assertTrue(validator(none, OkeTokenVerification.STRICT).validate(token, cluster).isEmpty());
        Optional<OkeCaller> off = validator(none, OkeTokenVerification.OFF).validate(token, cluster);
        assertTrue(off.isPresent());
        assertFalse(off.get().verified());
    }

    @Test
    void anotherKeyOfAUserWhoRegisteredKeysIsRejected() {
        FakeDirectory keys = new FakeDirectory();
        keys.register(TENANCY, USER, TestKeys.rsaKeyPair().getPublic());

        assertTrue(validator(keys, OkeTokenVerification.KNOWN_KEYS)
                .validate(ClusterTokenMinter.mint(REGION, CLUSTER_ID, TENANCY, USER, NOW), cluster).isEmpty(),
                "OCI would not accept a key the user does not have");
    }

    @Test
    void aSessionTokenSignedWithItsJwkKeyIsVerifiedForAKnownUserInKnownKeysAndRejectedInStrict() {
        KeyPair session = TestKeys.rsaKeyPair();
        FakeDirectory keys = new FakeDirectory();
        keys.users.add(TENANCY + "/" + USER);
        for (boolean jwkAsString : List.of(false, true)) {
            String token = ClusterTokenMinter.mintWithSecurityToken(REGION, CLUSTER_ID,
                    SecurityTokens.sessionWithJwk(TENANCY, USER, ClusterTokenMinter.jwk(session), jwkAsString),
                    NOW, session);

            Optional<OkeCaller> caller = validator(keys, OkeTokenVerification.KNOWN_KEYS).validate(token, cluster);
            assertTrue(caller.isPresent(), () -> "jwk as string: " + jwkAsString);
            assertTrue(caller.get().verified());
            assertEquals(USER, caller.get().principalId());
            assertTrue(validator(keys, OkeTokenVerification.STRICT).validate(token, cluster).isEmpty());
        }
    }

    @Test
    void aSessionTokenNotSignedWithItsJwkKeyIsRejectedForAKnownUser() {
        FakeDirectory keys = new FakeDirectory();
        keys.users.add(TENANCY + "/" + USER);
        String token = ClusterTokenMinter.mintWithSecurityToken(REGION, CLUSTER_ID,
                SecurityTokens.sessionWithJwk(TENANCY, USER, ClusterTokenMinter.jwk(TestKeys.rsaKeyPair()), false),
                NOW, TestKeys.rsaKeyPair());

        assertTrue(validator(keys, OkeTokenVerification.KNOWN_KEYS).validate(token, cluster).isEmpty());
        assertTrue(validator(keys, OkeTokenVerification.OFF).validate(token, cluster).isPresent());
    }

    @Test
    void aSessionTokenForAnUnknownUserStaysLegacyInKnownKeys() {
        Optional<OkeCaller> caller = validator(new FakeDirectory(), OkeTokenVerification.KNOWN_KEYS)
                .validate(ClusterTokenMinter.mintWithSecurityToken(REGION, CLUSTER_ID,
                        SecurityTokens.session(TENANCY, USER), NOW), cluster);

        assertTrue(caller.isPresent());
        assertFalse(caller.get().verified());
    }

    @Test
    void anInstancePrincipalTokenIsTheInstanceInKnownKeysAndRejectedInStrict() {
        String instance = "ocid1.instance.oc1.iad.tokeninstance";
        String token = ClusterTokenMinter.mintWithSecurityToken(REGION, CLUSTER_ID,
                SecurityTokens.instancePrincipal(TENANCY, instance, TENANCY), NOW);

        Optional<OkeCaller> caller = validator(new FakeDirectory(), OkeTokenVerification.KNOWN_KEYS)
                .validate(token, cluster);
        assertTrue(caller.isPresent());
        assertEquals(instance, caller.get().principalId());
        assertEquals(OkeCaller.Kind.INSTANCE, caller.get().kind());
        assertFalse(caller.get().verified());
        assertTrue(validator(new FakeDirectory(), OkeTokenVerification.STRICT).validate(token, cluster).isEmpty());
    }

    @Test
    void theModesParseFromTheirConfigValues() {
        assertEquals(OkeTokenVerification.KNOWN_KEYS, OkeTokenVerification.parse("known-keys"));
        assertEquals(OkeTokenVerification.STRICT, OkeTokenVerification.parse("STRICT"));
        assertEquals(OkeTokenVerification.OFF, OkeTokenVerification.parse("off"));
        assertEquals("Invalid floci-oci.services.oke.token-verification value 'maybe': allowed values are"
                        + " known-keys, strict and off",
                assertThrows(IllegalStateException.class,
                        () -> OkeTokenVerification.parse("maybe")).getMessage());
    }

    private FakeDirectory registeredMinterKey() {
        FakeDirectory keys = new FakeDirectory();
        keys.register(TENANCY, USER, ClusterTokenMinter.KEY_PAIR.getPublic());
        return keys;
    }

    private OkeTokenValidator validator(FakeDirectory keys, OkeTokenVerification mode) {
        return validatorAt(NOW, keys, mode);
    }

    private static OkeTokenValidator validatorAt(Instant now, FakeDirectory keys, OkeTokenVerification mode) {
        return new OkeTokenValidator(Clock.fixed(now, ZoneOffset.UTC), keys, () -> mode);
    }

    private static StoredOkeCluster cluster(String id, String region, String tenancy) {
        StoredOkeCluster stored = new StoredOkeCluster();
        stored.setId(id);
        stored.setRegion(region);
        stored.setTenancyId(tenancy);
        return stored;
    }

    private static final class FakeDirectory implements OkeKeyDirectory {
        private final Map<String, PublicKey> keys = new HashMap<>();
        private final Set<String> users = new HashSet<>();

        void register(String tenancy, String user, PublicKey key) {
            keys.put(tenancy + "/" + user + "/" + OciRequestSignature.fingerprint(key), key);
            users.add(tenancy + "/" + user);
        }

        @Override
        public Optional<PublicKey> apiKey(String tenancyId, String userId, String fingerprint) {
            return Optional.ofNullable(keys.get(tenancyId + "/" + userId + "/" + fingerprint));
        }

        @Override
        public boolean hasApiKeys(String tenancyId, String userId) {
            return keys.keySet().stream().anyMatch(key -> key.startsWith(tenancyId + "/" + userId + "/"));
        }

        @Override
        public boolean userExists(String tenancyId, String userId) {
            return users.contains(tenancyId + "/" + userId);
        }
    }

    private String mintAt(Instant signedAt) {
        return ClusterTokenMinter.mint(REGION, CLUSTER_ID, TENANCY, USER, signedAt);
    }

    private void assertRejected(String token) {
        assertTrue(validator.validate(token, cluster).isEmpty(), () -> "accepted: " + decodedOrRaw(token));
    }

    private static String decoded(String token) {
        return new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
    }

    private static String decodedOrRaw(String token) {
        if (token == null) {
            return "null";
        }
        try {
            return decoded(token);
        } catch (IllegalArgumentException notBase64Url) {
            return token;
        }
    }
}
