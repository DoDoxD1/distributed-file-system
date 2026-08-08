# Distributed File Storage System

I built this project to have an in depth understanding of distributed-systems design by implementing a file storage
service in Java 21 and Spring Boot.

## Projects snapshot

This project demonstrates my approach to designing and delivering robust backend systems, going well beyond simple CRUD APIs.

- Built end-to-end upload, download, delete, list, and version flows with immutable chunked
  storage.
- Implemented transactional relational metadata persistence with atomic manifest commit.
- Added rack-aware replica placement and durability checks before publish.
- Designed maintenance workers for replica scan, under-replication repair, retention-based
  garbage collection, and legacy local-to-bucket chunk migration.
- Added hybrid auth with short-lived access tokens, secure refresh cookies, and per-user file namespaces.
- Exposed REST APIs with centralized exception mapping and boundary validation.
- Added a pluggable chunk-storage layer with both local filesystem and Oracle Object Storage backends.
- Added interactive Swagger UI and OpenAPI docs for quick API exploration.
- Added JUnit tests that exercise gateway and worker behavior through a local in-process cluster.

## Tech stack

- Java 21
- Spring Boot 3.3
- Maven
- JUnit 5

## What I built

### 1) Data model and storage

- Files are split into fixed-size chunks (`distributed.fs.chunk-size-bytes`, default `1_048_576`).
- Each chunk has a SHA-256 content address (`chunkId`) and checksum validation.
- File versions are immutable manifests with ordered chunk IDs and tombstone support.
- Chunk replicas can be stored on either the local filesystem or Oracle Object Storage, selected by
  `distributed.fs.storage-backend`.

### 2) Metadata and consistency

- `MetadataService` is the source of truth for:
  - namespace (`logicalPath -> fileId`)
  - version history
  - chunk replica state
- Metadata is persisted in a Flyway-managed relational schema and committed transactionally.
- Manifest publish is atomic and happens only after required replica acknowledgements.

### 3) Replication and placement

- Replication factor is configurable (`distributed.fs.replication-factor`, default `3`).
- Placement prefers different failure domains before same-domain fallback.
- Default local topology is 4 nodes across 4 racks.

### 4) Background maintenance

- `scanAndPruneMissingReplicas()` removes stale or corrupt replica references.
- `repairUnderReplicatedChunks()` restores replica count toward configured durability.
- `garbageCollect()` removes unreferenced chunks after retention threshold.
- `migrateLocalChunksToBucket()` moves legacy local chunk files from
  `distributed.fs.storage-root/<nodeId>/chunks/*.chunk` into the configured Oracle bucket and deletes
  the local source after a successful write.

### 5) Authentication and ownership

- `POST /api/v1/auth/register` creates a user, returns a 15-minute bearer access token, and sets a refresh token cookie.
- `POST /api/v1/auth/login` rotates to a fresh access token plus refresh token cookie for an existing user.
- `POST /api/v1/auth/refresh` exchanges the refresh cookie for a new access token and rotated refresh cookie.
- File and worker APIs require `Authorization: Bearer <token>`.
- Logical file paths are isolated per user, so two users can both store `/docs/report.txt`
  independently.
- Access tokens live 15 minutes by default and refresh tokens live 24 hours by default.
- Refresh tokens are issued as `HttpOnly` cookies and default to `Secure` plus `SameSite=Strict`.

## API summary

Deployed base URL: `https://dfs-api.duckdns.org`
Swagger UI: `https://dfs-api.duckdns.org/swagger-ui.html`

Base path: `/api/v1`

### Auth

- `POST /auth/register` - create a user account, return access token, set refresh cookie
- `POST /auth/login` - authenticate, return access token, set refresh cookie
- `POST /auth/refresh` - rotate refresh cookie and return a new access token

### Files

- All file endpoints require `Authorization: Bearer <token>`.
- `POST /files` - upload base64 payload (optional idempotency key)
- `GET /files/content` - download payload as base64
- `GET /files/manifest` - fetch manifest by path/version (`includeDeleted` optional)
- `DELETE /files` - tombstone latest or specific version
- `GET /files` - list files by prefix within the authenticated user's namespace
- `GET /files/versions/{encodedPath}` - list active versions for a logical path in the user's namespace

### Workers

- Worker endpoints require `Authorization: Bearer <token>`.
- `POST /workers/scan`
- `POST /workers/repair`
- `POST /workers/gc`
- `POST /workers/migrate-local-chunks` - migrate legacy local chunks into Oracle Object Storage when
  `distributed.fs.storage-backend=oracle-object-storage`

