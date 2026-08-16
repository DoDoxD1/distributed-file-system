# Developer Guide

This guide documents module responsibilities, extension points, and configuration governance for the distributed file storage MVP.

## Architecture mapping to `plan.md`

The implementation preserves control-plane/data-plane separation:

- Control plane:
  - `GatewayService` validates requests and coordinates durable writes.
- Auth and request identity:
  - `AuthenticationService`, `AuthenticationInterceptor`, and `RequestUserContext` manage user sessions and bearer-token identity.
- Metadata authority:
  - `MetadataService` manages namespace, manifests, versions, chunk references, and tombstones using transactional relational persistence.
- Data plane:
  - `StorageNode` defines checksum-verified immutable chunk persistence.
  - `LocalStorageNode` persists chunk replicas under `storageRoot/<nodeId>/chunks`.
  - `OracleObjectStorageNode` persists chunk replicas in Oracle Object Storage through `OracleObjectStorageBucketClient`.
  - `DirectTransferService` plans direct object uploads, verifies staged objects, and commits deduplicated object-backed versions.
  - `OperationalStatusService` exposes system health and version metadata for public operational endpoints.
- Background workers:
  - `BackgroundWorkerService` runs scan, repair, GC, and legacy local-to-bucket migration flows.

## Package map

- `com.distributedfs.config`
  - `DistributedFsProperties`
  - `FrontendUrlConstants`
  - `ServiceConfiguration`
  - `WebConfiguration`
  - `OpenApiConfiguration`
- `com.distributedfs.model`
  - `AuthenticatedUser`, `AuthenticatedSession`, `FileManifest`, `ChunkRecord`, `ChunkWrite`, `FileListing`, `DirectUploadSession`, `DirectUploadTarget`, `StoredObject`, `SystemHealth`, `ApplicationVersionInfo`
- `com.distributedfs.service`
  - `AuthenticationService`, `UserFileService`, `MetadataService`, `GatewayService`, `DirectTransferService`, `StorageNode`, `LocalStorageNode`, `OracleObjectStorageNode`, `OracleObjectStorageBucketClient`, `OciOracleObjectStorageBucketClient`, `BackgroundWorkerService`, `OperationalStatusService`
- `com.distributedfs.placement`
  - `RackAwarePlacementStrategy`
- `com.distributedfs.cluster`
  - `LocalCluster`, `LocalClusterFactory`
- `com.distributedfs.api`
  - `AuthController`, `FileController`, `WorkerController`, `OperationalController`, `AuthenticationInterceptor`, `WorkerAuthorizationInterceptor`, `RequestUserContext`, `GlobalExceptionHandler`

## Data flow details

### Upload

1. API layer authenticates the bearer token and loads the request user.
2. `UserFileService` rewrites the public logical path into the user's private namespace.
3. Gateway validates logical path, payload, and optional idempotency key.
4. Payload is chunked using fixed-size chunking.
5. Each chunk receives a SHA-256 chunk ID.
6. Placement strategy chooses healthy nodes across failure domains.
7. Node writes are checksum-validated and retried on alternative healthy targets.
8. Metadata commit is atomic and only succeeds after durable replica acknowledgements.
9. New file version becomes visible once manifest commit completes.

### Direct upload (Oracle Object Storage)

1. API layer authenticates the bearer token and loads the request user.
2. `DirectTransferService.createUploadSession()` validates path, checksum, size, content type, and optional idempotency key.
3. Metadata either reuses an existing deduplicated stored object for the same `(owner_user_id, sha256, size_bytes)` or creates a new upload session in `AWAITING_UPLOAD` state.
4. When upload is required, `OracleObjectStorageBucketClient.createUploadTarget()` returns a signed PUT target for the session staging object.
5. The client uploads bytes directly to Oracle Object Storage with the expected checksum metadata.
6. `DirectTransferService.finalizeUploadSession()` verifies staged object existence, size, and checksum before promoting it to the canonical object key.
7. `MetadataService.commitDirectUploadSession()` commits the resulting manifest transactionally, links the file version to the stored object, and records the completed version ID on the session.
8. Subsequent downloads continue through the authenticated file API, which now supports object-backed versions in addition to chunk-backed versions.

### Authentication

1. `AuthenticationService.register()` validates and normalizes credentials, persists the user, reloads the stored row, and issues both an access token and refresh token.
2. `AuthenticationService.login()` verifies the PBKDF2 password hash and rotates to a fresh access token plus refresh token pair.
3. `AuthController` returns the access token in JSON and writes the refresh token as an `HttpOnly` cookie.
4. `AuthenticationService.refresh()` validates the refresh token, rotates both tokens, and invalidates the previous refresh token.
5. `AuthenticationService.logout()` revokes the current user's refresh session and active access session when the refresh cookie is presented.
6. `AuthenticationInterceptor` hashes bearer access tokens, resolves the active access session, and stores the authenticated user on the request.
7. `ServiceConfiguration` calls `AuthenticationService.ensureBootstrapAdmin()` at startup to seed the single admin account from `distributed.fs.bootstrap-admin.*` when needed.
8. `WorkerAuthorizationInterceptor` allows worker requests only when the authenticated user carries the persisted admin flag.
9. Expired access or refresh tokens are removed eagerly during authentication/refresh.

