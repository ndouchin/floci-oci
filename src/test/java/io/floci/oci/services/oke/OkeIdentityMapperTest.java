package io.floci.oci.services.oke;

import io.floci.oci.services.identity.model.StoredCompartment;
import io.floci.oci.services.identity.model.StoredGroup;
import io.floci.oci.services.identity.model.StoredPolicy;
import io.floci.oci.services.oke.model.StoredOkeCluster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class OkeIdentityMapperTest {

    private static final String TENANCY = "ocid1.tenancy.oc1..mapper";
    private static final String OTHER_TENANCY = "ocid1.tenancy.oc1..othermapper";
    private static final String COMPARTMENT = "ocid1.compartment.oc1..mapperteam";
    private static final String USER = "ocid1.user.oc1..mapperuser";

    private FakeIam iam;
    private OkeIdentityMapper mapper;
    private StoredOkeCluster cluster;

    @BeforeEach
    void setUp() {
        iam = new FakeIam();
        mapper = new OkeIdentityMapper(iam);
        cluster = new StoredOkeCluster();
        cluster.setId("ocid1.cluster.oc1.iad.mapper");
        cluster.setTenancyId(TENANCY);
        cluster.setCompartmentId(COMPARTMENT);
        StoredCompartment team = new StoredCompartment();
        team.setId(COMPARTMENT);
        team.setCompartmentId(TENANCY);
        team.setName("team");
        StoredCompartment root = new StoredCompartment();
        root.setId(TENANCY);
        root.setCompartmentId(TENANCY);
        iam.chains.put(TENANCY + "/" + COMPARTMENT, List.of(team, root));
    }

    @Test
    void anAdministratorsMemberIsClusterAdmin() {
        StoredGroup administrators = iam.join(TENANCY, "ocid1.group.oc1..admins", "Administrators");

        OkeIdentityMapper.KubeUser user = mapper.map(verified(), cluster);

        assertEquals(USER, user.username());
        assertEquals(USER, user.uid());
        assertEquals(List.of(administrators.getId(), "system:masters", "system:authenticated"), user.groups());
    }

    @Test
    void aManageClustersGrantMakesClusterAdmin() {
        StoredGroup devs = iam.join(TENANCY, "ocid1.group.oc1..devs", "Devs");
        iam.policy(TENANCY, TENANCY, "Allow group Devs to manage clusters in compartment team");

        assertEquals(List.of(devs.getId(), "system:masters", "system:authenticated"),
                mapper.map(verified(), cluster).groups());
    }

    @Test
    void anyOtherVerifiedUserGetsItsGroupOcidsAndSystemAuthenticatedOnly() {
        StoredGroup devs = iam.join(TENANCY, "ocid1.group.oc1..devs", "Devs");
        StoredGroup ops = iam.join(TENANCY, "ocid1.group.oc1..ops", "Ops");
        iam.policy(TENANCY, TENANCY, "Allow group Devs to use clusters in compartment team");

        OkeIdentityMapper.KubeUser user = mapper.map(verified(), cluster);

        assertEquals(List.of(devs.getId(), ops.getId(), "system:authenticated"), user.groups());
        assertEquals(Map.of("AuthType", List.of("Native"), "ContextType", List.of("OCI"),
                "Principal", List.of(USER), "TenancyID", List.of(TENANCY), "TokenV2", List.of("true")), user.extra());
    }

    @Test
    void anotherTenancysPolicyAndGroupsDoNotCount() {
        iam.join(OTHER_TENANCY, "ocid1.group.oc1..otheradmins", "Administrators");
        iam.policy(OTHER_TENANCY, OTHER_TENANCY, "Allow any-user to manage all-resources in tenancy");

        assertEquals(List.of("system:authenticated"), mapper.map(verified(), cluster).groups());
    }

    @Test
    void anUnverifiedCallerKeepsRoundOnesClusterAdminMapping() {
        iam.join(TENANCY, "ocid1.group.oc1..devs", "Devs");

        OkeIdentityMapper.KubeUser user = mapper.map(new OkeCaller(USER, TENANCY, OkeCaller.Kind.USER, false), cluster);

        assertEquals(USER, user.username());
        assertEquals(List.of("system:masters", "system:authenticated"), user.groups());
        assertNull(user.extra());
    }

    @Test
    void theWireFormIsATokenReviewUser() {
        iam.join(TENANCY, "ocid1.group.oc1..devs", "Devs");

        Map<String, Object> wire = mapper.map(verified(), cluster).toWire();

        assertEquals(USER, wire.get("username"));
        assertEquals(USER, wire.get("uid"));
        assertEquals(List.of("ocid1.group.oc1..devs", "system:authenticated"), wire.get("groups"));
        assertEquals(List.of("Native"), ((Map<?, ?>) wire.get("extra")).get("AuthType"));
    }

    private static OkeCaller verified() {
        return new OkeCaller(USER, TENANCY, OkeCaller.Kind.USER, true);
    }

    private static final class FakeIam implements OkeIamDirectory {
        private final Map<String, List<StoredGroup>> groups = new HashMap<>();
        private final Map<String, List<StoredPolicy>> policies = new HashMap<>();
        private final Map<String, List<StoredCompartment>> chains = new HashMap<>();

        StoredGroup join(String tenancy, String id, String name) {
            StoredGroup group = new StoredGroup();
            group.setId(id);
            group.setName(name);
            group.setLifecycleState("ACTIVE");
            groups.computeIfAbsent(tenancy, t -> new ArrayList<>()).add(group);
            return group;
        }

        void policy(String tenancy, String compartmentId, String statement) {
            StoredPolicy policy = new StoredPolicy();
            policy.setId("ocid1.policy.oc1.." + Math.abs(statement.hashCode()));
            policy.setCompartmentId(compartmentId);
            policy.setStatements(List.of(statement));
            policy.setLifecycleState("ACTIVE");
            policies.computeIfAbsent(tenancy, t -> new ArrayList<>()).add(policy);
        }

        @Override
        public List<StoredGroup> groupsOf(String tenancyId, String userId) {
            return groups.getOrDefault(tenancyId, List.of());
        }

        @Override
        public List<StoredPolicy> policies(String tenancyId) {
            return policies.getOrDefault(tenancyId, List.of());
        }

        @Override
        public List<StoredCompartment> compartmentAncestors(String tenancyId, String compartmentId) {
            return chains.getOrDefault(tenancyId + "/" + compartmentId, List.of());
        }
    }
}
