package io.floci.oci.services.oke;

import io.floci.oci.core.auth.OciRequestSignature;
import io.floci.oci.services.identity.IdentityService;
import io.floci.oci.services.identity.model.StoredApiKey;
import io.floci.oci.services.identity.model.StoredCompartment;
import io.floci.oci.services.identity.model.StoredGroup;
import io.floci.oci.services.identity.model.StoredPolicy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.security.PublicKey;
import java.util.List;
import java.util.Optional;

/** Identity's users, API keys, groups, policies and compartments, read from the cluster's tenancy partition. */
@ApplicationScoped
public class OkeIdentityDirectory implements OkeKeyDirectory, OkeIamDirectory {

    private static final Logger LOG = Logger.getLogger(OkeIdentityDirectory.class);

    private final IdentityService identity;

    @Inject
    public OkeIdentityDirectory(IdentityService identity) {
        this.identity = identity;
    }

    @Override
    public Optional<PublicKey> apiKey(String tenancyId, String userId, String fingerprint) {
        return identity.apiKey(tenancyId, userId, fingerprint)
                .filter(key -> "ACTIVE".equals(key.getLifecycleState()))
                .flatMap(OkeIdentityDirectory::parse);
    }

    @Override
    public boolean hasApiKeys(String tenancyId, String userId) {
        return !identity.apiKeys(tenancyId, userId).isEmpty();
    }

    @Override
    public boolean userExists(String tenancyId, String userId) {
        return identity.user(tenancyId, userId).isPresent();
    }

    @Override
    public List<StoredGroup> groupsOf(String tenancyId, String userId) {
        return identity.groupsOf(tenancyId, userId);
    }

    @Override
    public List<StoredPolicy> policies(String tenancyId) {
        return identity.policies(tenancyId);
    }

    @Override
    public List<StoredCompartment> compartmentAncestors(String tenancyId, String compartmentId) {
        return identity.compartmentAncestors(tenancyId, compartmentId);
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