### Download

1. API layer authenticates the bearer token and loads the request user.
2. `UserFileService` rewrites the public logical path into the user's private namespace.
3. Gateway resolves the target manifest (latest active or explicit version).
4. Chunks are read from available replicas.
5. For direct-upload-backed versions, Gateway resolves the linked stored object and downloads it through the optional Oracle bucket client.
6. Per-chunk or whole-object checksums are verified.
7. Payload is returned to caller as bytes.

### Delete

1. API layer authenticates the bearer token and loads the request user.
2. `UserFileService` rewrites the public logical path into the user's private namespace.
3. Metadata marks the target version deleted first.
4. Chunk references are released.
5. Background GC purges unreferenced chunks after retention threshold.

### Maintenance workers

- `scanAndPruneMissingReplicas()`
  - removes stale/corrupt replica references from metadata.
- `repairUnderReplicatedChunks()`
  - restores replicas back to configured durability where possible.
- `garbageCollect(Instant)`
  - deletes unreferenced chunk files and purges metadata records.
- `migrateLocalChunksToBucket()`
  - runs only when `distributed.fs.storage-backend=oracle-object-storage`.
  - reads legacy local chunk files from `distributed.fs.storage-root/<nodeId>/chunks/*.chunk`.
  - writes them to the Oracle-backed node with the same `nodeId`.
  - deletes each local source file only after a successful write.

### Legacy local-to-bucket migration

1. Switch runtime configuration to `distributed.fs.storage-backend=oracle-object-storage`.
2. Keep the legacy local chunk files under the existing `distributed.fs.storage-root`.
3. Call `POST /api/v1/workers/migrate-local-chunks` with an authenticated bearer token.
4. `BackgroundWorkerService` walks each legacy `storageRoot/<nodeId>/chunks` directory.
5. Each chunk is checksum-verified through `StorageNode.writeChunk()` before the local source is deleted.
6. The flow is idempotent for already-migrated bucket objects because duplicate writes are checksum-checked.

### Operational endpoints

1. `OperationalController` exposes unauthenticated `/api/v1/system/health` and `/api/v1/system/version` endpoints.
2. `OperationalStatusService.health()` executes a real metadata database ping using `select 1` and returns `UP` status only when the query succeeds.
3. Database failures are surfaced as HTTP 503 through `ServiceUnavailableException`.
4. `OperationalStatusService.version()` returns the Spring application name and resolved build version metadata.

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
| `com.distributedfs.config.DistributedFsProperties` | Typed config binding and cross-field validation | `distributed.fs.chunk-size-bytes`, `distributed.fs.replication-factor`, `distributed.fs.gc-retention-seconds`, `distributed.fs.node-count`, `distributed.fs.storage-backend`, `distributed.fs.bootstrap-admin.email`, `distributed.fs.bootstrap-admin.password`, `distributed.fs.max-file-size-bytes`, `distributed.fs.max-user-storage-bytes`, `distributed.fs.access-token-ttl-seconds`, `distributed.fs.refresh-token-ttl-seconds`, `distributed.fs.refresh-cookie-name`, `distributed.fs.refresh-cookie-path`, `distributed.fs.refresh-cookie-secure`, `distributed.fs.refresh-cookie-same-site`, `distributed.fs.cors-allowed-origin-patterns`, `distributed.fs.storage-root`, `distributed.fs.oracle-object-storage.namespace`, `distributed.fs.oracle-object-storage.bucket`, `distributed.fs.oracle-object-storage.object-prefix`, `distributed.fs.oracle-object-storage.config-file-path`, `distributed.fs.oracle-object-storage.config-profile`, `distributed.fs.oracle-object-storage.connection-timeout-millis`, `distributed.fs.oracle-object-storage.read-timeout-millis`, `distributed.fs.oracle-object-storage.max-retries`, `distributed.fs.oracle-object-storage.initial-backoff-millis`, `distributed.fs.failure-domains` |
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

### Auth API (`/api/v1/auth`)

- `POST /api/v1/auth/register`
  - request: email, password
  - response: 15-minute bearer access token, expiry, authenticated user
  - side effect: sets rotated refresh token cookie
- `POST /api/v1/auth/login`
  - request: email, password
  - response: fresh bearer access token, expiry, authenticated user
  - side effect: sets rotated refresh token cookie
- `POST /api/v1/auth/refresh`
  - request: refresh token cookie
  - response: fresh bearer access token, expiry, authenticated user
  - side effect: rotates refresh token cookie
- `POST /api/v1/auth/logout`
  - request: optional refresh token cookie
  - response: empty success response
  - side effect: clears the refresh cookie and revokes the current user's active access/refresh sessions when the cookie matches a live refresh session

