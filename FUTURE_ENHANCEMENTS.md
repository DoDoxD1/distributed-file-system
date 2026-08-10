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
- Integration-style coverage for gateway, worker, and auth flows

## Production-readiness gaps

The main reasons this should still be treated as an MVP are:

- Worker execution is manual and API-triggered instead of scheduled
- Oracle Object Storage cutovers still rely on an operator-triggered migration step
- File uploads and downloads still pass through the API process instead of using direct client-to-bucket transfer
- Rate limiting and admission control are not implemented
- Operational observability is still limited
- Recovery and deployment runbooks need to be formalized
- Secure refresh-cookie flow assumes HTTPS in real deployments

## Selected direction for direct file transfer

The chosen path for the next file-transfer architecture is direct object transfer with API-issued signed URLs and user-level deduplication.

That means:

- The API remains the control plane for authentication, path ownership, session issuance, object verification, and metadata commit
- The object storage bucket becomes the data plane for file upload and download
- Deduplication is scoped per user with the identity `(owner_user_id, sha256, size_bytes)`
- Existing chunked API endpoints can remain in place during rollout for backward compatibility

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
- Review CORS and cookie policy for cross-origin deployments
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

- Implement the selected direct-transfer architecture: API-issued signed upload and download URLs, user-owned object keys, finalize verification, and user-level dedup with `(owner_user_id, sha256, size_bytes)`
- Publish a maintained Postman collection for auth and file flows
- Document example client flows for register, login, refresh, upload, and download
- Improve error response consistency and troubleshooting guidance
- Add deployment examples for local, VM, and managed-database setups
- Expand developer docs for common operational tasks

## Highest-impact next enhancements

If only a few items are implemented next, these would likely provide the biggest return:

1. Add scheduled background workers and cron-style execution control
2. Implement Choice A: direct client-to-bucket transfer with API-issued signed URLs and user-level dedup
3. Add deployment and recovery runbooks
4. Add rate limiting to auth and upload APIs
5. Add metrics, alerts, and structured operational logs

## Longer-term architecture evolution

For a more production-oriented distributed storage system, the longer horizon could include:

- Automated cutover tooling for legacy local chunks after a backend switch
- Full rollout of the selected direct-transfer model across upload, download, dedup finalization, cleanup, and migration tooling
- Multi-host or externalized chunk storage instead of single-host local disk
- Better health-aware or load-aware placement decisions
- Metadata snapshots and stronger disaster recovery guarantees
- Rebalancing logic for uneven storage distribution
- Quotas and tenant-level admission control
- Cross-region replication or disaster recovery strategy
- Erasure coding for more efficient storage durability at scale

## Recommendation

The current system is strong enough to deploy as an MVP and showcase end-to-end engineering ability. The next milestone should be operational hardening, not major feature expansion. Reliability, security, and observability improvements will increase real deployment confidence more than adding new user-facing features first.

Once that hardening work is in place, the selected storage-transfer evolution is Choice A: direct client-to-bucket transfer with API-controlled session lifecycle and user-level dedup.
