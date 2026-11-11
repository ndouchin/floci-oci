package io.floci.oci.core.auth;

import io.floci.oci.testing.GoldenOkeToken;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OciRequestSignatureTest {

    private static final List<String> SIGNED_HEADERS = List.of("date", "(request-target)", "host");

    @Test
    void theRealOciCliTokenVerifiesWithItsPublicKey() {
        Golden golden = Golden.decode();

        assertTrue(OciRequestSignature.verify(golden.key(), golden.signingString(golden.path(), golden.host()),
                golden.signature()));
    }

    @Test
    void theSigningStringIsDateRequestTargetAndHostAsTheSdkBuildsIt() {
        Golden golden = Golden.decode();

        assertEquals("date: Wed, 11 Nov 2026 03:33:05 GMT\n"
                        + "(request-target): get /cluster_request/" + GoldenOkeToken.CLUSTER + "\n"
                        + "host: containerengine.eu-frankfurt-1.oraclecloud.com",
                golden.signingString(golden.path(), golden.host()));
    }

    @Test
    void aRequestTargetCarryingTheQueryDoesNotVerify() {
        Golden golden = Golden.decode();

        assertFalse(OciRequestSignature.verify(golden.key(),
                golden.signingString(golden.path() + "?" + golden.rawQuery(), golden.host()), golden.signature()));
    }

    @Test
    void anotherClusterOrHostDoesNotVerify() {
        Golden golden = Golden.decode();

        assertFalse(OciRequestSignature.verify(golden.key(),
                golden.signingString(golden.path().replace("spikecluster", "othercluster"), golden.host()),
                golden.signature()));
        assertFalse(OciRequestSignature.verify(golden.key(),
                golden.signingString(golden.path(), "containerengine.us-ashburn-1.oraclecloud.com"),
                golden.signature()));
    }

    @Test
    void anotherKeyDoesNotVerify() throws GeneralSecurityException {
        Golden golden = Golden.decode();
        PublicKey other = rsaKeyPair().getPublic();

        assertFalse(OciRequestSignature.verify(other, golden.signingString(golden.path(), golden.host()),
                golden.signature()));
    }

    @Test
    void aMalformedSignatureDoesNotVerify() {
        Golden golden = Golden.decode();

        assertFalse(OciRequestSignature.verify(golden.key(), golden.signingString(golden.path(), golden.host()),
                "not base64!"));
        assertFalse(OciRequestSignature.verify(golden.key(), golden.signingString(golden.path(), golden.host()), ""));
    }

    @Test
    void theFingerprintIsTheMd5OfTheDerSubjectPublicKeyInfo() {
        assertEquals(GoldenOkeToken.FINGERPRINT,
                OciRequestSignature.fingerprint(OciRequestSignature.publicKeyFromPem(GoldenOkeToken.PUBLIC_KEY_PEM)));
    }

    @Test
    void aPkcs1RsaPublicKeyParsesToTheSameKey() {
        PublicKey spki = OciRequestSignature.publicKeyFromPem(GoldenOkeToken.PUBLIC_KEY_PEM);
        // The SubjectPublicKeyInfo DER wraps the PKCS#1 RSAPublicKey after a fixed 24-byte header for 2048-bit keys.
        byte[] der = spki.getEncoded();
        byte[] pkcs1 = new byte[der.length - 24];
        System.arraycopy(der, 24, pkcs1, 0, pkcs1.length);
        String pem = "-----BEGIN RSA PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(pkcs1)
                + "\n-----END RSA PUBLIC KEY-----\n";

        assertEquals(GoldenOkeToken.FINGERPRINT,
                OciRequestSignature.fingerprint(OciRequestSignature.publicKeyFromPem(pem)));
    }

    @Test
    void textThatIsNotAPublicKeyPemIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> OciRequestSignature.publicKeyFromPem("garbage"));
        assertThrows(IllegalArgumentException.class, () -> OciRequestSignature.publicKeyFromPem(""));
        assertThrows(IllegalArgumentException.class, () -> OciRequestSignature.publicKeyFromPem(
                "-----BEGIN PUBLIC KEY-----\nAAAA\n-----END PUBLIC KEY-----\n"));
    }

    @Test
    void aSessionTokenSignatureVerifiesAgainstTheKeyInItsJwkClaim() throws GeneralSecurityException {
        KeyPair session = rsaKeyPair();
        RSAPublicKey sessionKey = (RSAPublicKey) session.getPublic();
        Base64.Encoder url = Base64.getUrlEncoder().withoutPadding();
        // oci-cli cli_util.py builds the session JWK as {"kty":"RSA","n":...,"e":...,"kid":"Ignored"}.
        String n = url.encodeToString(unsigned(sessionKey.getModulus()));
        String e = url.encodeToString(unsigned(sessionKey.getPublicExponent()));
        String path = "/cluster_request/" + GoldenOkeToken.CLUSTER;
        String host = "containerengine." + GoldenOkeToken.REGION + ".oraclecloud.com";
        String signingString = OciRequestSignature.signingString("GET", path, SIGNED_HEADERS,
                Map.of("date", "Wed, 11 Nov 2026 03:33:05 GMT", "host", host));
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(session.getPrivate());
        signer.update(signingString.getBytes(StandardCharsets.US_ASCII));
        String signature = Base64.getEncoder().encodeToString(signer.sign());

        assertTrue(OciRequestSignature.verify(OciRequestSignature.publicKeyFromJwk(n, e), signingString, signature));
        assertFalse(OciRequestSignature.verify(OciRequestSignature.publicKeyFromJwk(n, "AQAD"), signingString,
                signature));
    }

    @Test
    void aSignedHeaderWithoutAValueIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> OciRequestSignature.signingString("GET", "/x",
                SIGNED_HEADERS, Map.of("date", "Wed, 11 Nov 2026 03:33:05 GMT")));
    }

    private static byte[] unsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes[0] == 0) {
            byte[] trimmed = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return bytes;
    }

    private static KeyPair rsaKeyPair() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    /** The parts of the golden token a verifier needs, decoded the way {@code OkeTokenValidator} decodes them. */
    private record Golden(PublicKey key, String path, String host, String rawQuery, String date, String signature) {

        static Golden decode() {
            URI uri = URI.create(new String(Base64.getUrlDecoder().decode(GoldenOkeToken.TOKEN), StandardCharsets.UTF_8));
            Map<String, String> query = new LinkedHashMap<>();
            for (String pair : uri.getRawQuery().split("&")) {
                int equals = pair.indexOf('=');
                query.put(pair.substring(0, equals),
                        URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
            }
            Map<String, String> signatureParams = OciSignatureParser.parameters(query.get("authorization"));
            return new Golden(OciRequestSignature.publicKeyFromPem(GoldenOkeToken.PUBLIC_KEY_PEM), uri.getRawPath(),
                    uri.getHost(), uri.getRawQuery(), query.get("date"), signatureParams.get("signature"));
        }

        String signingString(String requestTarget, String signedHost) {
            return OciRequestSignature.signingString("GET", requestTarget, SIGNED_HEADERS,
                    Map.of("date", date, "host", signedHost));
        }
    }
}
