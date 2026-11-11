package io.floci.oci.services.oke;

import io.floci.oci.core.auth.OciRequestSignature;
import io.floci.oci.testing.TestKeys;

import java.math.BigInteger;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Locale;

/**
 * Mints bearer tokens the way {@code oci ce cluster generate-token} does
 * (oci-cli {@code containerengine_cli_extended.py:38-90}): RSA-sign a GET of
 * {@code https://containerengine.<region>.oraclecloud.com/cluster_request/<cluster-ocid>} over
 * {@code date (request-target) host} (oci-python-sdk {@code signer.py:257}), put the
 * {@code authorization} and {@code date} on the URL form-encoded like Python's {@code urlencode},
 * then base64url-encode the URL with padding. API-key tokens carry the real fingerprint of
 * {@link #KEY_PAIR}, so uploading {@link #publicKeyPem()} for a user makes that user's tokens verify.
 */
final class ClusterTokenMinter {

    /** Python's {@code email.utils.formatdate(usegmt=True)}: always two-digit day, literal GMT. */
    private static final DateTimeFormatter RFC_1123 =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).withZone(ZoneOffset.UTC);
    static final KeyPair KEY_PAIR = TestKeys.rsaKeyPair();

    private ClusterTokenMinter() {
    }

    /** The PEM to upload as the signing key's API key. */
    static String publicKeyPem() {
        return TestKeys.pem(KEY_PAIR.getPublic());
    }

    /** The fingerprint OCI computes for {@link #KEY_PAIR}, as it appears in the keyId. */
    static String fingerprint() {
        return OciRequestSignature.fingerprint(KEY_PAIR.getPublic());
    }

    static String mint(String region, String clusterId, String tenancyId, String userId, Instant signedAt) {
        return mintWithKeyId(region, clusterId, tenancyId + "/" + userId + "/" + fingerprint(), signedAt);
    }

    /** {@code oci ce cluster generate-token --auth security_token}: the keyId is {@code ST$<jwt>}. */
    static String mintWithSecurityToken(String region, String clusterId, String securityToken, Instant signedAt) {
        return mintWithKeyId(region, clusterId, "ST$" + securityToken, signedAt);
    }

    /** Like {@link #mintWithSecurityToken}, signed with the session key pair the token's {@code jwk} names. */
    static String mintWithSecurityToken(String region, String clusterId, String securityToken, Instant signedAt,
                                        KeyPair sessionKey) {
        return mint(region, clusterId, "ST$" + securityToken, signedAt, sessionKey);
    }

    static String mintWithKeyId(String region, String clusterId, String keyId, Instant signedAt) {
        return mint(region, clusterId, keyId, signedAt, KEY_PAIR);
    }

    static String mint(String region, String clusterId, String keyId, Instant signedAt, KeyPair signer) {
        String host = "containerengine." + region + ".oraclecloud.com";
        String path = "/cluster_request/" + clusterId;
        String date = RFC_1123.format(signedAt);
        String authorization = authorization(keyId, signature(date, path, host, signer));
        return encode("https://" + host + path
                + "?authorization=" + URLEncoder.encode(authorization, StandardCharsets.UTF_8)
                + "&date=" + URLEncoder.encode(date, StandardCharsets.UTF_8));
    }

    /**
     * A session token's {@code jwk} claim for {@code key}, as oci-cli builds it
     * ({@code cli_util.py}: {@code {"kty":"RSA","n":...,"e":...,"kid":"Ignored"}}).
     */
    static String jwk(KeyPair key) {
        RSAPublicKey publicKey = (RSAPublicKey) key.getPublic();
        Base64.Encoder url = Base64.getUrlEncoder().withoutPadding();
        return "{\"kty\":\"RSA\",\"n\":\"" + url.encodeToString(unsigned(publicKey.getModulus()))
                + "\",\"e\":\"" + url.encodeToString(unsigned(publicKey.getPublicExponent()))
                + "\",\"kid\":\"Ignored\"}";
    }

    /** The token with one character of its signature changed, still valid base64 of the same length. */
    static String tamperSignature(String token) {
        String url = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
        int query = url.indexOf("?authorization=") + "?authorization=".length();
        int end = url.indexOf("&date=");
        String authorization = URLDecoder.decode(url.substring(query, end), StandardCharsets.UTF_8);
        int signature = authorization.indexOf("signature=\"") + "signature=\"".length();
        char original = authorization.charAt(signature + 10);
        String tampered = authorization.substring(0, signature + 10) + (original == 'A' ? 'B' : 'A')
                + authorization.substring(signature + 11);
        return encode(url.substring(0, query) + URLEncoder.encode(tampered, StandardCharsets.UTF_8)
                + url.substring(end));
    }

    static String authorization(String keyId, String signature) {
        return "Signature algorithm=\"rsa-sha256\",headers=\"date (request-target) host\",keyId=\""
                + keyId + "\",signature=\"" + signature + "\",version=\"1\"";
    }

    static String encode(String url) {
        return Base64.getUrlEncoder().encodeToString(url.getBytes(StandardCharsets.UTF_8));
    }

    private static String signature(String date, String path, String host, KeyPair signer) {
        String signingString = "date: " + date + "\n(request-target): get " + path + "\nhost: " + host;
        try {
            Signature rsa = Signature.getInstance("SHA256withRSA");
            rsa.initSign(signer.getPrivate());
            rsa.update(signingString.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(rsa.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot sign the test token", e);
        }
    }

    private static byte[] unsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes[0] != 0) {
            return bytes;
        }
        byte[] trimmed = new byte[bytes.length - 1];
        System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
        return trimmed;
    }
}
