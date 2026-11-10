package io.floci.oci.services.oke;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * The Kubernetes objects every cluster gets from k3s's auto-deploy directory: the storage classes
 * OKE defines, {@code oci-bv} (default) and {@code oci}, and the local-path-provisioner instances
 * that serve them under OKE's provisioner names.
 */
final class OkeManifests {

    private static final String STORAGE = load("/oke/storage.yaml");

    private OkeManifests() {
    }

    static String clusterManifests() {
        return STORAGE;
    }

    private static String load(String resource) {
        try (InputStream in = OkeManifests.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + resource, e);
        }
    }
}
