package io.floci.oci.services.oke;

import io.floci.oci.services.identity.model.StoredCompartment;
import io.floci.oci.services.identity.model.StoredGroup;
import io.floci.oci.services.identity.model.StoredPolicy;

import java.util.List;

/** The IAM records OKE's caller mapping reads, by the cluster's tenancy. */
public interface OkeIamDirectory {

    List<StoredGroup> groupsOf(String tenancyId, String userId);

    List<StoredPolicy> policies(String tenancyId);

    /** The compartment and those above it, nearest first, ending with the root (the tenancy). */
    List<StoredCompartment> compartmentAncestors(String tenancyId, String compartmentId);
}
