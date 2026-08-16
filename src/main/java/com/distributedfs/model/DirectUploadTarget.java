package com.distributedfs.model;

import java.util.Map;

public record DirectUploadTarget(
    String url,
    String method,
    Map<String, String> headers
) {
    public DirectUploadTarget {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }
}
