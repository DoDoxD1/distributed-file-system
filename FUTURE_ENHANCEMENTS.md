# Future Enhancements

This document captures the highest-value next steps for the Distributed File Storage System after the current MVP. The project is deployable for a portfolio demo, internal use, or a controlled pilot, but it is not yet fully production-hardened.

## Current posture

The system already provides:

- Chunked upload, download, delete, list, and version flows
- Transactional relational metadata with Flyway migrations
- Rack-aware replica placement and durability checks
- Background scan, repair, garbage-collection, and local-to-bucket migration workers
- Hybrid authentication with short-lived access tokens and refresh-token cookies
- Per-user logical namespace isolation
- Configurable chunk storage using either local filesystem nodes or Oracle Object Storage
- Oracle Object Storage direct-upload sessions with signed PUT targets, finalize verification, and per-user deduplicated object-backed versions
- Public operational health and version endpoints
- Centralized API CORS configuration for localhost development and deployed browser frontends
- Integration-style coverage for gateway, worker, and auth flows

## Production-readiness gaps

The main reasons this should still be treated as an MVP are:

- Worker execution is manual and API-triggered instead of scheduled
- Oracle Object Storage cutovers still rely on an operator-triggered migration step
- Standard base64 uploads and all downloads still pass through the API process
- Direct upload exists for Oracle-backed upload bytes, but signed direct downloads, resumable multipart upload, and abandoned-session cleanup are not implemented yet
- Rate limiting and admission control are not implemented
- Operational observability is still limited
- Recovery and deployment runbooks need to be formalized
- Secure refresh-cookie flow assumes HTTPS in real deployments

## Current direct file transfer posture

The project has now started the chosen direct-transfer architecture: direct object upload with API-issued signed URLs and user-level deduplication.

That means:

- The API already acts as the control plane for authentication, path ownership, upload-session issuance, staged-object verification, and metadata commit
- Oracle Object Storage already acts as the upload data plane for direct-upload sessions
- Deduplication is scoped per user with the identity `(owner_user_id, sha256, size_bytes)`
- Existing chunked API endpoints remain in place for backward compatibility and for non-direct-upload flows

What is still missing from the full rollout:

- Signed direct-download URLs
- Resumable or multipart upload support for very large objects
- Cleanup of abandoned staging objects and expired upload sessions
- Clear operator tooling and runbooks for mixed chunk-backed and object-backed data

The rejected alternatives were client-side chunking with signed chunk URLs and direct upload followed by asynchronous ingest into chunked storage. Both keep more complexity in the client or in background processing than this project needs for the next iteration.

## Priority roadmap

### Phase 1: Deployment hardening

Goal: make the current application safe to operate in a real hosted environment.

- Put the app behind HTTPS from day one
- Document deployment environment variables and startup expectations
- Add a database reset and recovery runbook for Flyway-managed environments
- Add health/readiness expectations for application startup, database connectivity, and storage-root access
- Validate secure-cookie behavior for real frontend or API-client deployments

### Phase 2: Reliability and automation

Goal: reduce operational fragility and manual recovery work.

- Schedule background replica scan, repair, garbage collection, and local-to-bucket migration verification automatically
- Add cron-style worker execution with bounded concurrency, logging, and retry policy
- Add metadata backup and restore procedures
- Add startup validation for storage-root availability and writable paths
- Improve node health scoring and replica repair prioritization
- Add storage-capacity checks and failure-mode handling before writes

### Phase 3: Security hardening

Goal: improve resilience against misuse and strengthen trust boundaries.

- Add rate limiting for authentication and file APIs
- Add audit-friendly security logging for login, refresh, and worker invocations
- Validate bucket-side CORS plus cookie policy for cross-origin browser deployments
- Add stronger operational guidance around secrets, credential rotation, and least-privilege database access
- Extend role-based authorization to future administrative endpoints

### Phase 4: Observability and operations

Goal: make failures visible and diagnosable without manual digging.

- Add structured request logging with traceable request and object identifiers
- Publish metrics for upload latency, chunk durability, repair activity, and GC outcomes
- Add dashboards and alerts for migration failure, replication deficit, worker errors, storage exhaustion, and stuck scheduled jobs
- Expose operational health summaries for storage nodes and chunk replica state
- Add clear runbooks for common incidents and partial-failure recovery

### Phase 5: API and product polish

Goal: improve adoption, usability, and day-2 developer experience.

- Expand the current direct-transfer architecture with signed download URLs, resumable multipart upload, staged-object cleanup, and clearer client integration guidance
- Publish a maintained Postman collection for auth and file flows
- Document example client flows for register, login, refresh, standard upload, direct upload, finalize, and download
- Improve error response consistency and troubleshooting guidance
- Add deployment examples for local, VM, and managed-database setups
- Expand developer docs for common operational tasks

## Highest-impact next enhancements

If only a few items are implemented next, these would likely provide the biggest return:

1. Add scheduled background workers and cron-style execution control
2. Complete the remaining direct-transfer rollout: signed downloads, resumable uploads, and lifecycle cleanup around direct-upload sessions
3. Add deployment and recovery runbooks
4. Add rate limiting to auth and upload APIs
5. Add metrics, alerts, and structured operational logs

## Longer-term architecture evolution

For a more production-oriented distributed storage system, the longer horizon could include:

- Automated cutover tooling for legacy local chunks after a backend switch
- Full rollout of the current direct-transfer model across signed downloads, resumable upload, dedup finalization, cleanup, and migration tooling
- Multi-host or externalized chunk storage instead of single-host local disk
- Better health-aware or load-aware placement decisions
- Metadata snapshots and stronger disaster recovery guarantees
- Rebalancing logic for uneven storage distribution
- Quotas and tenant-level admission control
- Cross-region replication or disaster recovery strategy
- Erasure coding for more efficient storage durability at scale

## Recommendation

The current system is strong enough to deploy as an MVP and showcase end-to-end engineering ability. The next milestone should be operational hardening plus completion of the direct-transfer edges that are already partially implemented. Reliability, security, and observability improvements will increase real deployment confidence more than adding unrelated user-facing features first.

The chosen storage-transfer evolution remains direct client-to-bucket transfer with API-controlled session lifecycle and user-level dedup; the next work should finish that rollout rather than redesign it.
