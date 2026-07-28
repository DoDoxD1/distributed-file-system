# AGENTS.md

Repository-wide instructions for humans and coding agents.
Scope: this file applies to the entire repository unless a deeper `AGENTS.md` overrides it.

## Core Engineering Principles

1. Fix root causes, not symptoms.
2. Keep changes minimal, focused, and reversible.
3. Prefer clarity over cleverness.
4. Follow DRY: if logic appears more than once, extract it.
5. Keep functions small and single-purpose.
6. Separate business logic from I/O (UI, network, filesystem).
7. Avoid tight coupling; design for replaceable components.
8. Do not change unrelated code while implementing a request.
9. Preserve backward compatibility unless explicitly asked otherwise.
10. Remove dead code and stale TODOs when directly relevant.
11. Always enforce separation of concerns across modules and layers.
12. Avoid oversized files; split by cohesive responsibility unless a larger file is clearly justified.

## Reuse and Structure Rules

- Do not copy-paste implementation blocks across modules.
- If code is used in multiple places, create shared utilities/helpers and reuse them.
- Prefer composition over inheritance for shared behavior.
- Centralize constants, enums, and mappings in dedicated modules.
- Keep side effects at boundaries; keep core functions deterministic when possible.
- Prefer dependency injection for clients/services to improve testability.

## Configuration Governance

- Do not introduce hardcoded operational values in business or orchestration code paths.
- Define module-level defaults and headers in centralized config modules/files.
- Route runtime-overridable values through centralized config (`application.yml`,
  `@ConfigurationProperties`) with environment-variable overrides.
- Keep a current list of module config files in `DEVELOPER_GUIDE.md` with short descriptions.

## Readability and Maintainability

- Use descriptive names (`extract_requested_mr_type`, not `do_it`).
- Keep files and functions cohesive; split large units into focused modules.
- Add concise JavaDoc for public packages, classes, and methods.
- Use comments sparingly; explain *why*, not obvious *what*.
- Avoid magic numbers/strings; promote to named constants.
- Prefer explicit behavior over hidden implicit behavior.

## Error Handling and Reliability

- Validate inputs at boundaries.
- Raise specific exceptions; avoid broad `catch (Exception)` unless re-throwing with context.
- Never silently swallow errors.
- Include actionable context in errors and logs.
- Implement retries/timeouts only where failures are expected and safe.
- Keep operations idempotent where possible (especially automation workflows).

## Logging and Observability

- Use structured logging (`SLF4J`), not `System.out.println` for runtime behavior.
- Log key lifecycle events (start, decision, finish, failure).
- Do not log secrets or sensitive patient data.
- Prefer concise logs with traceable identifiers (e.g., `msg_id`, `document_id`).

## Security and Data Privacy

- Never hardcode credentials, tokens, or secrets.
- Read secrets from environment variables or secure stores.
- Mask or avoid PHI/PII in logs, test fixtures, and exported artifacts.
- Use least-privilege access and minimal data retention.
- Treat downloaded records as sensitive; clean temporary files promptly.

## Java-Specific Best Practices

### Language and Style

- Target Java 21+ features already used in this repo.
- Follow the project formatter/linting setup (e.g., Spotless, Checkstyle, PMD).
- Keep line length near configured limit (100).
- Prefer explicit types in public APIs; use `var` only when types are obvious.
- Prefer `Path`/`Paths` over raw string path manipulation.
- Use records/POJOs for structured data.
- Avoid nullable contracts where possible; use `Optional` primarily for return types.
- Use `Enum` for fixed value sets when appropriate.

### Imports and Modules

- Group and order imports consistently; avoid wildcard imports.
- Keep packages focused; avoid circular dependencies.
- Keep module boundaries explicit and cohesive.

### Functions and Classes

- Keep methods short and purpose-driven.
- Prefer pure methods for transformations/classification logic.
- Use classes/interfaces for cohesive stateful behavior (clients, pipelines).
- Keep class interfaces small and explicit.

### Async and Resource Management

- Use `CompletableFuture`/executor-based async flows consistently where needed.
- Use `try-with-resources` for files, streams, and other closeable resources.
- Ensure cleanup in `finally` blocks or lifecycle shutdown hooks.

### Data Parsing and Validation

- Normalize external text/inputs before rule matching.
- Keep parsing logic separate from transport/automation code.
- Validate configuration early and fail fast with clear errors.

### Testing

- Use `JUnit 5` for unit tests.
- Add or update tests for every behavior change or bug fix.
- Prefer small, deterministic tests with clear Arrange-Act-Assert structure.
- Mock external dependencies (network/filesystem/services) in unit tests (e.g., Mockito).
- Add regression tests when fixing production bugs.

## Review Checklist (Before Finalizing Changes)

- Is there duplicated logic that should be extracted?
- Are names and function boundaries clear?
- Are error paths handled explicitly?
- Are logs useful and free from sensitive data?
- Are tests updated and passing for changed behavior?
- Is the change scoped only to what was requested?
- If architecture/flow changed, was `DEVELOPER_GUIDE.md` updated?
