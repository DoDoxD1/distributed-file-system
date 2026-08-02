# V1 Deployment Plan

This document defines the deployment strategy for the first public API release of the Distributed File Storage System.

The goal for `v1` is to deploy the current MVP safely, with minimal operational complexity, while preserving the ability to harden and expand the system in later versions.

## Deployment decision

The selected `v1` deployment strategy is:

- Oracle VM as the single application host
- Docker Compose for packaging and runtime orchestration
- Caddy as the HTTPS reverse proxy
- Supabase Postgres as the external metadata database
- Host-mounted filesystem storage for chunk data
- HTTPS from day one so secure refresh-token cookies work correctly
- Free hostname instead of a purchased domain for the first release

This approach balances simplicity, reproducibility, and compatibility with the current authentication design.

## Why this strategy was selected

This project already has a working API, Flyway-managed schema, hybrid auth flow, and integration coverage. What it needs for `v1` is a deployment model that is stable and repeatable, but not over-engineered.

The chosen approach is intentionally not Kubernetes-based. A single VM with Docker Compose is enough for the current application shape and keeps the deployment process understandable.

The main reasons for this choice are:

- Docker provides consistent packaging across local and remote environments
- Docker Compose keeps the runtime simple without adding orchestration overhead
- Caddy simplifies HTTPS compared with a more manual reverse-proxy plus certificate flow
- Supabase keeps metadata persistence external to the VM
- A host-mounted storage directory preserves chunk data across container restarts and redeployments
- HTTPS is required for the secure refresh-cookie flow to behave like the intended production model

## Target architecture

### Public access path

1. Client sends HTTPS request to public hostname
2. Caddy receives the request and terminates TLS
3. Caddy forwards traffic to the Spring Boot API container
4. Spring Boot API reads and writes metadata through Supabase Postgres
5. Spring Boot API stores chunk data in the host-mounted storage directory

### Components

- Oracle VM
  - hosts Docker Engine and Docker Compose
  - stores chunk data on attached disk or persistent VM disk
- Caddy container
  - handles HTTPS
  - proxies requests to the API container
- API container
  - runs the Spring Boot application
- Supabase Postgres
  - stores metadata, auth records, and Flyway schema history
- Host-mounted storage directory
  - stores immutable chunk data written by storage nodes

## Public hostname strategy

Because no purchased domain is available yet, `v1` should use a free hostname.

### Preferred option: DuckDNS

Use DuckDNS if a stable demo URL is preferred.

Example:

```text
mydfsapi.duckdns.org
```

Benefits:

- stable hostname
- free to use
- works well for HTTPS
- cleaner public URL for demos and portfolio use

### Fastest option: sslip.io

Use `sslip.io` if the goal is to get public access working quickly without account setup.

Example:

```text
203.0.113.10.sslip.io
```

Benefits:

- no registration required
- fast to test
- still provides a hostname instead of a raw IP

Tradeoff:

- less polished than a named hostname
- better suited to initial rollout than long-term use

### Not recommended for the real v1 path: public IP only

Using only a raw public IP over HTTP is not recommended for the main `v1` deployment because the refresh token is sent as a secure cookie. That flow should be tested and operated under HTTPS.

## Deployment assumptions

This plan assumes the following:

- Oracle VM runs Ubuntu 22.04 LTS or equivalent
- Docker Engine and Docker Compose plugin are available
- Supabase database credentials are already provisioned
- The application remains a single deployable service
- Chunk storage remains local to the VM for `v1`
- Public ports `80` and `443` are reachable
- SSH is restricted to trusted IPs where possible

## Release scope for v1

The `v1` release includes:

- user registration
- login
- refresh token rotation using secure cookies
- authenticated file upload/download/delete/list/version flows
- manual worker endpoints
- Swagger/OpenAPI endpoint exposure

The `v1` release does not attempt to solve:

- multi-host chunk storage
- automatic worker scheduling
- full production observability
- rate limiting
- disaster recovery automation
- advanced rebalancing

