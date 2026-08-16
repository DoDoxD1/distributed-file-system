package com.distributedfs.model;

import java.util.Map;

public record ObjectStorageObjectInfo(
    long sizeBytes,
    String contentType,
    String contentSha256,
    Map<String, String> metadata
) {
    public ObjectStorageObjectInfo {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
