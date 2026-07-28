# Distributed File Storage System Plan

This plan outlines a from-scratch conceptual architecture for a distributed file storage system that is simple enough to reason about first, then extensible toward production concerns.

## Goal

Design a system that stores large files reliably across multiple machines, supports scalable reads/writes, tolerates node failure, and keeps metadata management explicit and auditable.

## Scope

- Build around immutable file chunks plus separate metadata
- Support upload, download, delete, list, and file-version lookup
- Prioritize durability, operational clarity, and horizontal scale
- Exclude advanced multi-region and tenant-billing concerns from the first design pass

## Architecture Shape

### 1. Define the control plane and data plane

Split responsibilities early:

- **API/Gateway layer**: authenticates requests, validates inputs, coordinates uploads/downloads
- **Metadata service**: owns namespace, file manifests, versions, chunk maps, replication state
- **Storage nodes**: store chunk blobs and serve chunk reads/writes
- **Background workers**: replication repair, rebalancing, garbage collection, integrity scans

This separation keeps business rules out of raw storage nodes and makes future scaling/replacement easier.

### 2. Choose the storage model

Use content-addressed or chunk-addressed blobs:

- Break files into fixed or variable-size chunks
- Store each chunk with a unique `chunk_id`
- Represent a file as metadata: logical path, file id, version id, ordered chunk list, size, checksum, timestamps

Start with fixed-size chunks because they simplify placement, retries, and manifest handling.

### 3. Define placement and durability rules

For the first serious design:

- Replicate each chunk to `N` storage nodes, e.g. replication factor 3
- Place replicas across failure domains when possible
- Mark writes successful only after metadata records enough durable replicas

Prefer replication before erasure coding; it is easier to implement and debug.

## Core Data Flows

### Upload flow

- Client sends file-create request to gateway
- Gateway requests chunk placement plan from metadata service
- Client or gateway streams chunks to selected storage nodes
- Storage nodes verify checksum and acknowledge persistence
- Metadata service commits manifest only after required chunk acknowledgements
- File version becomes visible atomically after manifest commit

### Download flow

- Client requests file/version
- Gateway fetches manifest from metadata service
- Client reads chunks from nearest/healthiest replicas
- Downloader reassembles chunks and verifies end-to-end checksum

### Delete flow

- Metadata marks file version deleted first
- Background GC later removes unreferenced chunks after retention window

This avoids unsafe immediate hard-deletes.

## Consistency Model

Pick explicit semantics up front:

- **Metadata**: strongly consistent
- **Chunk data**: write acknowledged only after durability threshold
- **Directory/listing view**: can be strongly consistent if metadata store supports it

If designing from scratch, I would keep metadata strongly consistent via a replicated consensus-backed store, and keep storage nodes comparatively dumb.

## Failure Handling

Design around expected failures:

- Storage node down during write: retry alternate placement
- Replica loss after commit: background repair restores target replica count
- Metadata leader failover: consensus elects new leader
- Partial upload: never publish manifest; expire orphan chunks later
- Corrupt chunk: checksum mismatch triggers replica replacement

## Key Technical Decisions to Make Early

- Metadata backend: embedded Raft-backed service vs external consensus store
- Chunk size: tradeoff between metadata fanout and read amplification
- Replica placement strategy: random, rack-aware, load-aware
- Client upload strategy: proxy through gateway vs direct-to-storage with signed placement tokens
- Namespace model: flat object store vs hierarchical filesystem-like paths

## Non-Functional Requirements

The design should explicitly cover:

- **Observability**: request tracing, chunk audit logs, repair metrics, capacity metrics
- **Security**: authn/authz, encryption in transit, optional encryption at rest
- **Backpressure**: per-node throttling and admission control
- **Idempotency**: safe retry for chunk upload and manifest commit APIs
- **Upgrade safety**: versioned manifests and rolling compatibility rules

## Suggested Delivery Phases

### Phase 1: Single-region MVP architecture

- Replicated metadata service
- Chunked file storage
- Replication factor 3
- Basic upload/download/delete/list
- Checksums and background repair

### Phase 2: Hardening

- Rebalancing
- Garbage collection
- Snapshot/backup of metadata
- Quotas and rate limits
- Better placement and health scoring

### Phase 3: Advanced scale

- Erasure coding
- Cross-region replication
- Tiered storage
- CDN/edge reads
- Multi-tenant isolation

## Review Focus

Before implementation, validate these decisions first:

- whether the system behaves like an object store or a filesystem
- target consistency guarantees
- expected file sizes and throughput
- failure domain assumptions
- whether metadata must support rename/move semantics cheaply