Those items belong to later versions and are tracked separately in `FUTURE_ENHANCEMENTS.md`.

## Files expected for deployment

The deployment will eventually rely on a small set of deployment artifacts.

Expected files:

- `Dockerfile`
- `docker-compose.yml`
- `Caddyfile`
- deployment environment file stored only on the VM

The environment file should not be committed if it contains secrets.

## Required runtime configuration

The following configuration values must be defined for the deployed environment.

### Database configuration

- `SUPABASE_DB_JDBC_URL`
- `SUPABASE_DB_USERNAME`
- `SUPABASE_DB_PASSWORD`
- `SUPABASE_DB_SSLMODE=require`

### Auth configuration

- `DISTRIBUTED_FS_ACCESS_TOKEN_TTL_SECONDS=900`
- `DISTRIBUTED_FS_REFRESH_TOKEN_TTL_SECONDS=86400`
- `DISTRIBUTED_FS_REFRESH_COOKIE_SECURE=true`
- `DISTRIBUTED_FS_REFRESH_COOKIE_SAME_SITE=Strict`

### Storage configuration

- `DISTRIBUTED_FS_STORAGE_ROOT=/data/chunks`

### Application configuration

- `SERVER_PORT=8080`
- `SERVER_FORWARD_HEADERS_STRATEGY=framework`

### Optional sizing configuration

- `JAVA_OPTS=-Xms512m -Xmx1024m`

## Oracle VM provisioning plan

### 1. Create the VM

Recommended baseline:

- 1 to 2 OCPU
- 2 to 4 GB RAM
- enough disk for chunk storage growth
- Ubuntu 22.04 LTS

### 2. Configure networking

Open the following ports:

- `22` for SSH
- `80` for HTTP challenge and redirect handling
- `443` for HTTPS traffic

Restrict `22` as much as possible.

### 3. Prepare persistent storage

Create a dedicated host path for chunk storage.

Recommended path:

```text
/opt/distributed-fs/data/chunks
```

This must be a host directory, not a container-only path.

### 4. Choose and configure hostname

Preferred order:

1. DuckDNS hostname
2. `sslip.io` hostname

The chosen hostname must resolve to the Oracle VM public IP before HTTPS is finalized.

## VM software installation plan

Install the following on the VM:

- Docker Engine
- Docker Compose plugin
- basic utilities such as `curl`

The reverse proxy is intended to run as a container, so no host-level Nginx installation is required for this plan.

## Deployment directory layout on the VM

Suggested layout:

```text
/opt/distributed-fs/
  compose/
    docker-compose.yml
    Caddyfile
  app/
    source-or-release-bundle
  data/
    chunks/
  env/
    production.env
```

This keeps runtime configuration, chunk data, and compose files clearly separated.

## Deployment workflow

### Phase 1: Prepare the release artifact

For `v1`, the release should be deployed from a known commit on the main branch.

Recommended process:

1. Ensure compile and relevant tests pass locally
2. Tag or note the commit used for deployment
3. Build the Docker image from that exact source state

Suggested local verification before deployment:

- `mvn compile`
- focused tests for auth, gateway, and worker flows

### Phase 2: Prepare the VM

1. SSH into the Oracle VM
2. Create the deployment directory layout
3. Create the chunk-storage directory
4. Install Docker and Docker Compose
5. Confirm the selected hostname points to the VM IP

### Phase 3: Create the production environment file

Create an environment file on the VM that includes:

- Supabase JDBC URL and credentials
- storage root path used inside the container
- auth TTL and cookie settings
- server forwarding strategy
- JVM options

The environment file must not be committed to the repository.

### Phase 4: Provide Compose and reverse-proxy configuration

The deployment should include:

- one service for the API
- one service for Caddy
- one bind mount for chunk data
- one bind mount or file reference for environment variables

The API container should not expose itself directly to the internet. Only Caddy should listen publicly.

