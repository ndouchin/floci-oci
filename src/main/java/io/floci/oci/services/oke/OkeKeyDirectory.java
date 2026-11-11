package io.floci.oci.services.oke;

import java.security.PublicKey;
import java.util.Optional;

/** The users and API signing keys OKE's token verification reads, by the cluster's tenancy. */
public interface OkeKeyDirectory {

    /** The {@code ACTIVE} API key {@code fingerprint} of {@code userId}. */
    Optional<PublicKey> apiKey(String tenancyId, String userId, String fingerprint);

    /** Whether {@code userId} has uploaded any API key. */
    boolean hasApiKeys(String tenancyId, String userId);

    boolean userExists(String tenancyId, String userId);
}
