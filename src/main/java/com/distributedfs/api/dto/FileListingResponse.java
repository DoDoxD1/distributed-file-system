package com.distributedfs.api.dto;

import com.distributedfs.model.FileListing;
import java.time.Instant;

/**
 * API response shape for file listing entries.
 */
public record FileListingResponse(
    String logicalPath,
    String latestVersionId,
    long sizeBytes,
    Instant createdAt
) {

    public static FileListingResponse fromListing(FileListing listing) {
        return new FileListingResponse(
            listing.logicalPath(),
            listing.latestVersionId(),
            listing.sizeBytes(),
            listing.createdAt()
        );
    }
}
