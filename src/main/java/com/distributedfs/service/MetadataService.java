package com.distributedfs.service;

import com.distributedfs.error.ChunkNotFoundException;
import com.distributedfs.error.LogicalFileNotFoundException;
import com.distributedfs.error.MetadataConflictException;
import com.distributedfs.error.ValidationException;
import com.distributedfs.error.VersionDeletedException;
import com.distributedfs.error.VersionNotFoundException;
import com.distributedfs.model.ChunkRecord;
import com.distributedfs.model.ChunkWrite;
import com.distributedfs.model.FileListing;
import com.distributedfs.model.FileManifest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Strongly consistent in-memory authority for paths, versions, manifests, and chunk references.
 */
public class MetadataService {

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
    private final Map<String, String> pathToFileId = new HashMap<>();
    private final Map<String, List<String>> versionsByFileId = new HashMap<>();
    private final Map<String, FileManifest> manifestsByVersionId = new HashMap<>();
    private final Map<String, ChunkRecord> chunkRecords = new HashMap<>();
    private final Map<IdempotencyKey, String> idempotencyIndex = new HashMap<>();

    /**
     * Looks up previously committed manifest by idempotency key.
     *
     * @param logicalPath file path
     * @param idempotencyKey idempotency key
     * @return manifest when already committed; empty otherwise
     */
    public Optional<FileManifest> findManifestByIdempotency(
        String logicalPath,
        String idempotencyKey
    ) {
        String normalizedPath = requireNonBlank(logicalPath, "logicalPath");
        String normalizedIdempotencyKey = requireNonBlank(idempotencyKey, "idempotencyKey");

        lock.readLock().lock();
        try {
            String versionId = idempotencyIndex.get(
                new IdempotencyKey(normalizedPath, normalizedIdempotencyKey)
            );
            if (versionId == null) {
                return Optional.empty();
            }

            FileManifest manifest = manifestsByVersionId.get(versionId);
            if (manifest == null) {
                return Optional.empty();
            }

            return Optional.of(copyManifest(manifest));
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Commits a new version manifest and atomically publishes it.
     *
     * @param logicalPath path of file
     * @param chunkWrites chunk write acknowledgements
     * @param sizeBytes total file size
     * @param checksum end-to-end file checksum
     * @param idempotencyKey optional idempotency key
     * @return committed manifest
     */
    public FileManifest commitManifest(
        String logicalPath,
        List<ChunkWrite> chunkWrites,
        long sizeBytes,
        String checksum,
        String idempotencyKey
    ) {
        String normalizedPath = requireNonBlank(logicalPath, "logicalPath");
        String normalizedChecksum = requireNonBlank(checksum, "checksum");
        if (chunkWrites == null) {
            throw new ValidationException("chunkWrites must be non-null");
        }
        List<ChunkWrite> normalizedChunkWrites = List.copyOf(chunkWrites);
        if (sizeBytes < 0) {
            throw new ValidationException("sizeBytes must be non-negative, got " + sizeBytes);
        }

        lock.writeLock().lock();
        try {
            String normalizedIdempotency = normalizeNullable(idempotencyKey);
            if (normalizedIdempotency != null) {
                IdempotencyKey indexKey = new IdempotencyKey(normalizedPath, normalizedIdempotency);
                String existingVersionId = idempotencyIndex.get(indexKey);
                if (existingVersionId != null) {
                    FileManifest existingManifest = manifestsByVersionId.get(existingVersionId);
                    if (existingManifest != null) {
                        return copyManifest(existingManifest);
                    }
                }
            }

            String fileId = pathToFileId.computeIfAbsent(normalizedPath, ignored -> newId());
            String versionId = newId();
            Instant createdAt = Instant.now();

            List<String> chunkIds = new ArrayList<>();
            for (ChunkWrite chunkWrite : normalizedChunkWrites) {
                validateChunkWrite(chunkWrite);

                if (chunkWrite.sizeBytes() > 0 && chunkWrite.replicaNodeIds().isEmpty()) {
                    throw new MetadataConflictException(
                        "Chunk " + chunkWrite.chunkId() + " has no durable replicas for commit"
                    );
                }

                chunkIds.add(chunkWrite.chunkId());
                ChunkRecord currentRecord = chunkRecords.get(chunkWrite.chunkId());
                if (currentRecord == null) {
                    ChunkRecord newRecord = new ChunkRecord(
                        chunkWrite.chunkId(),
                        chunkWrite.checksum(),
                        chunkWrite.sizeBytes(),
                        new HashSet<>(chunkWrite.replicaNodeIds()),
                        new HashSet<>(Set.of(versionId)),
                        null
                    );
                    chunkRecords.put(chunkWrite.chunkId(), newRecord);
                    continue;
                }

                if (!Objects.equals(currentRecord.checksum(), chunkWrite.checksum())) {
                    throw new MetadataConflictException(
                        "Checksum mismatch for chunk " + chunkWrite.chunkId() + ": "
                            + currentRecord.checksum() + " != " + chunkWrite.checksum()
                    );
                }
                if (currentRecord.sizeBytes() != chunkWrite.sizeBytes()) {
                    throw new MetadataConflictException(
                        "Size mismatch for chunk " + chunkWrite.chunkId() + ": "
                            + currentRecord.sizeBytes() + " != " + chunkWrite.sizeBytes()
                    );
                }

                Set<String> replicaNodeIds = new HashSet<>(currentRecord.replicaNodeIds());
                replicaNodeIds.addAll(chunkWrite.replicaNodeIds());

                Set<String> referencedVersionIds = new HashSet<>(
                    currentRecord.referencedVersionIds()
                );
                referencedVersionIds.add(versionId);

                ChunkRecord updatedRecord = new ChunkRecord(
                    currentRecord.chunkId(),
                    currentRecord.checksum(),
                    currentRecord.sizeBytes(),
                    replicaNodeIds,
                    referencedVersionIds,
                    null
                );
                chunkRecords.put(currentRecord.chunkId(), updatedRecord);
            }

            FileManifest manifest = new FileManifest(
                fileId,
                normalizedPath,
                versionId,
                List.copyOf(chunkIds),
                sizeBytes,
                normalizedChecksum,
                createdAt,
                normalizedIdempotency,
                null
            );
            manifestsByVersionId.put(versionId, manifest);
            versionsByFileId.computeIfAbsent(fileId, ignored -> new ArrayList<>()).add(versionId);

            if (normalizedIdempotency != null) {
                idempotencyIndex.put(
                    new IdempotencyKey(normalizedPath, normalizedIdempotency),
                    versionId
                );
            }

            return copyManifest(manifest);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Returns one file manifest by logical path and optional version.
     *
     * @param logicalPath file path
     * @param versionId explicit version ID or null for latest active
     * @param includeDeleted whether deleted versions are permitted in lookup
     * @return requested manifest
     */
    public FileManifest getManifest(String logicalPath, String versionId, boolean includeDeleted) {
        String normalizedPath = requireNonBlank(logicalPath, "logicalPath");

        lock.readLock().lock();
        try {
            String fileId = pathToFileId.get(normalizedPath);
            if (fileId == null) {
                throw new LogicalFileNotFoundException(
                    "Unknown logical path: " + normalizedPath
                );
            }

            String normalizedVersionId = normalizeNullable(versionId);
            if (normalizedVersionId != null) {
                FileManifest manifest = manifestsByVersionId.get(normalizedVersionId);
                if (manifest == null || !Objects.equals(manifest.logicalPath(), normalizedPath)) {
                    throw new VersionNotFoundException(
                        "Version " + normalizedVersionId + " does not exist for " + normalizedPath
                    );
                }
                if (manifest.deletedAt() != null && !includeDeleted) {
                    throw new VersionDeletedException(
                        "Version " + normalizedVersionId + " for " + normalizedPath + " is deleted"
                    );
                }
                return copyManifest(manifest);
            }

            FileManifest activeManifest = latestActiveManifestUnlocked(fileId);
            if (activeManifest != null) {
                return copyManifest(activeManifest);
            }

            if (includeDeleted) {
                List<String> versionIds = versionsByFileId.getOrDefault(fileId, List.of());
                if (!versionIds.isEmpty()) {
                    FileManifest latestManifest = manifestsByVersionId.get(
                        versionIds.get(versionIds.size() - 1)
                    );
                    return copyManifest(latestManifest);
                }
            }

            throw new LogicalFileNotFoundException(
                "No active versions found for logical path: " + normalizedPath
            );
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Lists all versions for one logical path in creation order.
     *
     * @param logicalPath file path
     * @return versions in creation order
     */
    public List<FileManifest> listVersions(String logicalPath) {
        String normalizedPath = requireNonBlank(logicalPath, "logicalPath");

        lock.readLock().lock();
        try {
            String fileId = pathToFileId.get(normalizedPath);
            if (fileId == null) {
                return List.of();
            }

            List<String> versionIds = versionsByFileId.getOrDefault(fileId, List.of());
            List<FileManifest> result = new ArrayList<>();
            for (String versionId : versionIds) {
                result.add(copyManifest(manifestsByVersionId.get(versionId)));
            }
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Lists logical files with their latest active version metadata.
     *
     * @param prefix path prefix filter
     * @return sorted listing entries
     */
    public List<FileListing> listFiles(String prefix) {
        String normalizedPrefix = prefix == null ? "" : prefix.strip();

        lock.readLock().lock();
        try {
            List<String> sortedPaths = pathToFileId.keySet().stream().sorted().toList();
            List<FileListing> listings = new ArrayList<>();

            for (String logicalPath : sortedPaths) {
                if (!normalizedPrefix.isEmpty() && !logicalPath.startsWith(normalizedPrefix)) {
                    continue;
                }

                String fileId = pathToFileId.get(logicalPath);
                FileManifest manifest = latestActiveManifestUnlocked(fileId);
                if (manifest == null) {
                    continue;
                }

                listings.add(new FileListing(
                    logicalPath,
                    manifest.versionId(),
                    manifest.sizeBytes(),
                    manifest.createdAt()
                ));
            }
            listings.sort(Comparator.comparing(FileListing::logicalPath));
            return listings;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Marks a version as deleted and releases chunk references.
     *
     * @param logicalPath file path
     * @param versionId target version or null for latest active
     * @return tombstoned manifest
     */
    public FileManifest markDeleted(String logicalPath, String versionId) {
        String normalizedPath = requireNonBlank(logicalPath, "logicalPath");

        lock.writeLock().lock();
        try {
            String fileId = pathToFileId.get(normalizedPath);
            if (fileId == null) {
                throw new LogicalFileNotFoundException("Unknown logical path: " + normalizedPath);
            }

            FileManifest targetManifest;
            String normalizedVersionId = normalizeNullable(versionId);
            if (normalizedVersionId != null) {
                FileManifest manifest = manifestsByVersionId.get(normalizedVersionId);
                if (manifest == null || !Objects.equals(manifest.logicalPath(), normalizedPath)) {
                    throw new VersionNotFoundException(
                        "Version " + normalizedVersionId + " does not exist for " + normalizedPath
                    );
                }
                targetManifest = manifest;
            } else {
                targetManifest = latestActiveManifestUnlocked(fileId);
                if (targetManifest == null) {
                    throw new LogicalFileNotFoundException(
                        "No active version exists to delete for " + normalizedPath
                    );
                }
            }

            if (targetManifest.deletedAt() != null) {
                return copyManifest(targetManifest);
            }

            Instant deletedAt = Instant.now();
            FileManifest updatedManifest = new FileManifest(
                targetManifest.fileId(),
                targetManifest.logicalPath(),
                targetManifest.versionId(),
                List.copyOf(targetManifest.chunkIds()),
                targetManifest.sizeBytes(),
                targetManifest.checksum(),
                targetManifest.createdAt(),
                targetManifest.idempotencyKey(),
                deletedAt
            );
            manifestsByVersionId.put(updatedManifest.versionId(), updatedManifest);

            for (String chunkId : targetManifest.chunkIds()) {
                ChunkRecord chunkRecord = chunkRecords.get(chunkId);
                if (chunkRecord == null) {
                    continue;
                }

                Set<String> referencedVersionIds = new HashSet<>(
                    chunkRecord.referencedVersionIds()
                );
                referencedVersionIds.remove(updatedManifest.versionId());

                Instant lastUnreferencedAt = chunkRecord.lastUnreferencedAt();
                if (referencedVersionIds.isEmpty()) {
                    lastUnreferencedAt = deletedAt;
                }

                ChunkRecord updatedRecord = new ChunkRecord(
                    chunkRecord.chunkId(),
                    chunkRecord.checksum(),
                    chunkRecord.sizeBytes(),
                    new HashSet<>(chunkRecord.replicaNodeIds()),
                    referencedVersionIds,
                    lastUnreferencedAt
                );
                chunkRecords.put(chunkRecord.chunkId(), updatedRecord);
            }

            return copyManifest(updatedManifest);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Reads chunk metadata by chunk ID.
     *
     * @param chunkId chunk identifier
     * @return chunk metadata
     */
    public ChunkRecord getChunkRecord(String chunkId) {
        String normalizedChunkId = requireNonBlank(chunkId, "chunkId");

        lock.readLock().lock();
        try {
            ChunkRecord chunkRecord = chunkRecords.get(normalizedChunkId);
            if (chunkRecord == null) {
                throw new ChunkNotFoundException("Chunk not found: " + normalizedChunkId);
            }
            return copyChunkRecord(chunkRecord);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Optionally reads chunk metadata by chunk ID.
     *
     * @param chunkId chunk identifier
     * @return optional chunk metadata
     */
    public Optional<ChunkRecord> getChunkRecordOrEmpty(String chunkId) {
        String normalizedChunkId = requireNonBlank(chunkId, "chunkId");

        lock.readLock().lock();
        try {
            ChunkRecord chunkRecord = chunkRecords.get(normalizedChunkId);
            if (chunkRecord == null) {
                return Optional.empty();
            }
            return Optional.of(copyChunkRecord(chunkRecord));
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Returns a snapshot list of chunk metadata records.
     *
     * @return chunk record snapshot
     */
    public List<ChunkRecord> listChunkRecords() {
        lock.readLock().lock();
        try {
            List<ChunkRecord> snapshot = new ArrayList<>();
            for (ChunkRecord chunkRecord : chunkRecords.values()) {
                snapshot.add(copyChunkRecord(chunkRecord));
            }
            return snapshot;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Removes one node replica reference from chunk metadata.
     *
     * @param chunkId chunk identifier
     * @param nodeId node identifier
     */
    public void removeReplica(String chunkId, String nodeId) {
        String normalizedChunkId = requireNonBlank(chunkId, "chunkId");
        String normalizedNodeId = requireNonBlank(nodeId, "nodeId");

        lock.writeLock().lock();
        try {
            ChunkRecord chunkRecord = chunkRecords.get(normalizedChunkId);
            if (chunkRecord == null) {
                throw new ChunkNotFoundException("Chunk not found: " + normalizedChunkId);
            }

            Set<String> replicaNodeIds = new HashSet<>(chunkRecord.replicaNodeIds());
            replicaNodeIds.remove(normalizedNodeId);

            ChunkRecord updatedRecord = new ChunkRecord(
                chunkRecord.chunkId(),
                chunkRecord.checksum(),
                chunkRecord.sizeBytes(),
                replicaNodeIds,
                new HashSet<>(chunkRecord.referencedVersionIds()),
                chunkRecord.lastUnreferencedAt()
            );
            chunkRecords.put(normalizedChunkId, updatedRecord);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Adds one node replica reference into chunk metadata.
     *
     * @param chunkId chunk identifier
     * @param nodeId node identifier
     */
    public void addReplica(String chunkId, String nodeId) {
        String normalizedChunkId = requireNonBlank(chunkId, "chunkId");
        String normalizedNodeId = requireNonBlank(nodeId, "nodeId");

        lock.writeLock().lock();
        try {
            ChunkRecord chunkRecord = chunkRecords.get(normalizedChunkId);
            if (chunkRecord == null) {
                throw new ChunkNotFoundException("Chunk not found: " + normalizedChunkId);
            }

            Set<String> replicaNodeIds = new HashSet<>(chunkRecord.replicaNodeIds());
            replicaNodeIds.add(normalizedNodeId);

            ChunkRecord updatedRecord = new ChunkRecord(
                chunkRecord.chunkId(),
                chunkRecord.checksum(),
                chunkRecord.sizeBytes(),
                replicaNodeIds,
                new HashSet<>(chunkRecord.referencedVersionIds()),
                chunkRecord.lastUnreferencedAt()
            );
            chunkRecords.put(normalizedChunkId, updatedRecord);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Returns unreferenced chunks eligible for garbage collection.
     *
     * @param retentionSeconds retention window in seconds
     * @param referenceTime optional reference time, or null for current time
     * @return chunk records older than retention threshold
     */
    public List<ChunkRecord> getGarbageCollectionCandidates(
        int retentionSeconds,
        Instant referenceTime
    ) {
        if (retentionSeconds < 0) {
            throw new ValidationException(
                "retentionSeconds must be non-negative, got " + retentionSeconds
            );
        }

        Instant nowValue = referenceTime == null ? Instant.now() : referenceTime;
        Instant cutoff = nowValue.minusSeconds(retentionSeconds);

        lock.readLock().lock();
        try {
            List<ChunkRecord> candidates = new ArrayList<>();
            for (ChunkRecord chunkRecord : chunkRecords.values()) {
                if (!chunkRecord.referencedVersionIds().isEmpty()) {
                    continue;
                }
                Instant lastUnreferencedAt = chunkRecord.lastUnreferencedAt();
                if (lastUnreferencedAt == null) {
                    continue;
                }
                if (!lastUnreferencedAt.isAfter(cutoff)) {
                    candidates.add(copyChunkRecord(chunkRecord));
                }
            }
            return candidates;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Removes one chunk metadata record after physical replicas are cleaned.
     *
     * @param chunkId chunk identifier
     */
    public void purgeChunkRecord(String chunkId) {
        String normalizedChunkId = requireNonBlank(chunkId, "chunkId");

        lock.writeLock().lock();
        try {
            ChunkRecord chunkRecord = chunkRecords.get(normalizedChunkId);
            if (chunkRecord == null) {
                return;
            }
            if (!chunkRecord.referencedVersionIds().isEmpty()) {
                throw new MetadataConflictException(
                    "Cannot purge referenced chunk " + normalizedChunkId
                        + "; references still exist"
                );
            }
            chunkRecords.remove(normalizedChunkId);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private FileManifest latestActiveManifestUnlocked(String fileId) {
        List<String> versionIds = versionsByFileId.getOrDefault(fileId, List.of());
        for (int index = versionIds.size() - 1; index >= 0; index--) {
            FileManifest candidate = manifestsByVersionId.get(versionIds.get(index));
            if (candidate != null && candidate.deletedAt() == null) {
                return candidate;
            }
        }
        return null;
    }

    private static void validateChunkWrite(ChunkWrite chunkWrite) {
        if (chunkWrite == null) {
            throw new ValidationException("chunkWrite must be non-null");
        }
        requireNonBlank(chunkWrite.chunkId(), "chunkWrite.chunkId");
        requireNonBlank(chunkWrite.checksum(), "chunkWrite.checksum");
        if (chunkWrite.sizeBytes() < 0) {
            throw new ValidationException(
                "chunkWrite.sizeBytes must be non-negative, got " + chunkWrite.sizeBytes()
            );
        }
        if (chunkWrite.replicaNodeIds() == null) {
            throw new ValidationException("chunkWrite.replicaNodeIds must be non-null");
        }
    }

    private static FileManifest copyManifest(FileManifest manifest) {
        return new FileManifest(
            manifest.fileId(),
            manifest.logicalPath(),
            manifest.versionId(),
            List.copyOf(manifest.chunkIds()),
            manifest.sizeBytes(),
            manifest.checksum(),
            manifest.createdAt(),
            manifest.idempotencyKey(),
            manifest.deletedAt()
        );
    }

    private static ChunkRecord copyChunkRecord(ChunkRecord chunkRecord) {
        return new ChunkRecord(
            chunkRecord.chunkId(),
            chunkRecord.checksum(),
            chunkRecord.sizeBytes(),
            new HashSet<>(chunkRecord.replicaNodeIds()),
            new HashSet<>(chunkRecord.referencedVersionIds()),
            chunkRecord.lastUnreferencedAt()
        );
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String normalizeNullable(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        return normalized.isEmpty() ? null : normalized;
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new ValidationException(fieldName + " must be non-empty");
        }
        return value.strip();
    }

    private record IdempotencyKey(String logicalPath, String idempotencyKey) {
    }
}
