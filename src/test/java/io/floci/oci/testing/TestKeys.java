package io.floci.oci.testing;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.util.Base64;

/** Fresh public keys in the PEM form {@code openssl rsa -pubout} writes, for API key uploads. */
public final class TestKeys {

    private TestKeys() {
    }

    public static String rsaPublicPem(int bits) {
        return pem(keyPair("RSA", bits).getPublic());
    }

    public static String ecPublicPem() {
        return pem(keyPair("EC", 256).getPublic());
    }

    public static KeyPair rsaKeyPair() {
        return keyPair("RSA", 2048);
    }

    /** A SubjectPublicKeyInfo PEM ({@code BEGIN PUBLIC KEY}), 64 characters per line. */
    public static String pem(PublicKey key) {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(key.getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
    }

    private static KeyPair keyPair(String algorithm, int size) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(algorithm);
            generator.initialize(size);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot generate a " + algorithm + " test key", e);
        }
    }
}
