package io.floci.oci.testing;

import java.time.Instant;

/**
 * A token produced by the real {@code oci ce cluster generate-token} (oci-cli 3.96.2, oci SDK
 * 2.191.0) with a throwaway 2048-bit API key and a fake tenancy, user and cluster, signed at
 * {@link #SIGNED_AT}. It is what kubectl sends to OKE's API server, and the only fixture here that
 * comes from Oracle's own signer rather than floci-oci's test minter.
 */
public final class GoldenOkeToken {

    public static final Instant SIGNED_AT = Instant.parse("2026-11-11T03:33:05Z");
    public static final String REGION = "eu-frankfurt-1";
    public static final String TENANCY = "ocid1.tenancy.oc1..aaaaaaaaspiketenancy";
    public static final String USER = "ocid1.user.oc1..aaaaaaaaspikeuser";
    public static final String CLUSTER = "ocid1.cluster.oc1.eu-frankfurt-1.aaaaaaaaspikecluster";
    /** {@code openssl rsa -pubout -outform DER | openssl md5 -c} of the key, as the CLI put it in the keyId. */
    public static final String FINGERPRINT = "68:84:3f:c2:f2:6b:45:af:64:91:19:7a:5d:ee:14:eb";
    public static final String KEY_ID = TENANCY + "/" + USER + "/" + FINGERPRINT;

    public static final String PUBLIC_KEY_PEM = """
            -----BEGIN PUBLIC KEY-----
            MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEArKEVA4SlozIIeQjy/RG1
            J4IXXkGNDzJaBdh7Navu2aMF0REDJAEy792wYDrP2gWIcyVdzkEnveSEq4yEzE18
            UnsdD22R+BJ4hd7wXbHqI1vZFpCs/80Loz4Q80jPxxjVXrUm/IsJpYa2AHpHymN8
            WCRIwt9Rla+LpbguImU4RcLcqbsdS3gLE5F1SawmuSEDyGASEhQqB445DeCsMN87
            9uf6jXRRP66bVNBXL4mVkxWmtzO9/eUgJH93kF5d5kEwASMiM7zr91W43I4pc2S8
            yKV8MnZ3jTx2SNOPPNnTENTtmGAlF32NRPQmLbnCMf8/wfOS0EnXx2h+5R6PNVEw
            rwIDAQAB
            -----END PUBLIC KEY-----
            """;

    /**
     * The {@code status.token} of the ExecCredential the CLI printed: a signed request for a fake cluster with a
     * throwaway key, long expired, so no credential.
     */
    public static final String TOKEN = "aHR0cHM6Ly9jb250YWluZXJlbmdpbmUuZXUtZnJhbmtmdXJ0LTEub3JhY2xlY2xvdWQuY29tL2NsdXN0ZXJfcmVxdWVzdC9vY2lkMS5jbHVzdGVyLm9jMS5ldS1mcmFua2Z1cnQtMS5hYWFhYWFhYXNwaWtlY2x1c3Rlcj9hdXRob3JpemF0aW9uPVNpZ25hdHVyZSthbGdvcml0aG0lM0QlMjJyc2Etc2hhMjU2JTIyJTJDaGVhZGVycyUzRCUyMmRhdGUrJTI4cmVxdWVzdC10YXJnZXQlMjkraG9zdCUyMiUyQ2tleUlkJTNEJTIyb2NpZDEudGVuYW5jeS5vYzEuLmFhYWFhYWFhc3Bpa2V0ZW5hbmN5JTJGb2NpZDEudXNlci5vYzEuLmFhYWFhYWFhc3Bpa2V1c2VyJTJGNjglM0E4NCUzQTNmJTNBYzIlM0FmMiUzQTZiJTNBNDUlM0FhZiUzQTY0JTNBOTElM0ExOSUzQTdhJTNBNWQlM0FlZSUzQTE0JTNBZWIlMjIlMkNzaWduYXR1cmUlM0QlMjJHcFdJV1JXTDgwQUpPeUhpdmpxVEVEcmpoMVBXM2UyUTZCNjFOZEtpVjRkYnNFQVcySzM1TGVwUXp1SXolMkJZOE5mWDRhWVJ3WW9iclZocXpZRFVKZkpUdnVLSmt6Zm5EQiUyQjNJeGZyemVTR0pjUnQzejhMVCUyRlQlMkJkR3ZpbmhRRHpERm04ZTBKQ3B1S0VnbzdaSVU3NDlRMFladlpmbmhQdExHclI2M3ZiUnZtQzJ2ZFhSRSUyRnJZYlAwNzVxaTFoQnUlMkZ5cmZWNFVDYXBkYXFUNnkyM1lWNU1vNlhReldDbyUyRnh3VVFMaE1ER0pJVHlpVEJZSDBTMTFFVGRzJTJCelkyN1M3bzdLJTJGeGpKdXdKM2FMeXJSRGNHU1ZkcVBDJTJGN3d1aGNVTU9tSHdDVWdwTU1CaU9sVXQlMkJRRTFJWnNkaE1sdXJNSGVza21XNnRFNVNGYmRrRDhVOUM3YjdBJTNEJTNEJTIyJTJDdmVyc2lvbiUzRCUyMjElMjImZGF0ZT1XZWQlMkMrMTErTm92KzIwMjYrMDMlM0EzMyUzQTA1K0dNVA=="; // gitleaks:allow

    private GoldenOkeToken() {
    }
}
