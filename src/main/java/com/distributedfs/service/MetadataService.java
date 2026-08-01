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
import com.distributedfs.util.TimeProvider;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Strongly consistent in-memory authority for paths, versions, manifests, and chunk references.
 */
public class MetadataService {

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final TimeProvider timeProvider;

    public MetadataService(
        JdbcTemplate jdbcTemplate,
        PlatformTransactionManager transactionManager,
        TimeProvider timeProvider
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedParameterJdbcTemplate = new NamedParameterJdbcTemplate(jdbcTemplate);
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.timeProvider = timeProvider;
    }

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

        String versionId = querySingleValue(
            """
            select version_id
            from dfs_idempotency_keys
            where logical_path = ? and idempotency_key = ?
            """,
            String.class,
            normalizedPath,
            normalizedIdempotencyKey
        );
        if (versionId == null) {
            return Optional.empty();
        }

        FileManifest manifest = loadManifestByVersionId(versionId);
        if (manifest == null) {
            return Optional.empty();
        }
        return Optional.of(copyManifest(manifest));
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

        String normalizedIdempotency = normalizeNullable(idempotencyKey);
        try {
            FileManifest manifest = transactionTemplate.execute(status -> {
                if (normalizedIdempotency != null) {
                    Optional<FileManifest> existingManifest = findManifestByIdempotency(
                        normalizedPath,
                        normalizedIdempotency
                    );
                    if (existingManifest.isPresent()) {
                        return existingManifest.get();
                    }
                }

                String fileId = ensureFileId(normalizedPath);
                lockFileRow(fileId);

                String versionId = newId();
                Instant createdAt = timeProvider.now();
                long versionNumber = nextVersionNumber(fileId);

                jdbcTemplate.update(
                    """
                    insert into dfs_file_versions(
                        version_id,
                        file_id,
                        version_number,
                        size_bytes,
                        checksum,
                        created_at,
                        idempotency_key,
                        deleted_at
                    ) values (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    versionId,
                    fileId,
                    versionNumber,
                    sizeBytes,
                    normalizedChecksum,
                    toTimestamp(createdAt),
                    normalizedIdempotency,
                    null
                );

                List<String> chunkIds = new ArrayList<>();
                for (int index = 0; index < normalizedChunkWrites.size(); index++) {
                    ChunkWrite chunkWrite = normalizedChunkWrites.get(index);
                    persistChunkWrite(versionId, index, chunkWrite);
                    chunkIds.add(chunkWrite.chunkId());
                }

                if (normalizedIdempotency != null) {
                    jdbcTemplate.update(
                        """
                        insert into dfs_idempotency_keys(
                            logical_path,
                            idempotency_key,
                            version_id
                        ) values (?, ?, ?)
                        """,
                        normalizedPath,
                        normalizedIdempotency,
                        versionId
                    );
                }

                return new FileManifest(
                    fileId,
                    null,
                    normalizedPath,
                    versionId,
                    List.copyOf(chunkIds),
                    sizeBytes,
                    normalizedChecksum,
                    createdAt,
                    normalizedIdempotency,
                    null
                );
            });
            return copyManifest(Objects.requireNonNull(manifest));
        } catch (DuplicateKeyException error) {
            if (normalizedIdempotency != null) {
                Optional<FileManifest> existingManifest = findManifestByIdempotency(
                    normalizedPath,
                    normalizedIdempotency
                );
                if (existingManifest.isPresent()) {
                    return existingManifest.get();
                }
            }
            throw new MetadataConflictException(
                "Metadata commit conflicted for logical path: " + normalizedPath
            );
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

        String fileId = findFileIdByPath(normalizedPath);
        if (fileId == null) {
            throw new LogicalFileNotFoundException("Unknown logical path: " + normalizedPath);
        }

        String normalizedVersionId = normalizeNullable(versionId);
        if (normalizedVersionId != null) {
            FileManifest manifest = loadManifestByVersionId(normalizedVersionId);
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

        String latestActiveVersionId = findLatestVersionId(fileId, false);
        if (latestActiveVersionId != null) {
            return copyManifest(
                Objects.requireNonNull(loadManifestByVersionId(latestActiveVersionId))
            );
        }

        if (includeDeleted) {
            String latestVersionId = findLatestVersionId(fileId, true);
            if (latestVersionId != null) {
                return copyManifest(
                    Objects.requireNonNull(loadManifestByVersionId(latestVersionId))
                );
            }
        }

        throw new LogicalFileNotFoundException(
            "No active versions found for logical path: " + normalizedPath
        );
    }

    /**
     * Lists active versions for one logical path in creation order.
     *
     * @param logicalPath file path
     * @return active versions in creation order
     */
    public List<FileManifest> listVersions(String logicalPath) {
        String normalizedPath = requireNonBlank(logicalPath, "logicalPath");

        String fileId = findFileIdByPath(normalizedPath);
        if (fileId == null) {
            return List.of();
        }

        List<String> versionIds = jdbcTemplate.query(
            """
            select version_id
            from dfs_file_versions
            where file_id = ? and deleted_at is null
            order by version_number
            """,
            (resultSet, rowNum) -> resultSet.getString("version_id"),
            fileId
        );
        return loadManifestsByVersionIds(versionIds);
    }

    /**
     * Lists logical files with their latest active version metadata.
     *
     * @param prefix path prefix filter
     * @return sorted listing entries
     */
    public List<FileListing> listFiles(String prefix) {
        String normalizedPrefix = prefix == null ? "" : prefix.strip();

        List<FileListing> listings = jdbcTemplate.query(
            """
            select f.logical_path, v.version_id, v.size_bytes, v.created_at
            from dfs_files f
            join dfs_file_versions v on v.file_id = f.file_id
            where v.deleted_at is null
                and v.version_number = (
                    select max(v2.version_number)
                    from dfs_file_versions v2
                    where v2.file_id = f.file_id and v2.deleted_at is null
                )
            order by f.logical_path
            """,
            (resultSet, rowNum) -> new FileListing(
                resultSet.getString("logical_path"),
                resultSet.getString("version_id"),
                resultSet.getLong("size_bytes"),
                getInstant(resultSet, "created_at")
            )
        );
        if (normalizedPrefix.isEmpty()) {
            return listings;
        }

        List<FileListing> filteredListings = new ArrayList<>();
        for (FileListing listing : listings) {
            if (listing.logicalPath().startsWith(normalizedPrefix)) {
                filteredListings.add(listing);
            }
        }
        filteredListings.sort(Comparator.comparing(FileListing::logicalPath));
        return filteredListings;
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

        FileManifest deletedManifest = transactionTemplate.execute(status -> {
            String fileId = findFileIdByPath(normalizedPath);
            if (fileId == null) {
                throw new LogicalFileNotFoundException("Unknown logical path: " + normalizedPath);
            }
            lockFileRow(fileId);

            FileManifest targetManifest;
            String normalizedVersionId = normalizeNullable(versionId);
            if (normalizedVersionId != null) {
                FileManifest manifest = loadManifestByVersionId(normalizedVersionId);
                if (manifest == null || !Objects.equals(manifest.logicalPath(), normalizedPath)) {
                    throw new VersionNotFoundException(
                        "Version " + normalizedVersionId + " does not exist for " + normalizedPath
                    );
                }
                targetManifest = manifest;
            } else {
                String latestActiveVersionId = findLatestVersionId(fileId, false);
                if (latestActiveVersionId == null) {
                    throw new LogicalFileNotFoundException(
                        "No active version exists to delete for " + normalizedPath
                    );
                }
                targetManifest = Objects.requireNonNull(
                    loadManifestByVersionId(latestActiveVersionId)
                );
            }

            if (targetManifest.deletedAt() != null) {
                return targetManifest;
            }

            Instant deletedAt = timeProvider.now();
            jdbcTemplate.update(
                "update dfs_file_versions set deleted_at = ? where version_id = ?",
                toTimestamp(deletedAt),
                targetManifest.versionId()
            );

            for (String chunkId : targetManifest.chunkIds()) {
                if (countActiveReferences(chunkId) == 0) {
                    jdbcTemplate.update(
                        "update dfs_chunks set last_unreferenced_at = ? where chunk_id = ?",
                        toTimestamp(deletedAt),
                        chunkId
                    );
                }
            }

            return Objects.requireNonNull(loadManifestByVersionId(targetManifest.versionId()));
        });
        return copyManifest(Objects.requireNonNull(deletedManifest));
    }

    /**
     * Reads chunk metadata by chunk ID.
     *
     * @param chunkId chunk identifier
     * @return chunk metadata
     */
    public ChunkRecord getChunkRecord(String chunkId) {
        String normalizedChunkId = requireNonBlank(chunkId, "chunkId");

        Optional<ChunkRecord> chunkRecord = getChunkRecordOrEmpty(normalizedChunkId);
        if (chunkRecord.isEmpty()) {
            throw new ChunkNotFoundException("Chunk not found: " + normalizedChunkId);
        }
        return copyChunkRecord(chunkRecord.get());
    }

    /**
     * Optionally reads chunk metadata by chunk ID.
     *
     * @param chunkId chunk identifier
     * @return optional chunk metadata
     */
    public Optional<ChunkRecord> getChunkRecordOrEmpty(String chunkId) {
        String normalizedChunkId = requireNonBlank(chunkId, "chunkId");

        List<ChunkRecord> chunkRecords = loadChunkRecordsByIds(List.of(normalizedChunkId));
        if (chunkRecords.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(copyChunkRecord(chunkRecords.getFirst()));
    }

    /**
     * Returns a snapshot list of chunk metadata records.
     *
     * @return chunk record snapshot
     */
    public List<ChunkRecord> listChunkRecords() {
        return loadChunkRecordsByQuery(
            """
            select chunk_id, checksum, size_bytes, last_unreferenced_at
            from dfs_chunks
            order by chunk_id
            """
        );
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

        transactionTemplate.executeWithoutResult(status -> {
            if (findChunkRow(normalizedChunkId) == null) {
                throw new ChunkNotFoundException("Chunk not found: " + normalizedChunkId);
            }
            jdbcTemplate.update(
                "delete from dfs_chunk_replicas where chunk_id = ? and node_id = ?",
                normalizedChunkId,
                normalizedNodeId
            );
        });
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

        transactionTemplate.executeWithoutResult(status -> {
            if (findChunkRow(normalizedChunkId) == null) {
                throw new ChunkNotFoundException("Chunk not found: " + normalizedChunkId);
            }
            insertReplica(normalizedChunkId, normalizedNodeId);
        });
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

        Instant nowValue = referenceTime == null ? timeProvider.now() : referenceTime;
        Instant cutoff = nowValue.minusSeconds(retentionSeconds);

        return loadChunkRecordsByQuery(
            """
            select chunk_id, checksum, size_bytes, last_unreferenced_at
            from dfs_chunks c
            where c.last_unreferenced_at is not null
                and c.last_unreferenced_at <= ?
                and not exists (
                    select 1
                    from dfs_version_chunks vc
                    join dfs_file_versions fv on fv.version_id = vc.version_id
                    where vc.chunk_id = c.chunk_id and fv.deleted_at is null
                )
            order by c.chunk_id
            """,
            toTimestamp(cutoff)
        );
    }

    /**
     * Removes one chunk metadata record after physical replicas are cleaned.
     *
     * @param chunkId chunk identifier
     */
    public void purgeChunkRecord(String chunkId) {
        String normalizedChunkId = requireNonBlank(chunkId, "chunkId");

        transactionTemplate.executeWithoutResult(status -> {
            if (findChunkRow(normalizedChunkId) == null) {
                return;
            }
            if (countActiveReferences(normalizedChunkId) > 0) {
                throw new MetadataConflictException(
                    "Cannot purge referenced chunk " + normalizedChunkId
                        + "; references still exist"
                );
            }
            jdbcTemplate.update(
                "delete from dfs_chunk_replicas where chunk_id = ?",
                normalizedChunkId
            );
            jdbcTemplate.update(
                "delete from dfs_chunks where chunk_id = ?",
                normalizedChunkId
            );
        });
    }

    private void persistChunkWrite(String versionId, int chunkOrder, ChunkWrite chunkWrite) {
        validateChunkWrite(chunkWrite);
        if (chunkWrite.sizeBytes() > 0 && chunkWrite.replicaNodeIds().isEmpty()) {
            throw new MetadataConflictException(
                "Chunk " + chunkWrite.chunkId() + " has no durable replicas for commit"
            );
        }

        ensureChunkRow(chunkWrite);
        jdbcTemplate.update(
            "update dfs_chunks set last_unreferenced_at = ? where chunk_id = ?",
            null,
            chunkWrite.chunkId()
        );
        for (String nodeId : chunkWrite.replicaNodeIds().stream().sorted().toList()) {
            insertReplica(chunkWrite.chunkId(), nodeId);
        }
        jdbcTemplate.update(
            "insert into dfs_version_chunks(version_id, chunk_order, chunk_id) values (?, ?, ?)",
            versionId,
            chunkOrder,
            chunkWrite.chunkId()
        );
    }

    private void ensureChunkRow(ChunkWrite chunkWrite) {
        ChunkRow currentChunk = findChunkRow(chunkWrite.chunkId());
        if (currentChunk == null) {
            insertIgnoringDuplicate(
                """
                insert into dfs_chunks(chunk_id, checksum, size_bytes, last_unreferenced_at)
                values (?, ?, ?, ?)
                """,
                chunkWrite.chunkId(),
                chunkWrite.checksum(),
                chunkWrite.sizeBytes(),
                null
            );
            currentChunk = findChunkRow(chunkWrite.chunkId());
            if (currentChunk != null) {
                validateExistingChunk(currentChunk, chunkWrite);
                return;
            }
        }
        validateExistingChunk(currentChunk, chunkWrite);
    }

    private void validateExistingChunk(ChunkRow currentChunk, ChunkWrite chunkWrite) {
        if (currentChunk == null) {
            throw new MetadataConflictException(
                "Chunk state disappeared during commit for chunk " + chunkWrite.chunkId()
            );
        }
        if (!Objects.equals(currentChunk.checksum(), chunkWrite.checksum())) {
            throw new MetadataConflictException(
                "Checksum mismatch for chunk " + chunkWrite.chunkId() + ": "
                    + currentChunk.checksum() + " != " + chunkWrite.checksum()
            );
        }
        if (currentChunk.sizeBytes() != chunkWrite.sizeBytes()) {
            throw new MetadataConflictException(
                "Size mismatch for chunk " + chunkWrite.chunkId() + ": "
                    + currentChunk.sizeBytes() + " != " + chunkWrite.sizeBytes()
            );
        }
    }

    private void insertReplica(String chunkId, String nodeId) {
        insertIgnoringDuplicate(
            "insert into dfs_chunk_replicas(chunk_id, node_id) values (?, ?)",
            chunkId,
            nodeId
        );
    }

    private String ensureFileId(String logicalPath) {
        String fileId = findFileIdByPath(logicalPath);
        if (fileId != null) {
            return fileId;
        }

        String createdFileId = newId();
        insertIgnoringDuplicate(
            "insert into dfs_files(file_id, logical_path) values (?, ?)",
            createdFileId,
            logicalPath
        );
        String existingFileId = findFileIdByPath(logicalPath);
        if (existingFileId != null) {
            return existingFileId;
        }
        throw new MetadataConflictException(
            "File metadata was not created for logical path: " + logicalPath
        );
    }

    private boolean insertIgnoringDuplicate(String sql, Object... arguments) {
        var dataSource = Objects.requireNonNull(jdbcTemplate.getDataSource());
        Connection connection = DataSourceUtils.getConnection(
            dataSource
        );
        Savepoint savepoint = createSavepoint(connection, sql);
        try {
            jdbcTemplate.update(sql, arguments);
            releaseSavepoint(connection, savepoint, sql);
            return true;
        } catch (DuplicateKeyException error) {
            rollbackToSavepoint(connection, savepoint, sql, error);
            return false;
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    private Savepoint createSavepoint(Connection connection, String sql) {
        try {
            return connection.setSavepoint();
        } catch (SQLException error) {
            throw new IllegalStateException(
                "Failed to create savepoint for SQL statement: " + sql,
                error
            );
        }
    }

    private void rollbackToSavepoint(
        Connection connection,
        Savepoint savepoint,
        String sql,
        DuplicateKeyException error
    ) {
        try {
            connection.rollback(savepoint);
            releaseSavepoint(connection, savepoint, sql);
        } catch (SQLException rollbackError) {
            throw new IllegalStateException(
                "Failed to roll back duplicate insert for SQL statement: " + sql,
                rollbackError
            );
        }
    }

    private void releaseSavepoint(Connection connection, Savepoint savepoint, String sql) {
        try {
            connection.releaseSavepoint(savepoint);
        } catch (SQLException error) {
            throw new IllegalStateException(
                "Failed to release savepoint for SQL statement: " + sql,
                error
            );
        }
    }

    private String findFileIdByPath(String logicalPath) {
        return querySingleValue(
            "select file_id from dfs_files where logical_path = ?",
            String.class,
            logicalPath
        );
    }

    private void lockFileRow(String fileId) {
        querySingleValue(
            "select file_id from dfs_files where file_id = ? for update",
            String.class,
            fileId
        );
    }

    private long nextVersionNumber(String fileId) {
        Long nextVersionNumber = querySingleValue(
            "select coalesce(max(version_number), 0) + 1 from dfs_file_versions where file_id = ?",
            Long.class,
            fileId
        );
        return nextVersionNumber == null ? 1L : nextVersionNumber;
    }

    private String findLatestVersionId(String fileId, boolean includeDeleted) {
        String sql = includeDeleted
            ? """
                select version_id
                from dfs_file_versions
                where file_id = ?
                order by version_number desc
                limit 1
                """
            : """
                select version_id
                from dfs_file_versions
                where file_id = ? and deleted_at is null
                order by version_number desc
                limit 1
                """;
        return querySingleValue(sql, String.class, fileId);
    }

    private FileManifest loadManifestByVersionId(String versionId) {
        ManifestRow manifestRow = querySingle(
            """
            select v.version_id, v.file_id, f.logical_path, v.size_bytes, v.checksum,
                v.created_at, v.idempotency_key, v.deleted_at
            from dfs_file_versions v
            join dfs_files f on f.file_id = v.file_id
            where v.version_id = ?
            """,
            (resultSet, rowNum) -> new ManifestRow(
                resultSet.getString("file_id"),
                resultSet.getString("logical_path"),
                resultSet.getString("version_id"),
                resultSet.getLong("size_bytes"),
                resultSet.getString("checksum"),
                getInstant(resultSet, "created_at"),
                resultSet.getString("idempotency_key"),
                getInstant(resultSet, "deleted_at")
            ),
            versionId
        );
        if (manifestRow == null) {
            return null;
        }

        List<String> chunkIds = jdbcTemplate.query(
            """
            select chunk_id
            from dfs_version_chunks
            where version_id = ?
            order by chunk_order
            """,
            (resultSet, rowNum) -> resultSet.getString("chunk_id"),
            versionId
        );
        return manifestRow.toManifest(chunkIds);
    }

    private List<FileManifest> loadManifestsByVersionIds(List<String> versionIds) {
        if (versionIds.isEmpty()) {
            return List.of();
        }

        MapSqlParameterSource parameters = new MapSqlParameterSource("versionIds", versionIds);
        List<ManifestRow> manifestRows = namedParameterJdbcTemplate.query(
            """
            select v.version_id, v.file_id, f.logical_path, v.size_bytes, v.checksum,
                v.created_at, v.idempotency_key, v.deleted_at
            from dfs_file_versions v
            join dfs_files f on f.file_id = v.file_id
            where v.version_id in (:versionIds)
            """,
            parameters,
            (resultSet, rowNum) -> new ManifestRow(
                resultSet.getString("file_id"),
                resultSet.getString("logical_path"),
                resultSet.getString("version_id"),
                resultSet.getLong("size_bytes"),
                resultSet.getString("checksum"),
                getInstant(resultSet, "created_at"),
                resultSet.getString("idempotency_key"),
                getInstant(resultSet, "deleted_at")
            )
        );

        Map<String, ManifestRow> manifestRowsByVersionId = new LinkedHashMap<>();
        for (ManifestRow manifestRow : manifestRows) {
            manifestRowsByVersionId.put(manifestRow.versionId(), manifestRow);
        }

        Map<String, List<String>> chunkIdsByVersionId = new HashMap<>();
        List<VersionChunkRow> versionChunkRows = namedParameterJdbcTemplate.query(
            """
            select version_id, chunk_id
            from dfs_version_chunks
            where version_id in (:versionIds)
            order by version_id, chunk_order
            """,
            parameters,
            (resultSet, rowNum) -> new VersionChunkRow(
                resultSet.getString("version_id"),
                resultSet.getString("chunk_id")
            )
        );
        for (VersionChunkRow versionChunkRow : versionChunkRows) {
            chunkIdsByVersionId.computeIfAbsent(
                versionChunkRow.versionId(),
                ignored -> new ArrayList<>()
            ).add(versionChunkRow.chunkId());
        }

        List<FileManifest> manifests = new ArrayList<>();
        for (String versionId : versionIds) {
            ManifestRow manifestRow = manifestRowsByVersionId.get(versionId);
            if (manifestRow == null) {
                continue;
            }
            manifests.add(
                manifestRow.toManifest(chunkIdsByVersionId.getOrDefault(versionId, List.of()))
            );
        }
        return manifests;
    }

    private ChunkRow findChunkRow(String chunkId) {
        return querySingle(
            """
            select chunk_id, checksum, size_bytes, last_unreferenced_at
            from dfs_chunks
            where chunk_id = ?
            """,
            (resultSet, rowNum) -> new ChunkRow(
                resultSet.getString("chunk_id"),
                resultSet.getString("checksum"),
                resultSet.getInt("size_bytes"),
                getInstant(resultSet, "last_unreferenced_at")
            ),
            chunkId
        );
    }

    private List<ChunkRecord> loadChunkRecordsByIds(List<String> chunkIds) {
        if (chunkIds.isEmpty()) {
            return List.of();
        }

        MapSqlParameterSource parameters = new MapSqlParameterSource("chunkIds", chunkIds);
        List<ChunkRow> chunkRows = namedParameterJdbcTemplate.query(
            """
            select chunk_id, checksum, size_bytes, last_unreferenced_at
            from dfs_chunks
            where chunk_id in (:chunkIds)
            order by chunk_id
            """,
            parameters,
            (resultSet, rowNum) -> new ChunkRow(
                resultSet.getString("chunk_id"),
                resultSet.getString("checksum"),
                resultSet.getInt("size_bytes"),
                getInstant(resultSet, "last_unreferenced_at")
            )
        );
        return buildChunkRecords(chunkRows);
    }

    private List<ChunkRecord> loadChunkRecordsByQuery(String sql, Object... args) {
        List<ChunkRow> chunkRows = jdbcTemplate.query(
            sql,
            (resultSet, rowNum) -> new ChunkRow(
                resultSet.getString("chunk_id"),
                resultSet.getString("checksum"),
                resultSet.getInt("size_bytes"),
                getInstant(resultSet, "last_unreferenced_at")
            ),
            args
        );
        return buildChunkRecords(chunkRows);
    }

    private List<ChunkRecord> buildChunkRecords(List<ChunkRow> chunkRows) {
        if (chunkRows.isEmpty()) {
            return List.of();
        }

        List<String> chunkIds = chunkRows.stream().map(ChunkRow::chunkId).toList();
        Map<String, Set<String>> replicasByChunkId = loadReplicaNodeIds(chunkIds);
        Map<String, Set<String>> referencesByChunkId = loadActiveReferenceVersionIds(chunkIds);

        List<ChunkRecord> chunkRecords = new ArrayList<>();
        for (ChunkRow chunkRow : chunkRows) {
            chunkRecords.add(new ChunkRecord(
                chunkRow.chunkId(),
                chunkRow.checksum(),
                chunkRow.sizeBytes(),
                replicasByChunkId.getOrDefault(chunkRow.chunkId(), Set.of()),
                referencesByChunkId.getOrDefault(chunkRow.chunkId(), Set.of()),
                chunkRow.lastUnreferencedAt()
            ));
        }
        return chunkRecords;
    }

    private Map<String, Set<String>> loadReplicaNodeIds(List<String> chunkIds) {
        Map<String, Set<String>> replicaNodeIds = new HashMap<>();
        MapSqlParameterSource parameters = new MapSqlParameterSource("chunkIds", chunkIds);
        List<ChunkReplicaRow> chunkReplicaRows = namedParameterJdbcTemplate.query(
            """
            select chunk_id, node_id
            from dfs_chunk_replicas
            where chunk_id in (:chunkIds)
            order by chunk_id, node_id
            """,
            parameters,
            (resultSet, rowNum) -> new ChunkReplicaRow(
                resultSet.getString("chunk_id"),
                resultSet.getString("node_id")
            )
        );
        for (ChunkReplicaRow chunkReplicaRow : chunkReplicaRows) {
            replicaNodeIds.computeIfAbsent(
                chunkReplicaRow.chunkId(),
                ignored -> new HashSet<>()
            ).add(chunkReplicaRow.nodeId());
        }
        return replicaNodeIds;
    }

    private Map<String, Set<String>> loadActiveReferenceVersionIds(List<String> chunkIds) {
        Map<String, Set<String>> referencedVersionIds = new HashMap<>();
        MapSqlParameterSource parameters = new MapSqlParameterSource("chunkIds", chunkIds);
        List<ChunkReferenceRow> chunkReferenceRows = namedParameterJdbcTemplate.query(
            """
            select vc.chunk_id, vc.version_id
            from dfs_version_chunks vc
            join dfs_file_versions fv on fv.version_id = vc.version_id
            where vc.chunk_id in (:chunkIds) and fv.deleted_at is null
            order by vc.chunk_id, vc.version_id
            """,
            parameters,
            (resultSet, rowNum) -> new ChunkReferenceRow(
                resultSet.getString("chunk_id"),
                resultSet.getString("version_id")
            )
        );
        for (ChunkReferenceRow chunkReferenceRow : chunkReferenceRows) {
            referencedVersionIds.computeIfAbsent(
                chunkReferenceRow.chunkId(),
                ignored -> new HashSet<>()
            ).add(chunkReferenceRow.versionId());
        }
        return referencedVersionIds;
    }

    private int countActiveReferences(String chunkId) {
        Integer activeReferenceCount = querySingleValue(
            """
            select count(*)
            from dfs_version_chunks vc
            join dfs_file_versions fv on fv.version_id = vc.version_id
            where vc.chunk_id = ? and fv.deleted_at is null
            """,
            Integer.class,
            chunkId
        );
        return activeReferenceCount == null ? 0 : activeReferenceCount;
    }

    private <T> T querySingle(String sql, RowMapper<T> rowMapper, Object... args) {
        List<T> results = jdbcTemplate.query(sql, rowMapper, args);
        if (results.isEmpty()) {
            return null;
        }
        return results.getFirst();
    }

    private <T> T querySingleValue(String sql, Class<T> type, Object... args) {
        List<T> results = jdbcTemplate.queryForList(sql, type, args);
        if (results.isEmpty()) {
            return null;
        }
        return results.getFirst();
    }

    private static Instant getInstant(ResultSet resultSet, String columnName) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(columnName);
        if (timestamp == null) {
            return null;
        }
        return timestamp.toInstant();
    }

    private static Timestamp toTimestamp(Instant instant) {
        if (instant == null) {
            return null;
        }
        return Timestamp.from(instant);
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
            manifest.ownerUserId(),
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

    private record ManifestRow(
        String fileId,
        String logicalPath,
        String versionId,
        long sizeBytes,
        String checksum,
        Instant createdAt,
        String idempotencyKey,
        Instant deletedAt
    ) {

        private FileManifest toManifest(List<String> chunkIds) {
            return new FileManifest(
                fileId,
                null,
                logicalPath,
                versionId,
                List.copyOf(chunkIds),
                sizeBytes,
                checksum,
                createdAt,
                idempotencyKey,
                deletedAt
            );
        }
    }

    private record ChunkRow(
        String chunkId,
        String checksum,
        int sizeBytes,
        Instant lastUnreferencedAt
    ) {
    }

    private record VersionChunkRow(String versionId, String chunkId) {
    }

    private record ChunkReplicaRow(String chunkId, String nodeId) {
    }

    private record ChunkReferenceRow(String chunkId, String versionId) {
    }
}
