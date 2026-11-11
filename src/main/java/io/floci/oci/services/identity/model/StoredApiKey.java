package io.floci.oci.services.identity.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** An API signing key of a user: oci-go-sdk {@code identity.ApiKey}. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StoredApiKey {

    private String keyId;
    private String keyValue;
    private String fingerprint;
    private String userId;
    private String timeCreated;
    private String lifecycleState;
    private Long inactiveStatus;
    @JsonIgnore
    private String etag;

    public StoredApiKey() {
    }

    public String getKeyId() {
        return keyId;
    }

    public void setKeyId(String keyId) {
        this.keyId = keyId;
    }

    public String getKeyValue() {
        return keyValue;
    }

    public void setKeyValue(String keyValue) {
        this.keyValue = keyValue;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public void setFingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getTimeCreated() {
        return timeCreated;
    }

    public void setTimeCreated(String timeCreated) {
        this.timeCreated = timeCreated;
    }

    public String getLifecycleState() {
        return lifecycleState;
    }

    public void setLifecycleState(String lifecycleState) {
        this.lifecycleState = lifecycleState;
    }

    public Long getInactiveStatus() {
        return inactiveStatus;
    }

    public void setInactiveStatus(Long inactiveStatus) {
        this.inactiveStatus = inactiveStatus;
    }

    public String getEtag() {
        return etag;
    }

    public void setEtag(String etag) {
        this.etag = etag;
    }
}
