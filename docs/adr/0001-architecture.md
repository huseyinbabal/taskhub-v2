# ADR 0001: Multi-module Maven build, package-by-feature layering

- **Status:** Accepted
- **Date:** 2026-11-24
- **Spec:** SPEC.md § Session 1 (`platform-skeleton`), § Project Structure

## Context

TaskHub is two deployable services: `taskhub-api` (REST) and `notification-service` (gRPC). They share a gRPC contract. The codebase is also course material built over five sessions, so a reader must be able to find "everything about tasks" quickly, and an AI agent must have unambiguous rules for where new code goes.

## Decision

### 1. One repository, one Maven reactor with three modules

| Module | Packaging | Purpose |
|---|---|---|
| `taskhub-proto` | jar | `notification.proto` and the generated stubs. It is the only code both apps share |
| `taskhub-api` | Boot jar | REST API: identity and task-api capabilities |
| `notification-service` | Boot jar | gRPC server for task events |

- The parent POM inherits `spring-boot-starter-parent` (4.1.x) and manages the plugins (Spotless, Surefire/Failsafe) and any versions outside the BOM.
- A single `./mvnw verify` builds and tests everything. That is the same gate locally and in CI.
- There is no shared "common" library between the apps. Coupling goes only through the versioned proto contract.

### 2. Package by feature, layered inside each feature

```
com.huseyin.taskhub
├── common/   errors (ProblemDetail), paging
├── config/   security, OpenAPI, CORS, gRPC client
├── auth/  user/  project/  task/  notification/
│   └── <Feature>Controller → <Feature>Service → <Feature>Repository, entity, dto/
```

- Controllers map HTTP to DTOs (records). Services own transactions and authorization. Repositories stay private to their feature.
- One feature uses another only through its service.
- Classes are package-private by default, so the compiler enforces most of these boundaries.

## Alternatives considered

- **Separate repositories per service:** independent release cycles, but the proto would have to be published as an artifact, and a course participant would have to juggle several repos. Rejected for V1.
- **Package by layer** (`controller/`, `service/`, `repository/`): it's familiar, but each change spreads across the tree and every class has to be `public`. Rejected.
- **Hexagonal / ports-and-adapters per feature:** more indirection than two small services need, and it would distract from the course's topics. We can revisit it if a feature grows complex.
- **Gradle:** works equally well. Maven matches the course material and Initializr's default.

## Consequences

- New code has an obvious home, and reviewers can reject any controller that touches a repository or returns an entity.
- Both apps build in one reactor, so a proto change breaks the build immediately if a consumer isn't updated.
- Releasing one service means building the whole reactor. That's acceptable at this size.
- If the services ever need independent repos, `taskhub-proto` is already isolated and can be extracted.
