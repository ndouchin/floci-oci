package io.floci.oci.services.oke;

import io.floci.oci.services.identity.model.StoredCompartment;
import io.floci.oci.services.identity.model.StoredGroup;
import io.floci.oci.services.identity.model.StoredPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Policy syntax: "Allow &lt;subject&gt; to &lt;verb&gt; &lt;resource-type&gt; in &lt;location&gt; where
 * &lt;conditions&gt;" (Oracle's Policy Syntax page); paths per "Policies and Compartment Hierarchies";
 * CLUSTER_MANAGE through {@code manage clusters} and the {@code cluster-family} aggregate (Container
 * Engine policy reference).
 */
class OkeIamGrantsTest {

    private static final String TENANCY = "ocid1.tenancy.oc1..grants";
    private static final String TEAM = "ocid1.compartment.oc1..team";
    private static final String PROJECT = "ocid1.compartment.oc1..project";
    private static final String SIBLING = "ocid1.compartment.oc1..sibling";
    private static final StoredGroup DEVS = group("ocid1.group.oc1..devs", "Devs");

    /** tenancy > team > project, and tenancy > sibling. */
    private static final List<StoredCompartment> PROJECT_CHAIN = List.of(
            compartment(PROJECT, TEAM, "project"), compartment(TEAM, TENANCY, "team"), root());
    private static final List<StoredCompartment> TEAM_CHAIN = List.of(compartment(TEAM, TENANCY, "team"), root());
    private static final List<StoredCompartment> SIBLING_CHAIN = List.of(
            compartment(SIBLING, TENANCY, "sibling"), root());
    private static final List<StoredCompartment> ROOT_CHAIN = List.of(root());

    @Test
    void manageClustersInACompartmentCoversItAndItsChildrenButNotASibling() {
        List<StoredPolicy> policies = List.of(policy(TENANCY, "Allow group Devs to manage clusters in compartment team"));

        assertTrue(OkeIamGrants.managesClusters(policies, List.of(DEVS), TEAM_CHAIN));
        assertTrue(OkeIamGrants.managesClusters(policies, List.of(DEVS), PROJECT_CHAIN));
        assertFalse(OkeIamGrants.managesClusters(policies, List.of(DEVS), SIBLING_CHAIN));
        assertFalse(OkeIamGrants.managesClusters(policies, List.of(DEVS), ROOT_CHAIN));
    }

    @Test
    void useAndReadClustersDoNotGrantManage() {
        for (String verb : List.of("inspect", "read", "use")) {
            assertFalse(OkeIamGrants.managesClusters(
                    List.of(policy(TENANCY, "Allow group Devs to " + verb + " clusters in tenancy")),
                    List.of(DEVS), PROJECT_CHAIN), verb);
        }
    }

    @Test
    void clusterFamilyAndAllResourcesGrantManage() {
        assertTrue(OkeIamGrants.managesClusters(
                List.of(policy(TENANCY, "Allow group Devs to manage cluster-family in compartment team:project")),
                List.of(DEVS), PROJECT_CHAIN));
        assertTrue(OkeIamGrants.managesClusters(
                List.of(policy(TENANCY, "ALLOW GROUP Devs to MANAGE all-resources IN TENANCY")),
                List.of(DEVS), PROJECT_CHAIN));
        assertFalse(OkeIamGrants.managesClusters(
                List.of(policy(TENANCY, "Allow group Devs to manage instance-family in tenancy")),
                List.of(DEVS), PROJECT_CHAIN));
    }

    @Test
    void subjectsByOcidListQuotedDomainAnyUserAndAnyGroupMatch() {
        assertTrue(OkeIamGrants.managesClusters(
                List.of(policy(TENANCY, "Allow group id ocid1.group.oc1..devs to manage clusters in tenancy")),
                List.of(DEVS), PROJECT_CHAIN));
        assertTrue(OkeIamGrants.managesClusters(
                List.of(policy(TENANCY, "Allow group Ops, Devs to manage clusters in tenancy")),
                List.of(DEVS), PROJECT_CHAIN));
        assertTrue(OkeIamGrants.managesClusters(
                List.of(policy(TENANCY, "Allow group id ocid1.group.oc1..ops, id ocid1.group.oc1..devs to manage"
                        + " clusters in tenancy")),
                List.of(DEVS), PROJECT_CHAIN));
        assertTrue(OkeIamGrants.managesClusters(
                List.of(policy(TENANCY, "Allow group 'Default'/'Devs' to manage clusters in tenancy")),
                List.of(DEVS), PROJECT_CHAIN));
        assertFalse(OkeIamGrants.managesClusters(
                List.of(policy(TENANCY, "Allow group 'OtherDomain'/'Devs' to manage clusters in tenancy")),
                List.of(DEVS), PROJECT_CHAIN));
        assertTrue(OkeIamGrants.managesClusters(
                List.of(policy(TENANCY, "Allow any-user to manage clusters in tenancy")), List.of(), PROJECT_CHAIN));
        assertTrue(OkeIamGrants.managesClusters(
                List.of(policy(TENANCY, "Allow any-group to manage clusters in tenancy")),
                List.of(DEVS), PROJECT_CHAIN));
        assertFalse(OkeIamGrants.managesClusters(
                List.of(policy(TENANCY, "Allow group Ops to manage clusters in tenancy")),
                List.of(DEVS), PROJECT_CHAIN));
        assertFalse(OkeIamGrants.managesClusters(
                List.of(policy(TENANCY, "Allow dynamic-group Devs to manage clusters in tenancy")),
                List.of(DEVS), PROJECT_CHAIN));
    }

