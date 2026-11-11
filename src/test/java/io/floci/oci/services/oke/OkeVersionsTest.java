package io.floci.oci.services.oke;

import io.floci.oci.core.common.OciException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OkeVersionsTest {

    @Test
    void everySupportedVersionRunsOnTheK3sImageOfTheSamePatch() {
        for (OkeVersions.Version version : OkeVersions.SUPPORTED) {
            assertEquals("rancher/k3s:" + version.name() + "-k3s1", OkeVersions.image("rancher/k3s", version.name()));
        }
        assertEquals("mirror.example/k3s:v1.30.1-k3s1", OkeVersions.image("mirror.example/k3s", "v1.30.1"));
    }

    @Test
    void optionsListTheNewestPatchOfEachMinorAndThePreviews() {
        assertEquals(List.of("v1.34.10", "v1.35.0", "v1.35.2", "v1.36.4", "v1.37.0"), OkeVersions.advertised(true));
        assertEquals(List.of("v1.34.10", "v1.35.0", "v1.35.2", "v1.36.4", "v1.37.0"), OkeVersions.advertised(false));
    }

    @Test
    void upgradesGoOneMinorAtATime() {
        assertEquals(List.of("v1.35.0", "v1.35.2"), OkeVersions.availableUpgrades("v1.34.10"));
        assertEquals(List.of("v1.35.2", "v1.36.4"), OkeVersions.availableUpgrades("v1.35.0"));
        assertEquals(List.of(), OkeVersions.availableUpgrades("v1.37.0"));
        assertEquals(List.of(), OkeVersions.availableUpgrades("v1.30.1"));

        assertDoesNotThrow(() -> OkeVersions.requireClusterUpgrade("v1.35.2", "v1.36.4", List.of("v1.35.2")));
        assertThrows(OciException.class, () -> OkeVersions.requireClusterUpgrade("v1.34.10", "v1.36.4", List.of()));
        assertThrows(OciException.class, () -> OkeVersions.requireClusterUpgrade("v1.36.4", "v1.35.2", List.of()));
        assertThrows(OciException.class, () -> OkeVersions.requireClusterUpgrade("v1.36.4", "v1.29.1", List.of()));
    }

    @Test
    void newClustersNeedASupportedVersion() {
        assertEquals("v1.36.4", OkeVersions.requireForNewCluster("v1.36.4"));
        OciException missing = assertThrows(OciException.class, () -> OkeVersions.requireForNewCluster(null));
        assertEquals("MissingParameter", missing.getCode());
        OciException unsupported = assertThrows(OciException.class, () -> OkeVersions.requireForNewCluster("v1.30.1"));
        assertEquals("InvalidParameter", unsupported.getCode());
        assertThrows(OciException.class, () -> OkeVersions.requireForNewCluster("latest"));
    }

    @Test
    void nodePoolsStayAtOrBelowTheControlPlaneWithinThreeMinors() {
        assertDoesNotThrow(() -> OkeVersions.requireNodePoolVersion("v1.36.4", "v1.34.10"));
        assertDoesNotThrow(() -> OkeVersions.requireNodePoolVersion("v1.30.1", "v1.30.1"));
        assertThrows(OciException.class, () -> OkeVersions.requireNodePoolVersion("v1.35.2", "v1.36.4"));
        assertThrows(OciException.class, () -> OkeVersions.requireNodePoolVersion("v1.36.4", "v1.30.1"));
        assertThrows(OciException.class, () -> OkeVersions.requireNodePoolVersion("v1.36.4", "v1.36.9"));
    }
}
