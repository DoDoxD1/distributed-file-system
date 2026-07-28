package com.distributedfs.model;

import java.time.Instant;

/**
 * Listing entry for a logical file path and its latest active version.
 */
public record FileListing(
    String logicalPath,
    String latestVersionId,
    long sizeBytes,
    Instant createdAt
) {
}