### System

- `GET /system/health` - metadata database reachability check
- `GET /system/version` - application name and build version

### API docs

- Swagger UI: `/swagger-ui.html`
- OpenAPI JSON: `/v3/api-docs`

## Project layout

- `src/main/java/com/distributedfs/config` - typed config and service wiring
- `src/main/java/com/distributedfs/service` - gateway, metadata, storage node, workers
- `src/main/java/com/distributedfs/placement` - rack-aware placement strategy
- `src/main/java/com/distributedfs/api` - controllers, DTOs, and exception handler
- `src/main/java/com/distributedfs/cluster` - local in-process cluster factory
- `src/main/java/com/distributedfs/model` - manifests, chunk records, and listings
- `src/main/resources/db/migration` - Flyway metadata schema migrations
- `src/test/java/com/distributedfs/service` - gateway, auth/user namespace, and worker tests

## Configuration

Runtime settings are centralized in:

- `src/main/resources/application.yml`
- `com.distributedfs.config.DistributedFsProperties`

Supported keys under `distributed.fs`:

- `chunk-size-bytes`
- `replication-factor`
- `gc-retention-seconds`
- `node-count`
- `storage-backend`
- `max-file-size-bytes`
- `max-user-storage-bytes`
- `access-token-ttl-seconds`
- `refresh-token-ttl-seconds`
- `refresh-cookie-name`
- `refresh-cookie-path`
- `refresh-cookie-secure`
- `refresh-cookie-same-site`
- `storage-root`
- `oracle-object-storage.namespace`
- `oracle-object-storage.bucket`
- `oracle-object-storage.object-prefix`
- `oracle-object-storage.config-file-path`
- `oracle-object-storage.config-profile`
- `oracle-object-storage.connection-timeout-millis`
- `oracle-object-storage.read-timeout-millis`
- `oracle-object-storage.max-retries`
- `oracle-object-storage.initial-backoff-millis`
- `failure-domains`

Metadata datasource settings are configured via `spring.datasource.*` and environment overrides.
Supabase Postgres is the preferred hosted metadata backend:

- `SUPABASE_DB_JDBC_URL`
- `SUPABASE_DB_USERNAME`
- `SUPABASE_DB_PASSWORD`
- `SUPABASE_DB_SSLMODE`

The existing local overrides remain supported:

- `DFS_METADATA_DATASOURCE_URL`
- `DFS_METADATA_DATASOURCE_USERNAME`
- `DFS_METADATA_DATASOURCE_PASSWORD`
- `DFS_METADATA_DATASOURCE_MAX_POOL_SIZE`
- `DFS_METADATA_DATASOURCE_CONNECTION_TIMEOUT_MS`

For local development, Spring Boot also imports an optional repo-root `.env` file using
`spring.config.import`, so you can keep secrets outside `application.yml`.

For Oracle Object Storage local development, keep the OCI config path and bucket settings in `.env`, and
store the OCI profile plus private key outside the repository.

If you run locally over plain HTTP, a browser will not send a `Secure` refresh cookie. The default
is intentionally secure for production. Override `distributed.fs.refresh-cookie-secure=false` only
in local development if you need browser-based refresh over HTTP.

## Run locally

Prerequisites:

- JDK 21+
- Maven 3.6+
- A reachable PostgreSQL database for metadata persistence (Supabase recommended)

```bash
mvn test
mvn spring-boot:run
```

If your Maven environment uses a private mirror, configure credentials in `settings.xml`.

For Supabase, copy the connection details from your project dashboard into `.env` or your shell.
Use the direct JDBC host/port form from Supabase, not the REST API URL.

Typical Oracle VM deployment shape for this project:

- Spring Boot app on the VM
- Supabase Postgres or another PostgreSQL instance for metadata persistence
- Either local filesystem under `distributed.fs.storage-root` or Oracle Object Storage for chunk data
- Optional one-time `POST /api/v1/workers/migrate-local-chunks` call after switching from local storage to
  the Oracle backend

## Current limits

- Metadata is durable in PostgreSQL, but overall system availability still depends on the single app host and the chosen chunk backend configuration.
- Worker execution is API-triggered, not scheduled.
- Worker endpoints are authenticated, but they are not yet restricted to an admin-only role.
- Metadata replication and consensus are not included yet.