    @Test
    void aStatementWithAConditionNeverGrants() {
        assertFalse(OkeIamGrants.managesClusters(
                List.of(policy(TENANCY, "Allow group Devs to manage clusters in tenancy where"
                        + " request.permission = 'CLUSTER_MANAGE'")),
                List.of(DEVS), PROJECT_CHAIN));
    }

    @Test
    void locationsResolveRelativeToThePolicysCompartment() {
        // Attached to team: team names itself, its child by name; the tenancy is out of its reach.
        assertTrue(OkeIamGrants.managesClusters(
                List.of(policy(TEAM, "Allow group Devs to manage clusters in compartment team")),
                List.of(DEVS), PROJECT_CHAIN));
        assertTrue(OkeIamGrants.managesClusters(
                List.of(policy(TEAM, "Allow group Devs to manage clusters in compartment project")),
                List.of(DEVS), PROJECT_CHAIN));
        assertFalse(OkeIamGrants.managesClusters(
                List.of(policy(TEAM, "Allow group Devs to manage clusters in compartment project")),
                List.of(DEVS), TEAM_CHAIN));
        assertFalse(OkeIamGrants.managesClusters(
                List.of(policy(TEAM, "Allow group Devs to manage clusters in tenancy")),
                List.of(DEVS), PROJECT_CHAIN));
        // Attached to the tenancy, a grandchild needs its path.
        assertFalse(OkeIamGrants.managesClusters(
                List.of(policy(TENANCY, "Allow group Devs to manage clusters in compartment project")),
                List.of(DEVS), PROJECT_CHAIN));
        assertTrue(OkeIamGrants.managesClusters(
                List.of(policy(TENANCY, "Allow group Devs to manage clusters in compartment id " + PROJECT)),
                List.of(DEVS), PROJECT_CHAIN));
        // A policy in a sibling compartment reaches nothing on this path.
        assertFalse(OkeIamGrants.managesClusters(
                List.of(policy(SIBLING, "Allow group Devs to manage clusters in compartment sibling")),
                List.of(DEVS), PROJECT_CHAIN));
    }

    @Test
    void textThatIsNotAnAllowStatementIsIgnored() {
        assertFalse(OkeIamGrants.managesClusters(List.of(policy(TENANCY,
                "Define tenancy Other as ocid1.tenancy.oc1..other",
                "Endorse group Devs to manage clusters in tenancy Other",
                "Allow group Devs to {CLUSTER_MANAGE} in tenancy",
                "Allow group Devs manage clusters in tenancy",
                "")), List.of(DEVS), PROJECT_CHAIN));
    }

    private static StoredPolicy policy(String compartmentId, String... statements) {
        StoredPolicy policy = new StoredPolicy();
        policy.setId("ocid1.policy.oc1.." + Math.abs(String.join("", statements).hashCode()));
        policy.setCompartmentId(compartmentId);
        policy.setStatements(List.of(statements));
        policy.setLifecycleState("ACTIVE");
        return policy;
    }

    private static StoredGroup group(String id, String name) {
        StoredGroup group = new StoredGroup();
        group.setId(id);
        group.setName(name);
        group.setLifecycleState("ACTIVE");
        return group;
    }

    private static StoredCompartment compartment(String id, String parent, String name) {
        StoredCompartment compartment = new StoredCompartment();
        compartment.setId(id);
        compartment.setCompartmentId(parent);
        compartment.setName(name);
        compartment.setLifecycleState("ACTIVE");
        return compartment;
    }

    private static StoredCompartment root() {
        StoredCompartment root = new StoredCompartment();
        root.setId(TENANCY);
        root.setCompartmentId(TENANCY);
        return root;
    }
}
