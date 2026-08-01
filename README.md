# Distributed File Storage System

I built this project to have an in depth understanding of distributed-systems design by implementing a file storage
service in Java 21 and Spring Boot.

## Projects snapshot

This project demonstrates my approach to designing and delivering robust backend systems, going well beyond simple CRUD APIs.

- Built end-to-end upload, download, delete, list, and version flows with immutable chunked
  storage.
- Implemented transactional relational metadata persistence with atomic manifest commit.
- Added rack-aware replica placement and durability checks before publish.
- Designed maintenance workers for replica scan, under-replication repair, and retention-based
  garbage collection.
- Exposed REST APIs with centralized exception mapping and boundary validation.
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

### 2) Metadata and consistency

- `MetadataService` is the source of truth for:
  - namespace (`logicalPath -> fileId`)
  - version history
  - chunk replica state
  - idempotency index
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

## API summary

Base path: `/api/v1`

### Files

- `POST /files` - upload base64 payload (optional idempotency key)
- `GET /files/content` - download payload as base64
- `GET /files/manifest` - fetch manifest by path/version (`includeDeleted` optional)
- `DELETE /files` - tombstone latest or specific version
- `GET /files` - list files by prefix
- `GET /files/versions/{encodedPath}` - list versions for a logical path

### Workers

- `POST /workers/scan`
- `POST /workers/repair`
- `POST /workers/gc`

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
- `src/test/java/com/distributedfs/service` - gateway and worker tests

## Configuration

Runtime settings are centralized in:

- `src/main/resources/application.yml`
- `com.distributedfs.config.DistributedFsProperties`

Supported keys under `distributed.fs`:

- `chunk-size-bytes`
- `replication-factor`
- `gc-retention-seconds`
- `node-count`
- `storage-root`
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
- Local filesystem under `distributed.fs.storage-root` for chunk data

## Current limits

- Metadata is durable in PostgreSQL, but overall system availability still depends on the single app host and local chunk storage.
- Worker execution is API-triggered, not scheduled.
- Metadata replication and consensus are not included yet.
