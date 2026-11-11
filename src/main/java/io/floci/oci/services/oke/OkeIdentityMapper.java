package io.floci.oci.services.oke;

import io.floci.oci.services.identity.model.StoredGroup;
import io.floci.oci.services.oke.model.StoredOkeCluster;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Kubernetes user an accepted {@code generate-token} caller becomes, as OKE documents it
 * ("About Access Control and Kubernetes Engine"): username and uid are the principal's OCID, the
 * groups are the caller's IAM group OCIDs (which RBAC bindings name) plus
 * {@code system:authenticated}, and a caller holding {@code CLUSTER_MANAGE} on the cluster, tenancy
 * administrators included, also gets {@code system:masters}, which is how "tenancy administrators
 * already have sufficient privileges" shows on real clusters (third-party {@code kubectl auth whoami}
 * output; Oracle does not document the group list). An administrator is a member of the tenancy's
 * group named {@code Administrators} (every OCI tenancy has one) or of a group a policy of the
 * cluster's tenancy lets {@code manage clusters} where the cluster is ({@link OkeIamGrants}).
 *
 * <p>A caller whose signature was not verified keeps round 1's mapping: {@code system:masters}.
 */
@ApplicationScoped
public class OkeIdentityMapper {

    private static final Logger LOG = Logger.getLogger(OkeIdentityMapper.class);
    static final String SYSTEM_MASTERS = "system:masters";
    static final String SYSTEM_AUTHENTICATED = "system:authenticated";
    static final String ADMINISTRATORS_GROUP = "Administrators";
    private static final int MAX_HINTED_USERS = 1024;

    private final OkeIamDirectory iam;
    private final Set<String> hintedUsers = ConcurrentHashMap.newKeySet();

    @Inject
    public OkeIdentityMapper(OkeIamDirectory iam) {
        this.iam = iam;
    }

    /** A TokenReview's {@code status.user}; {@code extra} is null for an unverified caller. */
    public record KubeUser(String username, String uid, List<String> groups, Map<String, List<String>> extra) {

        public Map<String, Object> toWire() {
            Map<String, Object> wire = new LinkedHashMap<>();
            wire.put("username", username);
            wire.put("uid", uid);
            wire.put("groups", groups);
            if (extra != null) {
                wire.put("extra", extra);
            }
            return wire;
        }
    }

    public KubeUser map(OkeCaller caller, StoredOkeCluster cluster) {
        if (!caller.verified()) {
            return new KubeUser(caller.principalId(), caller.principalId(),
                    List.of(SYSTEM_MASTERS, SYSTEM_AUTHENTICATED), null);
        }
        String tenancy = cluster.getTenancyId();
        List<StoredGroup> memberOf = iam.groupsOf(tenancy, caller.principalId());
        boolean administrator = memberOf.stream().anyMatch(group -> ADMINISTRATORS_GROUP.equalsIgnoreCase(group.getName()))
                || OkeIamGrants.managesClusters(iam.policies(tenancy), memberOf,
                        iam.compartmentAncestors(tenancy, cluster.getCompartmentId()));
        List<String> groups = new ArrayList<>();
        memberOf.forEach(group -> groups.add(group.getId()));
        if (administrator) {
            groups.add(SYSTEM_MASTERS);
        } else {
            hintOnce(caller, cluster);
        }
        groups.add(SYSTEM_AUTHENTICATED);
        Map<String, List<String>> extra = new LinkedHashMap<>();
        extra.put("AuthType", List.of("Native"));
        extra.put("ContextType", List.of("OCI"));
        extra.put("Principal", List.of(caller.principalId()));
        extra.put("TenancyID", List.of(tenancy));
        extra.put("TokenV2", List.of("true"));
        return new KubeUser(caller.principalId(), caller.principalId(), List.copyOf(groups), extra);
    }

    /**
     * Logs once per user what OKE leaves implicit: a verified user without {@code manage clusters} has
     * only what RBAC binds to it. floci-oci's default tenancy has no {@code Administrators} group, so
     * a user who uploads a key goes from cluster-admin to this until they get one.
     */
    private void hintOnce(OkeCaller caller, StoredOkeCluster cluster) {
        String key = cluster.getTenancyId() + "/" + caller.principalId();
        if (hintedUsers.size() >= MAX_HINTED_USERS || !hintedUsers.add(key)) {
            return;
        }
        LOG.infov("OKE cluster {0}: {1} signed with a registered key but holds no ''manage clusters'' grant, so it has"
                + " only what Kubernetes RBAC binds to its user OCID or its group OCIDs, as on OKE. To make it a"
                + " cluster administrator, add it to a group named Administrators in tenancy {2} (create the group"
                + " if the tenancy has none), or give one of its groups a policy such as"
                + " ''Allow group <name> to manage cluster-family in tenancy''.",
                cluster.getId(), caller.principalId(), cluster.getTenancyId());
    }
}