### File API (`/api/v1/files`)

- all requests require `Authorization: Bearer <token>`
- `POST /api/v1/files`
  - request: logical path, base64 payload, optional idempotency key
  - response: committed manifest with `ownerUserId`
- `POST /api/v1/files/direct/upload-sessions`
  - request: logical path, checksum SHA-256, size bytes, optional content type, optional idempotency key
  - response: `DirectUploadSessionResponse` including session status, optional signed upload target, and optional committed version ID
- `GET /api/v1/files/direct/upload-sessions/{sessionId}`
  - request: session ID path parameter
  - response: latest `DirectUploadSessionResponse` for the authenticated user
- `POST /api/v1/files/direct/upload-sessions/{sessionId}/finalize`
  - request: session ID path parameter
  - response: committed manifest after staged object verification and object-backed version linking
- `GET /api/v1/files/content`
  - request: path, optional version ID
  - response: base64 payload
- `GET /api/v1/files/manifest`
  - request: path, optional version ID, optional `includeDeleted`
  - response: manifest (including deleted flag and `ownerUserId`)
- `DELETE /api/v1/files`
  - request: path, optional version ID
  - response: deleted manifest
- `GET /api/v1/files`
  - request: optional prefix in the authenticated user's namespace
  - response: active file listing
- `GET /api/v1/files/versions/{encodedPath}`
  - request: base64-url encoded logical path in the authenticated user's namespace
  - response: ordered active versions

### Worker API (`/api/v1/workers`)

- all requests require `Authorization: Bearer <token>` from the bootstrap admin user
- `POST /api/v1/workers/scan`
- `POST /api/v1/workers/repair`
- `POST /api/v1/workers/gc?referenceTime=<ISO-8601>`
- `POST /api/v1/workers/migrate-local-chunks`
  - request: no body
  - response: `WorkerRunResponse(worker, affectedCount)`
  - constraint: only valid when the active backend is Oracle Object Storage

### System API (`/api/v1/system`)

- `GET /api/v1/system/health`
  - response: metadata database status and timestamp
- `GET /api/v1/system/version`
  - response: application name and resolved build version

### API documentation endpoints

- `GET /swagger-ui.html` - interactive Swagger UI
- `GET /v3/api-docs` - OpenAPI JSON

## Testing strategy

Current tests validate behavior changes requested in `plan.md`:

- `GatewayServiceTest`
  - upload/download/version/list/delete/idempotency flows
  - metadata persistence across cluster rebuilds
- `UserFileServiceTest`
  - registration/login/access-token plus refresh-token rotation behavior
  - logout revocation of the current access and refresh sessions
  - per-user namespace isolation for identical public logical paths
- `DirectTransferServiceTest`
  - direct upload session creation, signed upload target planning, finalize behavior, and dedup-aware object reuse
- `AuthControllerIntegrationTest`
  - secure refresh-cookie issuance and refresh rotation over the real HTTP path
  - logout cookie clearing and refresh-session revocation behavior
- `FileControllerIntegrationTest`
  - authenticated file API behavior, including direct upload session HTTP flows
- `BackgroundWorkerServiceTest`
  - replica scan+repair lifecycle
  - retention-based garbage collection
  - local-to-bucket migration behavior against Oracle-backed nodes using an in-memory bucket client
- `OperationalStatusServiceTest`
  - database ping success/failure mapping and version payload behavior
- `OperationalControllerIntegrationTest`
  - public system endpoint behavior for health and version routes
- `OracleObjectStorageNodeTest`
  - Oracle-backed node read/write/list/delete behavior and object-prefix handling
- `DistributedFsPropertiesTest`
  - backend-selection validation for Oracle Object Storage configuration
  - bootstrap-admin configuration validation and normalization
  - CORS allowed-origin-pattern normalization and validation
- `WorkerControllerIntegrationTest`
  - bootstrap-admin-only worker authorization
  - authenticated migration endpoint behavior when the Oracle backend is not active
- `OpenApiConfigurationTest`
  - OpenAPI security scheme and documentation metadata wiring

Tests are integration-style using `LocalClusterFactory` with per-test temporary storage directories and a file-backed H2 metadata database migrated with Flyway.

## Known MVP limits and next steps

- Metadata durability depends on the configured relational database instance; the single app host still coordinates all worker execution and API traffic.
- Worker scheduling is manual/API-triggered; no periodic scheduler yet.
- No rate limiting/backpressure controls yet.
- Standard base64 uploads and all downloads still transit the API process.
- Direct upload is implemented only for Oracle-backed upload bytes; signed direct downloads, resumable multipart upload, and abandoned-session cleanup are still pending.

Natural next hardening milestones:

1. Persist metadata in a consensus-backed store.
2. Add health scoring and rebalancing logic.
3. Add quotas, admission control, and rate limiting.
4. Add background scheduling and metrics export.
5. Expand the current direct-transfer flow with signed download URLs, resumable multipart upload, and lifecycle cleanup for staged objects and expired sessions.
