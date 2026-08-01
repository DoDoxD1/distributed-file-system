# Developer Guide

This guide documents module responsibilities, extension points, and configuration governance for the distributed file storage MVP.

## Architecture mapping to `plan.md`

The implementation preserves control-plane/data-plane separation:

- Control plane:
  - `GatewayService` validates requests and coordinates durable writes.
- Metadata authority:
  - `MetadataService` manages namespace, manifests, versions, chunk references, and tombstones using transactional relational persistence.
- Data plane:
  - `StorageNode` handles checksum-verified immutable chunk persistence.
- Background workers:
  - `BackgroundWorkerService` runs scan, repair, and GC flows.

## Package map

- `com.distributedfs.config`
  - `DistributedFsProperties`
  - `ServiceConfiguration`
  - `OpenApiConfiguration`
- `com.distributedfs.model`
  - `FileManifest`, `ChunkRecord`, `ChunkWrite`, `FileListing`
- `com.distributedfs.service`
  - `MetadataService`, `GatewayService`, `StorageNode`, `BackgroundWorkerService`
- `com.distributedfs.placement`
  - `RackAwarePlacementStrategy`
- `com.distributedfs.cluster`
  - `LocalCluster`, `LocalClusterFactory`
- `com.distributedfs.api`
  - `FileController`, `WorkerController`, `GlobalExceptionHandler`
- `com.distributedfs.error`
  - domain-specific exception hierarchy
- `com.distributedfs.util`
  - chunking and hashing helpers
- `src/main/resources/db/migration`
  - Flyway metadata schema migrations

## Data flow details

### Upload

1. Gateway validates logical path, payload, and optional idempotency key.
2. Payload is chunked using fixed-size chunking.
3. Each chunk receives a SHA-256 chunk ID.
4. Placement strategy chooses healthy nodes across failure domains.
5. Node writes are checksum-validated and retried on alternative healthy targets.
6. Metadata commit is atomic and only succeeds after durable replica acknowledgements.
7. New file version becomes visible once manifest commit completes.

### Download

1. Gateway resolves the target manifest (latest active or explicit version).
2. Chunks are read from available replicas.
3. Per-chunk and whole-file checksums are verified.
4. Payload is returned to caller as bytes.

### Delete

1. Metadata marks the target version deleted first.
2. Chunk references are released.
3. Background GC purges unreferenced chunks after retention threshold.

### Maintenance workers

- `scanAndPruneMissingReplicas()`
  - removes stale/corrupt replica references from metadata.
- `repairUnderReplicatedChunks()`
  - restores replicas back to configured durability where possible.
- `garbageCollect(Instant)`
  - deletes unreferenced chunk files and purges metadata records.

## Consistency and durability semantics

- Metadata is strongly consistent via database transactions scoped to manifest and chunk updates.
- Chunk writes are acknowledged only after configured replica threshold is met.
- Latest active listings are strongly consistent with metadata state.
- Version deletes use tombstones before physical cleanup.

## Configuration registry

Per repository policy, runtime-overridable values are centralized.

| Config module/file | Purpose | Runtime keys |
| --- | --- | --- |
| `src/main/resources/application.yml` | Default distributed FS runtime values and optional local secret import | `distributed.fs.*`, `spring.config.import` |
| `com.distributedfs.config.DistributedFsProperties` | Typed config binding and cross-field validation | `distributed.fs.chunk-size-bytes`, `distributed.fs.replication-factor`, `distributed.fs.gc-retention-seconds`, `distributed.fs.node-count`, `distributed.fs.storage-root`, `distributed.fs.failure-domains` |
| `src/main/resources/application.yml` | Metadata datasource and pool configuration | `spring.datasource.url`, `spring.datasource.username`, `spring.datasource.password`, `spring.datasource.driver-class-name`, `spring.datasource.hikari.maximum-pool-size`, `spring.datasource.hikari.connection-timeout`, `spring.datasource.hikari.data-source-properties.sslmode` |
| `src/main/resources/application.yml` | Flyway migration configuration | `spring.flyway.enabled`, `spring.flyway.locations` |
| `src/main/resources/application.yml` | Swagger/OpenAPI endpoint configuration | `springdoc.api-docs.path`, `springdoc.swagger-ui.path`, `springdoc.swagger-ui.operations-sorter`, `springdoc.swagger-ui.tags-sorter`, `springdoc.swagger-ui.display-request-duration` |

Preferred hosted metadata environment variables:

- `SUPABASE_DB_JDBC_URL`
- `SUPABASE_DB_USERNAME`
- `SUPABASE_DB_PASSWORD`
- `SUPABASE_DB_SSLMODE`

Spring Boot optionally imports a repo-root `.env` file via `spring.config.import`, allowing
developers to keep local secrets outside committed configuration while still resolving standard
property placeholders.

Fallback local metadata environment variables remain supported:

- `DFS_METADATA_DATASOURCE_URL`
- `DFS_METADATA_DATASOURCE_USERNAME`
- `DFS_METADATA_DATASOURCE_PASSWORD`
- `DFS_METADATA_DATASOURCE_MAX_POOL_SIZE`
- `DFS_METADATA_DATASOURCE_CONNECTION_TIMEOUT_MS`

## API contract summary

### File API (`/api/v1/files`)

- `POST /api/v1/files`
  - request: logical path, base64 payload, optional idempotency key
  - response: committed manifest
- `GET /api/v1/files/content`
  - request: path, optional version ID
  - response: base64 payload
- `GET /api/v1/files/manifest`
  - request: path, optional version ID, optional `includeDeleted`
  - response: manifest (including deleted flag)
- `DELETE /api/v1/files`
  - request: path, optional version ID
  - response: deleted manifest
- `GET /api/v1/files`
  - request: optional prefix
  - response: active file listing
- `GET /api/v1/files/versions/{encodedPath}`
  - request: base64-url encoded logical path
  - response: ordered versions

### Worker API (`/api/v1/workers`)

- `POST /api/v1/workers/scan`
- `POST /api/v1/workers/repair`
- `POST /api/v1/workers/gc?referenceTime=<ISO-8601>`

### API documentation endpoints

- `GET /swagger-ui.html` - interactive Swagger UI
- `GET /v3/api-docs` - OpenAPI JSON

## Testing strategy

Current tests validate behavior changes requested in `plan.md`:

- `GatewayServiceTest`
  - upload/download/version/list/delete/idempotency flows
  - metadata persistence across cluster rebuilds
- `BackgroundWorkerServiceTest`
  - replica scan+repair lifecycle
  - retention-based garbage collection

Tests are integration-style using `LocalClusterFactory` with per-test temporary storage directories and a file-backed H2 metadata database migrated with Flyway.

## Known MVP limits and next steps

- Metadata durability depends on the configured relational database instance; Supabase improves metadata resilience, but local chunk storage and the single app host remain deployment-level SPOFs.
- Worker scheduling is manual/API-triggered; no periodic scheduler yet.
- No authentication/authorization in current API layer.
- No rate limiting/backpressure controls yet.

Natural next hardening milestones:

1. Persist metadata in a consensus-backed store.
2. Add health scoring and rebalancing logic.
3. Add authn/authz, quotas, and admission control.
4. Add background scheduling and metrics export.
