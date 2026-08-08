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
- Worker endpoints are authenticated, but they are not yet restricted to administrators
- Oracle Object Storage cutovers still rely on an operator-triggered migration step
- Rate limiting and admission control are not implemented
- Operational observability is still limited
- Recovery and deployment runbooks need to be formalized
- Secure refresh-cookie flow assumes HTTPS in real deployments

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
- Add an admin-only authorization layer for `WorkerController` endpoints so maintenance APIs are not callable by every authenticated user
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

- Publish a maintained Postman collection for auth and file flows
- Document example client flows for register, login, refresh, upload, and download
- Improve error response consistency and troubleshooting guidance
- Add deployment examples for local, VM, and managed-database setups
- Expand developer docs for common operational tasks

## Highest-impact next enhancements

If only a few items are implemented next, these would likely provide the biggest return:

1. Add admin-only authorization for worker endpoints
2. Add scheduled background workers and cron-style execution control
3. Add deployment and recovery runbooks
4. Add rate limiting to auth and upload APIs
5. Add metrics, alerts, and structured operational logs

## Longer-term architecture evolution

For a more production-oriented distributed storage system, the longer horizon could include:

- Automated cutover tooling for legacy local chunks after a backend switch
- Multi-host or externalized chunk storage instead of single-host local disk
- Better health-aware or load-aware placement decisions
- Metadata snapshots and stronger disaster recovery guarantees
- Rebalancing logic for uneven storage distribution
- Quotas and tenant-level admission control
- Cross-region replication or disaster recovery strategy
- Erasure coding for more efficient storage durability at scale

## Recommendation

The current system is strong enough to deploy as an MVP and showcase end-to-end engineering ability. The next milestone should be operational hardening, not major feature expansion. Reliability, security, and observability improvements will increase real deployment confidence more than adding new user-facing features first.
