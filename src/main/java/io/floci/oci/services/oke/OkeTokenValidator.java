package io.floci.oci.services.oke;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.auth.AuthContext;
import io.floci.oci.core.auth.OciRequestSignature;
import io.floci.oci.core.auth.OciSignatureParser;
import io.floci.oci.services.identity.IdentityService;
import io.floci.oci.services.identity.model.StoredApiKey;
import io.floci.oci.services.oke.model.StoredOkeCluster;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Checks the bearer tokens {@code oci ce cluster generate-token} produces. A token is
 * {@code base64url(<signed GET URL>)}, where the URL is
 * {@code https://containerengine.<region>.oraclecloud.com/cluster_request/<cluster-ocid>?authorization=<Signature ...>&date=<RFC 1123>}
 * (oci-cli {@code containerengine_cli_extended.py}): the CLI signs a bare GET of that URL over
 * {@code date (request-target) host}, then copies the {@code authorization} and {@code date}
 * headers into the query, so the query is not part of what was signed.
 *
 * <p>Every mode binds the token to the cluster: the URL must name this cluster and its region, the
 * Signature must cover {@code date (request-target) host}, its keyId tenancy (or, for
 * {@code --auth security_token}, the security token's {@code tenant} claim) must own the cluster,
 * and the date must be within five minutes of now, OCI's limit for a signed request. What the
 * signature itself is checked against depends on {@link OkeTokenVerification}: an API key uploaded
 * to identity, or for a session token of a known user the session key in its {@code jwk} claim.
 */
@ApplicationScoped
public class OkeTokenValidator {

    private static final Logger LOG = Logger.getLogger(OkeTokenValidator.class);
    static final int MAX_TOKEN_LENGTH = 4096;
    static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);
    private static final String REQUIRED_SIGNED_HEADERS = "date (request-target) host";
    private static final String CLUSTER_REQUEST_PATH = "/cluster_request/";
    private static final Set<String> ALLOWED_QUERY_PARAMS = Set.of("authorization", "date", "opc-obo-token");
    private static final int MAX_WARNED_KEYS = 1024;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Clock clock;
    private final OkeKeyDirectory keys;
    private final Supplier<OkeTokenVerification> mode;
    private final Set<String> warnedKeys = ConcurrentHashMap.newKeySet();

    @Inject
    public OkeTokenValidator(EmulatorConfig config, IdentityService identity) {
        this(Clock.systemUTC(), new IdentityKeyDirectory(identity),
                () -> OkeTokenVerification.parse(config.services().oke().tokenVerification()));
    }

    /** Known-keys mode with no key registered: every well-formed token keeps round 1's behavior. */
    OkeTokenValidator(Clock clock) {
        this(clock, new NoKeys(), () -> OkeTokenVerification.KNOWN_KEYS);
    }

    OkeTokenValidator(Clock clock, OkeKeyDirectory keys, Supplier<OkeTokenVerification> mode) {
        this.clock = clock;
        this.keys = keys;
        this.mode = mode;
    }

    /** The caller the token was signed by, when the token is valid for {@code cluster}. */
    public Optional<OkeCaller> validate(String token, StoredOkeCluster cluster) {
        if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
            return reject(cluster, "token is missing or longer than " + MAX_TOKEN_LENGTH);
        }
        Optional<URI> url = decode(token);
        if (url.isEmpty()) {
            return reject(cluster, "token is not a base64-encoded URL");
        }
        URI uri = url.get();
        String expectedHost = "containerengine." + cluster.getRegion() + ".oraclecloud.com";
        if (!"https".equals(uri.getScheme()) || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                || uri.getPort() != -1 || !expectedHost.equalsIgnoreCase(uri.getHost())) {
            return reject(cluster, "token URL is not https://" + expectedHost);
        }
        if (!(CLUSTER_REQUEST_PATH + cluster.getId()).equals(uri.getRawPath())) {
            return reject(cluster, "token URL names another cluster");
        }
        Optional<Map<String, String>> query = queryParameters(uri.getRawQuery());
        if (query.isEmpty() || !query.get().containsKey("authorization") || !query.get().containsKey("date")) {
            return reject(cluster, "token URL lacks a single authorization and date");
        }
        Map<String, String> signature = OciSignatureParser.parameters(query.get().get("authorization"));
        if (!"rsa-sha256".equals(signature.get("algorithm"))
                || !REQUIRED_SIGNED_HEADERS.equals(signature.get("headers"))
                || signature.getOrDefault("signature", "").isBlank()) {
            return reject(cluster, "authorization is not an rsa-sha256 Signature over " + REQUIRED_SIGNED_HEADERS);
        }
        Optional<AuthContext> keyOwner = OciSignatureParser.parse(query.get().get("authorization"));
        if (keyOwner.isEmpty() || !keyOwner.get().tenancyId().equals(cluster.getTenancyId())) {
            return reject(cluster, "keyId tenancy does not own the cluster");
        }
        String date = query.get().get("date");
        if (!isCurrent(date)) {
            return reject(cluster, "date is not within " + MAX_CLOCK_SKEW.toMinutes() + " minutes of now");
        }
        SignedRequest signed = new SignedRequest(uri, date, signature.get("signature"));
        String keyId = signature.get("keyid");
        OkeTokenVerification verification = mode.get();
        if (OciSignatureParser.isSecurityToken(keyId)) {
            return securityTokenCaller(cluster, keyOwner.get(), keyId, signed, verification);
        }
        return apiKeyCaller(cluster, keyOwner.get(), keyId, signed, verification);
    }

    private Optional<OkeCaller> apiKeyCaller(StoredOkeCluster cluster, AuthContext owner, String keyId,
                                             SignedRequest signed, OkeTokenVerification verification) {
        OkeCaller legacy = new OkeCaller(owner.userId(), owner.tenancyId(), OkeCaller.Kind.USER, false);
        if (verification == OkeTokenVerification.OFF) {
            return Optional.of(legacy);
        }
        Optional<PublicKey> key = keys.apiKey(owner.tenancyId(), owner.userId(), owner.fingerprint());
        if (key.isPresent()) {
            return signed.verifiesWith(key.get())
                    ? Optional.of(new OkeCaller(owner.userId(), owner.tenancyId(), OkeCaller.Kind.USER, true))
                    : reject(cluster, "signature does not verify with API key " + owner.fingerprint());
        }
        if (verification == OkeTokenVerification.STRICT) {
            return reject(cluster, "API key " + keyId + " is not registered (token-verification=strict)");
        }
        if (keys.hasApiKeys(owner.tenancyId(), owner.userId())) {
            return reject(cluster, "user " + owner.userId() + " has API keys, but not " + owner.fingerprint());
        }
        warnOnce(keyId, "OKE cluster {0}: accepted a token signed with API key {1}, which is not registered in"
                + " identity, as cluster-admin without checking its signature (token-verification=known-keys)."
                + " Upload the public key (UploadApiKey, oci iam user api-key upload) to have the signature"
                + " verified and the caller mapped by IAM as OKE does, or set token-verification=strict to"
                + " refuse such tokens.", cluster.getId(), keyId);
        return Optional.of(legacy);
    }

    /**
     * {@code --auth security_token} and instance principal tokens. floci-oci issues no session tokens,
     * so the JWT itself cannot be checked; only a session token of a known user that carries a
     * {@code jwk} is verified, as proof that the caller holds the session key.
     */
    private Optional<OkeCaller> securityTokenCaller(StoredOkeCluster cluster, AuthContext owner, String keyId,
                                                    SignedRequest signed, OkeTokenVerification verification) {
        JsonNode claims = OciSignatureParser.securityTokenClaims(keyId).orElseThrow();
        boolean instance = "instance".equals(OciSignatureParser.textClaim(claims, "ptype"));
        OkeCaller.Kind kind = instance ? OkeCaller.Kind.INSTANCE : OkeCaller.Kind.USER;
        OkeCaller legacy = new OkeCaller(owner.userId(), owner.tenancyId(), kind, false);
        if (verification == OkeTokenVerification.OFF) {
            return Optional.of(legacy);
        }
        if (verification == OkeTokenVerification.STRICT) {
            return reject(cluster, "security tokens have no trust anchor in floci-oci (token-verification=strict)");
        }
        Optional<PublicKey> sessionKey = instance ? Optional.empty() : jwkKey(claims);
        if (sessionKey.isEmpty() || !keys.userExists(owner.tenancyId(), owner.userId())) {
            warnOnce("ST$" + owner.tenancyId() + "/" + owner.userId(), "OKE cluster {0}: accepted a security token"
                    + " for {1} as cluster-admin without checking its signature (token-verification=known-keys):"
                    + " only a session token of a user that exists in identity and carries a jwk claim is verified.",
                    cluster.getId(), owner.userId());
            return Optional.of(legacy);
        }
        return signed.verifiesWith(sessionKey.get())
                ? Optional.of(new OkeCaller(owner.userId(), owner.tenancyId(), kind, true))
                : reject(cluster, "signature does not verify with the session key in the token's jwk claim");
    }

    /** The RSA key of a {@code jwk} claim, which may be a JSON object or a string holding one. */
    private static Optional<PublicKey> jwkKey(JsonNode claims) {
        JsonNode jwk = claims.get("jwk");
        if (jwk != null && jwk.isTextual()) {
            try {
                jwk = JSON.readTree(jwk.asText());
            } catch (IOException e) {
                LOG.debugv("Unreadable jwk claim: {0}", e.getMessage());
                return Optional.empty();
            }
        }
        if (jwk == null || !jwk.isObject() || !"RSA".equals(jwk.path("kty").asText())) {
            return Optional.empty();
        }
        try {
            return Optional.of(OciRequestSignature.publicKeyFromJwk(jwk.path("n").asText(), jwk.path("e").asText()));
        } catch (IllegalArgumentException e) {
            LOG.debugv("jwk claim is not an RSA key: {0}", e.getMessage());
            return Optional.empty();
        }
    }

    private void warnOnce(String key, String format, Object... params) {
        if (warnedKeys.size() < MAX_WARNED_KEYS && warnedKeys.add(key)) {
            LOG.warnv(format, params);
        }
    }

    private Optional<URI> decode(String token) {
        byte[] bytes;
        try {
            bytes = Base64.getUrlDecoder().decode(token);
        } catch (IllegalArgumentException notUrlSafe) {
            try {
                bytes = Base64.getDecoder().decode(token);
            } catch (IllegalArgumentException notBase64) {
                return Optional.empty();
            }
        }
        try {
            return Optional.of(new URI(new String(bytes, StandardCharsets.UTF_8)));
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }

    /** Form-decoded query parameters; empty when one is unknown, appears twice or is badly escaped. */
    private static Optional<Map<String, String>> queryParameters(String rawQuery) {
        Map<String, String> params = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return Optional.of(params);
        }
        for (String pair : rawQuery.split("&")) {
            int equals = pair.indexOf('=');
            String name;
            String value;
            try {
                name = URLDecoder.decode(equals < 0 ? pair : pair.substring(0, equals), StandardCharsets.UTF_8);
                value = equals < 0 ? "" : URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException badEscape) {
                return Optional.empty();
            }
            if (!ALLOWED_QUERY_PARAMS.contains(name) || params.putIfAbsent(name, value) != null) {
                return Optional.empty();
            }
        }
        return Optional.of(params);
    }

    private boolean isCurrent(String date) {
        Instant signedAt;
        try {
            signedAt = ZonedDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (DateTimeParseException e) {
            return false;
        }
        Duration age = Duration.between(signedAt, clock.instant()).abs();
        return age.compareTo(MAX_CLOCK_SKEW) <= 0;
    }

    private static Optional<OkeCaller> reject(StoredOkeCluster cluster, String reason) {
        LOG.debugv("Rejected token for OKE cluster {0}: {1}", cluster.getId(), reason);
        return Optional.empty();
    }

    /** What the CLI signed: a bare GET of the URL's path, with the date and host it sent. */
    private record SignedRequest(URI uri, String date, String signature) {

        boolean verifiesWith(PublicKey key) {
            String signingString = OciRequestSignature.signingString("GET", uri.getRawPath(),
                    Arrays.asList(REQUIRED_SIGNED_HEADERS.split(" ")),
                    Map.of("date", date, "host", uri.getHost()));
            return OciRequestSignature.verify(key, signingString, signature);
        }
    }

    /** Identity's users and API keys, read from the cluster's tenancy partition. */
    static final class IdentityKeyDirectory implements OkeKeyDirectory {
        private final IdentityService identity;

        IdentityKeyDirectory(IdentityService identity) {
            this.identity = identity;
        }

        @Override
        public Optional<PublicKey> apiKey(String tenancyId, String userId, String fingerprint) {
            return identity.apiKey(tenancyId, userId, fingerprint)
                    .filter(key -> "ACTIVE".equals(key.getLifecycleState()))
                    .flatMap(IdentityKeyDirectory::parse);
        }

        @Override
        public boolean hasApiKeys(String tenancyId, String userId) {
            return !identity.apiKeys(tenancyId, userId).isEmpty();
        }

        @Override
        public boolean userExists(String tenancyId, String userId) {
            return identity.user(tenancyId, userId).isPresent();
        }

        private static Optional<PublicKey> parse(StoredApiKey key) {
            try {
                return Optional.of(OciRequestSignature.publicKeyFromPem(key.getKeyValue()));
            } catch (IllegalArgumentException e) {
                LOG.warnv("Stored API key {0} is not a readable public key: {1}", key.getKeyId(), e.getMessage());
                return Optional.empty();
            }
        }
    }

    private static final class NoKeys implements OkeKeyDirectory {
        @Override
        public Optional<PublicKey> apiKey(String tenancyId, String userId, String fingerprint) {
            return Optional.empty();
        }

        @Override
        public boolean hasApiKeys(String tenancyId, String userId) {
            return false;
        }

        @Override
        public boolean userExists(String tenancyId, String userId) {
            return false;
        }
    }
}
