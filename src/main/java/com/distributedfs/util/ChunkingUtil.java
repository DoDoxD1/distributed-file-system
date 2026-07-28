package com.distributedfs.util;

import com.distributedfs.error.ValidationException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Fixed-size chunk partitioning utilities.
 */
public final class ChunkingUtil {

    private ChunkingUtil() {
    }

    public static List<byte[]> splitIntoChunks(byte[] payload, int chunkSizeBytes) {
        if (payload == null) {
            throw new ValidationException("payload must be non-null");
        }
        if (chunkSizeBytes <= 0) {
            throw new ValidationException(
                "chunkSizeBytes must be positive, got " + chunkSizeBytes
            );
        }

        if (payload.length == 0) {
            return List.of(new byte[0]);
        }

        List<byte[]> chunks = new ArrayList<>();
        int offset = 0;
        while (offset < payload.length) {
            int nextOffset = Math.min(offset + chunkSizeBytes, payload.length);
            chunks.add(Arrays.copyOfRange(payload, offset, nextOffset));
            offset = nextOffset;
        }
        return chunks;
    }
}
