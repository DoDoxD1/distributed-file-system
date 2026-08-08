package com.distributedfs.api.dto;

import com.distributedfs.model.ApplicationVersionInfo;

public record VersionResponse(
    String application,
    String version
) {

    public static VersionResponse fromVersion(ApplicationVersionInfo versionInfo) {
        return new VersionResponse(
            versionInfo.application(),
            versionInfo.version()
        );
    }
}
