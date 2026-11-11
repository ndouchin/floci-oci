package io.floci.oci.services.oke.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A cluster add-on installed through InstallAddon, kept on its cluster. */
@RegisterForReflection
public class StoredAddon {

    private String name;
    private String version;
    private String lifecycleState;
    private Instant timeCreated;
    private List<Map<String, Object>> configurations;

    /** The oci-go-sdk {@code containerengine.Addon} wire shape. */
    public Map<String, Object> toWire() {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("name", name);
        wire.put("lifecycleState", lifecycleState);
        wire.put("version", version);
        wire.put("currentInstalledVersion", version);
        wire.put("timeCreated", timeCreated);
        wire.put("configurations", configurations != null ? configurations : List.of());
        return wire;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getLifecycleState() {
        return lifecycleState;
    }

    public void setLifecycleState(String lifecycleState) {
        this.lifecycleState = lifecycleState;
    }

    public Instant getTimeCreated() {
        return timeCreated;
    }

    public void setTimeCreated(Instant timeCreated) {
        this.timeCreated = timeCreated;
    }

    public List<Map<String, Object>> getConfigurations() {
        return configurations;
    }

    public void setConfigurations(List<Map<String, Object>> configurations) {
        this.configurations = configurations;
    }
}
