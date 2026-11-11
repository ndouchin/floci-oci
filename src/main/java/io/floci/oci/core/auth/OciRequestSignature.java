package io.floci.oci.core.auth;

import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Verifies OCI request signatures (draft-cavage HTTP Signatures as OCI profiles them, "Request
 * Signatures" in Oracle's API docs): the signing string is one {@code name: value} line per signed
 * header, in the order of the Signature's {@code headers} parameter, joined with {@code \n}, where
 * {@code (request-target)} is the lower-case method, a space and the path with its query
 * (oci-python-sdk {@code httpsig_cffi/utils.py generate_message}); the signature is RSA PKCS#1 v1.5
 * over SHA-256, base64-encoded.
 *
 * <p>Also reads the public keys OCI knows a caller by: an API key's PEM (SubjectPublicKeyInfo, or
 * PKCS#1 {@code RSA PUBLIC KEY}), a session token's {@code jwk} claim, and the key fingerprint OCI
 * puts in a keyId, the colon-separated MD5 of the DER SubjectPublicKeyInfo ("Required Keys and
 * OCIDs": {@code openssl rsa -pubout -outform DER | openssl md5 -c}).
 */
public final class OciRequestSignature {

    public static final String REQUEST_TARGET = "(request-target)";

    private OciRequestSignature() {
    }

    /**
     * The string a signature over {@code headerNames} covers.
     *
     * @param requestTarget the raw path, with the query when the request had one
     * @param headerValues  the value of every signed header but {@code (request-target)}, by lower-case name
     * @throws IllegalArgumentException when a signed header has no value
     */
    public static String signingString(String method, String requestTarget, List<String> headerNames,
                                       Map<String, String> headerValues) {
        List<String> lines = new ArrayList<>(headerNames.size());
        for (String name : headerNames) {
            String header = name.toLowerCase(Locale.ROOT);
            if (REQUEST_TARGET.equals(header)) {
                lines.add(REQUEST_TARGET + ": " + method.toLowerCase(Locale.ROOT) + " " + requestTarget);
                continue;
            }
            String value = headerValues.get(header);
            if (value == null) {
                throw new IllegalArgumentException("Signed header " + header + " has no value");
            }
            lines.add(header + ": " + value);
        }
        return String.join("\n", lines);
    }

    /** Whether {@code base64Signature} is a valid {@code rsa-sha256} signature of {@code signingString} by {@code key}. */
    public static boolean verify(PublicKey key, String signingString, String base64Signature) {
        byte[] signature;
        try {
            signature = Base64.getDecoder().decode(base64Signature);
        } catch (IllegalArgumentException notBase64) {
            return false;
        }
        if (signature.length == 0) {
            return false;
        }
        try {
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(key);
            verifier.update(signingString.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    /**
     * The public key of a PEM document: {@code PUBLIC KEY} (SubjectPublicKeyInfo, what
     * {@code openssl rsa -pubout} writes and OCI asks for) or {@code RSA PUBLIC KEY} (PKCS#1).
     * The key may be of any algorithm; callers that need RSA check for {@link RSAPublicKey}.
     *
     * @throws IllegalArgumentException when the text holds no readable public key
     */
    public static PublicKey publicKeyFromPem(String pem) {
        if (pem == null || pem.isBlank()) {
            throw new IllegalArgumentException("The key is empty");
        }
        Object parsed;
        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            parsed = parser.readObject();
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("The key is not a readable PEM public key: " + e.getMessage(), e);
        }
        if (!(parsed instanceof SubjectPublicKeyInfo info)) {
            throw new IllegalArgumentException("The key is not a PEM public key");
        }
        try {
            return new JcaPEMKeyConverter().getPublicKey(info);
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("The key is not a readable PEM public key: " + e.getMessage(), e);
        }
    }

    /**
     * The RSA key of a JWK's base64url {@code n} and {@code e}, as a session token's {@code jwk}
     * claim carries it (oci-cli {@code cli_util.py}, oci-java-sdk {@code SecurityTokenAdapter}).
     *
     * @throws IllegalArgumentException when either value is not base64url or no RSA key
     */
    public static RSAPublicKey publicKeyFromJwk(String n, String e) {
        try {
            Base64.Decoder url = Base64.getUrlDecoder();
            RSAPublicKeySpec spec = new RSAPublicKeySpec(new BigInteger(1, url.decode(n)),
                    new BigInteger(1, url.decode(e)));
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(spec);
        } catch (IllegalArgumentException | NullPointerException | GeneralSecurityException ex) {
            throw new IllegalArgumentException("The JWK is not an RSA key: " + ex.getMessage(), ex);
        }
    }

    /** The fingerprint OCI identifies {@code key} by: lower-case hex of the MD5 of its DER SubjectPublicKeyInfo, colon-separated. */
    public static String fingerprint(PublicKey key) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(key.getEncoded());
            return HexFormat.ofDelimiter(":").formatHex(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("MD5 is not available", e);
        }
    }

    /** The key's size in bits, or 0 when it is not an RSA key. */
    public static int rsaBits(PublicKey key) {
        return key instanceof RSAPublicKey rsa ? rsa.getModulus().bitLength() : 0;
    }
}
