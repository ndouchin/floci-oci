package io.floci.oci.services.oke;

/**
 * Who an accepted {@code generate-token} token says the caller is.
 *
 * @param principalId the user OCID, or the instance OCID of an instance principal
 * @param tenancyId   the tenancy the token is bound to, always the cluster's
 * @param verified    whether the signature was checked against a key floci-oci knows (an uploaded
 *                    API key, or a known user's session key); an unverified caller gets round 1's
 *                    mapping, {@code system:masters}
 */
public record OkeCaller(String principalId, String tenancyId, Kind kind, boolean verified) {

    public enum Kind {
        USER,
        INSTANCE
    }
}