### Phase 5: Start the stack

1. Start the Compose stack
2. Confirm the API container becomes healthy
3. Confirm Caddy serves the public hostname
4. Confirm HTTPS is issued successfully

### Phase 6: Verify the application path end to end

After startup, validate:

- `/v3/api-docs`
- `/swagger-ui.html`
- `POST /api/v1/auth/register`
- `POST /api/v1/auth/login`
- `POST /api/v1/auth/refresh`
- one protected file API request using the bearer token

The refresh flow must be verified over HTTPS because secure cookies are part of the design.

## Database readiness and Flyway precautions

The metadata database must be in a consistent Flyway-managed state before the first deployment.

Important rule:

- do not partially delete application tables by hand and leave the schema half-populated

If a true reset is needed, remove all application-managed tables and the Flyway history table together so the application can recreate them cleanly on startup.

A half-reset schema will cause Flyway startup failure.

## Security requirements for v1

These are the minimum deployment-time security rules for the first version.

- Use HTTPS publicly
- Keep `DISTRIBUTED_FS_REFRESH_COOKIE_SECURE=true`
- Do not expose database credentials in repository files
- Limit SSH exposure
- Keep the API container behind the reverse proxy
- Do not expose the storage directory directly through the proxy

## Operational checks after deployment

After the stack is up, confirm the following:

- the container logs show successful application startup
- Flyway migrations validate or apply successfully
- the public OpenAPI endpoint responds over HTTPS
- registration and login work
- refresh token rotation works
- a file upload writes data into the mounted chunk directory
- the uploaded file can be downloaded again

## Suggested smoke-test checklist

### Authentication

- register a new user
- confirm access token is returned
- confirm refresh cookie is set
- call refresh and confirm a new access token is returned

### File flow

- upload a small file
- download the file
- fetch its manifest
- list files for that user
- verify version listing works
- delete the file and confirm delete behavior

### Worker flow

- confirm worker endpoints are reachable with bearer auth
- do not rely on them as scheduled maintenance yet

## Rollback plan

If the `v1` deployment fails after a new image rollout:

1. stop the new container image
2. redeploy the previously known-good image tag
3. keep the same environment file and mounted chunk directory
4. inspect logs before attempting another rollout

Because metadata is externalized in Supabase and chunk data is host-mounted, a container rollback is simpler than a full host rebuild.

## Known operational limits in v1

The deployment is still subject to these architectural limits:

- the app host remains a single operational dependency for chunk data
- worker flows are still manual
- rate limiting is not yet implemented
- metrics and alerts are still limited
- chunk storage capacity must be monitored manually

These are accepted constraints for `v1`, not oversights.

## Recommended first deployment sequence

The recommended order for the actual release is:

1. Choose hostname strategy
   - prefer DuckDNS
2. Provision Oracle VM and open ports
3. Install Docker and Docker Compose
4. Prepare `/opt/distributed-fs` directory layout
5. Create production environment file
6. Build and transfer the release bundle or source checkout
7. provide `docker-compose.yml`, `Dockerfile`, and `Caddyfile`
8. start the stack
9. verify HTTPS
10. run auth smoke tests
11. run file-flow smoke tests
12. announce the `v1` API endpoint

## Decision checkpoints before executing this plan

Before proceeding with implementation, confirm these final choices:

- hostname option
  - DuckDNS or `sslip.io`
- VM operating system
  - Ubuntu 22.04 recommended
- storage disk sizing
- whether the deployment will build on the VM or receive a prebuilt image
- whether Swagger UI should remain publicly exposed in `v1`

## Recommendation

Proceed with `v1` using:

- Oracle VM
- Docker Compose
- Caddy
- DuckDNS if a stable free hostname is wanted
- `sslip.io` if speed of setup matters more than hostname polish
- Supabase for metadata
- host-mounted local storage for chunks

This is the most practical release shape for the current system. It preserves the intended auth model, keeps operations understandable, and avoids premature platform complexity.
