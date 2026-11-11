package io.floci.oci.services.oke;

import java.util.Locale;

/** {@code floci-oci.services.oke.token-verification}: how far the token webhook checks a token's signature. */
public enum OkeTokenVerification {

    /**
     * A token signed with an API key uploaded to identity, or with the session key of a session token
     * naming a known user, is verified and mapped as OKE maps callers; any other well-formed token keeps
     * round 1's behavior (accepted as cluster-admin).
     */
    KNOWN_KEYS("known-keys"),

    /** Only verified tokens are accepted: unregistered keys, session and instance principal tokens are refused. */
    STRICT("strict"),

    /** The token's shape alone is checked and every caller is cluster-admin, as in round 1. */
    OFF("off");

    private final String value;

    OkeTokenVerification(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    static OkeTokenVerification parse(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        for (OkeTokenVerification mode : values()) {
            if (mode.value.equals(normalized)) {
                return mode;
            }
        }
        throw new IllegalStateException("Invalid floci-oci.services.oke.token-verification value '" + value
                + "': allowed values are known-keys, strict and off");
    }
}
