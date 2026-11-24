# Spec: TaskHub — V1

Status: proposed

TaskHub is a team task-management service built in five sessions. Each session adds one capability on top of the last one and finishes with a working, tested increment. This document holds the full product scope. Each session has its own section, tagged with the module ids from the capability map.

---

## Capability Map

| Module id | Responsibility | Depends on | Session |
|---|---|---|---|
| `platform-skeleton` | Bootstrap, CLAUDE.md, package-by-feature layering, ProblemDetail errors, profiles | — | S1 |
| `identity` | Users, register/login, self-issued JWT, roles, ownership checks, CORS | platform-skeleton | S2 |
| `task-api` | Project & Task REST API, DTOs, OpenAPI, PostgreSQL + Flyway, N+1-safe queries | identity | S2 |
| `notification-service` | Separate Boot app, `notification.proto`, unary + server-streaming gRPC; task-api as a best-effort client | task-api | S3 |
| `test-strategy` | Test pyramid and coverage gate that every module follows | task-api, notification-service | S3 |
| `delivery` | Container images, GitHub Actions merge gate, GHCR publishing | test-strategy | S4 |
| `gitops` | Kubernetes manifests, kind, Flux, Kustomize, image automation, Istio mTLS | delivery | S4 |
| `observability` | Structured logs, metrics, traces, alerts, load test, runbook | gitops | S5 |

Build order: platform-skeleton → identity → task-api → notification-service → test-strategy → delivery → gitops → observability

Module ids are stable. Issues, PRs and plans refer to work by these ids.

---

## Objective

**What:** A backend for small teams. It lets them organise work into projects, create and assign tasks, follow task status, and get real-time notifications when a task assigned to them, or in a project they belong to, changes.

**Who:**
- *Team member (`MEMBER`)*: creates projects, works on tasks in projects they belong to, subscribes to notifications.
- *Administrator (`ADMIN`)*: manages every project and user.
- *Course participants (secondary audience)*: the codebase is the teaching material, so clarity and conventional Spring idioms come before cleverness.

**Success:** By the end of session 5, one `git push` to `main` builds, tests and publishes images. Flux deploys them to a kind cluster. A user can then register, log in, manage projects and tasks over REST, and watch a gRPC stream of task events. Every request can be traced across both services in Grafana.

---

## Tech Stack

| Concern | Choice |
|---|---|
| Language / runtime | Java 25 (Temurin) |
| Framework | Spring Boot 4.1.x, bootstrapped from start.spring.io |
| Build | Maven (wrapper committed), multi-module |
| Web | Spring MVC, Bean Validation, ProblemDetail (RFC 9457) |
| API docs | springdoc-openapi (Swagger UI) |
| Security | Spring Security + OAuth2 Resource Server, Nimbus JWT (HS256, self-issued) |
| Persistence | PostgreSQL 17, Spring Data JPA (Hibernate), Flyway |
| Inter-service | Spring gRPC, protobuf 3 |
| Service mesh | Istio (sidecar mode): STRICT mTLS + AuthorizationPolicy for service-to-service identity |
| Testing | JUnit 5, AssertJ, Mockito, Spring Boot test slices, Testcontainers, JaCoCo |
| Containers | Docker multi-stage, layered jars, non-root, distroless/JRE base |
| CI/CD | GitHub Actions, GHCR |
| Platform | Kubernetes (kind locally), Flux v2, Kustomize |
| Observability | Micrometer → Prometheus, Grafana, Loki + Alloy, OpenTelemetry → Tempo, k6 |

Exact dependency versions come from the Spring Boot 4.1 BOM. Any version outside the BOM is pinned in the parent `pom.xml` `<properties>`.

---

## Commands

```bash
# Build & test everything (unit + slice + integration; needs Docker for Testcontainers)
./mvnw clean verify

# Unit + slice tests only (fast loop, no Docker)
./mvnw test

# One module
./mvnw -pl taskhub-api -am verify

# Run locally (Boot Docker Compose support starts Postgres from compose.yaml)
./mvnw -pl taskhub-api spring-boot:run -Dspring-boot.run.profiles=dev
./mvnw -pl notification-service spring-boot:run -Dspring-boot.run.profiles=dev

# Regenerate gRPC stubs
./mvnw -pl taskhub-proto generate-sources

# Coverage report → <module>/target/site/jacoco/index.html
./mvnw verify -Pcoverage

# Container images
docker build -f taskhub-api/Dockerfile -t taskhub-api:local .
docker build -f notification-service/Dockerfile -t notification-service:local .

# Local cluster + GitOps (S4)
kind create cluster --name taskhub --config deploy/kind/cluster.yaml
flux bootstrap github --owner=<owner> --repository=<repo> --path=deploy/clusters/local --personal
flux get kustomizations --watch

# Load test (S5)
k6 run load/tasks-smoke.js
```

---

## Project Structure

```
taskhub-v2/
├── pom.xml                         → parent POM: modules, Java 25, plugin management
├── CLAUDE.md                       → agent rules (architecture, workflow, boundaries)
├── SPEC.md                         → this file
├── compose.yaml                    → local Postgres (+ observability stack in S5)
├── taskhub-proto/                  → notification.proto + generated stubs (jar)
│   └── src/main/proto/
├── taskhub-api/                    → REST API (identity + task-api modules)
│   ├── Dockerfile
│   └── src/
│       ├── main/java/com/huseyin/taskhub/
│       │   ├── TaskhubApplication.java
│       │   ├── common/             → ProblemDetail handler, base exceptions, paging DTOs
│       │   ├── config/             → security, OpenAPI, CORS, gRPC client
│       │   ├── auth/               → register/login, JWT issuing
│       │   ├── user/               → User entity, repository, service
│       │   ├── project/            → controller, service, repository, entity, dto/
│       │   ├── task/               → controller, service, repository, entity, dto/
│       │   └── notification/       → gRPC client adapter (best effort)
│       ├── main/resources/
│       │   ├── application.yml, application-dev.yml, application-prod.yml
│       │   └── db/migration/       → V1__*.sql, V2__*.sql …
│       └── test/java/…             → mirrors main; *Test (unit/slice), *IT (integration)
├── notification-service/           → gRPC server
│   ├── Dockerfile
│   └── src/main/java/com/huseyin/taskhub/notification/
│       ├── grpc/                   → service impl, interceptors
│       └── subscription/           → in-memory subscriber registry / fan-out
├── deploy/                         → GitOps (S4)
│   ├── kind/
│   ├── base/                       → Deployments, Services, ConfigMaps, probes
│   ├── overlays/local/
│   ├── infrastructure/             → Istio, Postgres, observability stack (S5)
│   └── clusters/local/             → Flux Kustomizations + image automation
├── observability/                  → Grafana dashboards, alert rules, Alloy config (S5)
├── load/                           → k6 scripts
├── docs/                           → ADRs (docs/adr/NNNN-*.md), runbook.md
└── .github/workflows/              → ci.yml, publish.yml
```

**Layering inside each feature package:** `Controller → Service → Repository`. Controllers take and return DTOs (Java records) only, never entities. Services own transactions and authorization decisions. Repositories never leak out of their feature package. Features call each other only through services.

---

## Code Style

```java
// task/TaskController.java
@RestController
@RequestMapping("/api/v1/projects/{projectId}/tasks")
@RequiredArgsConstructor
class TaskController {

    private final TaskService taskService;

    @PostMapping
    ResponseEntity<TaskResponse> create(@PathVariable Long projectId,
                                        @Valid @RequestBody CreateTaskRequest request,
                                        @AuthenticationPrincipal Jwt jwt) {
        TaskResponse created = taskService.create(projectId, request, CurrentUser.from(jwt));
        URI location = URI.create("/api/v1/projects/%d/tasks/%d".formatted(projectId, created.id()));
        return ResponseEntity.created(location).body(created);
    }
}

// task/dto/CreateTaskRequest.java
public record CreateTaskRequest(
        @NotBlank @Size(max = 200) String title,
        @Size(max = 5000) String description,
        @NotNull TaskPriority priority,
        Long assigneeId,
        @FutureOrPresent LocalDate dueDate) {}
```

Conventions:
- Package by feature. Classes are package-private by default and `public` only when another package needs them.
- Constructor injection only, no field `@Autowired`. Lombok is limited to `@RequiredArgsConstructor`, `@Getter` and `@Slf4j`. No `@Data` on entities.
- DTOs are records named `*Request` / `*Response`. Mapping is explicit (static `from(...)` or a small mapper class), with no reflection mappers.
- Errors: throw domain exceptions (`NotFoundException`, `ConflictException`, `ForbiddenException`). One `@RestControllerAdvice` turns them into `ProblemDetail` with a stable `type` URI, plus an `errors` array for validation failures.
- Formatting: 4-space indent, 120 columns, no wildcard imports. Every module uses the same formatter (Spotless with palantir-java-format) and `./mvnw verify` checks it.
- Names: REST paths are plural and kebab-case. JSON fields are camelCase. DB tables and columns are snake_case. Flyway files are `V<n>__<verb>_<noun>.sql`.
- Configuration goes through typed `@ConfigurationProperties` records, never `@Value` scattered through the code.

---

## Session 1 — Foundations · `platform-skeleton`

**Goal:** an empty but correctly shaped system that every later session builds on.

Requirements:
1. Generate a Maven multi-module project (`taskhub-proto`, `taskhub-api`, `notification-service`) from Spring Initializr: Java 25, Boot 4.1. The parent POM manages versions and plugins.
2. `CLAUDE.md` records the architecture (package by feature, layering), the workflow (spec → issue → branch → PR, with tests before merge) and the boundaries (copied from this spec).
3. A global exception handler returns `application/problem+json` for every error. That includes 404 for unknown routes and 400 for malformed JSON and validation errors.
4. Profiles: `dev` (local Postgres through compose, verbose logs), `test`, and `prod` (all secrets come from environment variables and fail fast when missing).
5. Actuator exposes `health`, `info` and `prometheus`, with `liveness`/`readiness` health groups enabled.
6. A `GET /api/v1/ping` placeholder proves the wiring works. It is removed in S2.

Acceptance criteria:
- `./mvnw clean verify` passes on a fresh clone.
- `GET /unknown` returns 404 with a ProblemDetail body (`type`, `title`, `status`, `detail`, `instance`).
- `GET /actuator/health/readiness` returns `UP`.
- Starting with `prod` and no `JWT_SECRET` set fails at startup with a clear message.

---

## Session 2 — REST API, Security & Data · `identity`, `task-api`

### Domain model

| Entity | Fields |
|---|---|
| `User` | id, username (unique), email (unique), passwordHash (BCrypt), role (`ADMIN`/`MEMBER`), createdAt |
| `Project` | id, name, description, owner → User, members ↔ User (join table `project_members`), createdAt, updatedAt |
| `Task` | id, project → Project, title, description, status (`TODO`/`IN_PROGRESS`/`DONE`), priority (`LOW`/`MEDIUM`/`HIGH`), assignee → User (nullable), dueDate, version (optimistic lock), createdAt, updatedAt |

Flyway owns the schema. `spring.jpa.hibernate.ddl-auto=validate` everywhere. Every FK and every filtered column has an index.

### REST contract (base `/api/v1`)

| Method & path | Who | Notes |
|---|---|---|
| `POST /auth/register` | anyone | Creates a `MEMBER`. 409 when username or email is taken |
| `POST /auth/login` | anyone | Returns `{accessToken, tokenType, expiresIn}`. 401 on bad credentials, with no hint about which part was wrong |
| `GET /users/me` | authenticated | Current profile |
| `GET /projects` | authenticated | Paged. Projects the caller owns or belongs to (all projects for `ADMIN`) |
| `POST /projects` | authenticated | Caller becomes the owner and a member |
| `GET /projects/{id}` | member/owner/admin | |
| `PUT /projects/{id}` | owner/admin | |
| `DELETE /projects/{id}` | owner/admin | Deletes its tasks too |
| `POST /projects/{id}/members` `{userId}` | owner/admin | Idempotent |
| `DELETE /projects/{id}/members/{userId}` | owner/admin | Cannot remove the owner. Removing a member unassigns their tasks |
| `GET /projects/{id}/tasks` | member | Paged. Filters: `status`, `assigneeId`, `priority`. Sort: `dueDate`, `createdAt`, `priority` |
| `POST /projects/{id}/tasks` | member | The assignee must be a project member (else 422) |
| `GET /projects/{id}/tasks/{taskId}` | member | |
| `PUT /projects/{id}/tasks/{taskId}` | member | Needs the `If-Match` / `version` field. 409 on a stale version |
| `PATCH /projects/{id}/tasks/{taskId}/status` `{status}` | member | |
| `DELETE /projects/{id}/tasks/{taskId}` | owner/admin or task creator | |
| `GET /tasks?assignee=me` | authenticated | Paged list of tasks assigned to the caller, across projects |

Paging: `page` (0-based) and `size` (default 20, max 100). The response is `{content, page, size, totalElements, totalPages}`.

### Security rules
- Stateless sessions with CSRF disabled (no cookies). Every route except `/auth/**`, `/actuator/health/**`, `/v3/api-docs/**` and `/swagger-ui/**` needs a token.
- JWT: HS256 with a secret of at least 256 bits from `JWT_SECRET`. Claims are `sub` (user id), `username`, `roles`, `iat`, `exp`. The token lasts 1 h. There are no refresh tokens in V1.
- Two levels of authorization: **roles** (URL rules plus `@PreAuthorize` for `ADMIN`) and **ownership** (checked in the service layer).
- An authenticated caller who lacks access to an existing project or task gets **403**. That covers a non-member reading it and a member attempting an owner-only action. **404** is only for resources that don't exist. (Decision: 403 is simpler to teach and debug. The accepted trade-off is that project ids can be enumerated.)
- CORS: an explicit origin allow-list from configuration. Never `*` together with credentials.
- Passwords, tokens and secrets never appear in logs or responses.

### Persistence requirements
- One migration per schema change. Applied migrations are never edited.
- List endpoints use fetch joins, `@EntityGraph` or DTO projections. Loading a page of 50 tasks with assignee and project names runs a **fixed number of SQL statements, at most 3**, and a test proves it (S3).
- Indexes come from real query plans (`EXPLAIN ANALYZE`) for the task list filters.

Acceptance criteria:
- Swagger UI lists every endpoint, and the Bearer auth scheme lets you call protected endpoints.
- A user who isn't a member gets 403 for that project's tasks, and anyone gets 404 for a project id that doesn't exist. A member who isn't the owner gets 403 when deleting the project.
- Two concurrent `PUT`s with the same version: one succeeds and the other returns 409 ProblemDetail.
- An expired or tampered token returns 401 ProblemDetail.
- Starting against an empty database applies every Flyway migration, and Hibernate validation passes.

---

## Session 3 — gRPC & Test Strategy · `notification-service`, `test-strategy`

### `notification-service`

Contract `taskhub-proto/src/main/proto/notification/v1/notification.proto`, package `taskhub.notification.v1`:

- `rpc PublishTaskEvent(TaskEvent) returns (PublishAck)`: unary. task-api calls it after a successful task create, update, assign, status change or delete.
- `rpc SubscribeTaskEvents(SubscribeRequest) returns (stream TaskEvent)`: server streaming. The subscriber filters by `project_ids` and/or `assignee_id`. The stream stays open until the client cancels or the server shuts down.
- `TaskEvent`: `event_id` (UUID), `type` enum (`*_UNSPECIFIED = 0` first), `task_id`, `project_id`, `title`, `status`, `assignee_id`, `actor`, `occurred_at` (Timestamp).

Contract rules:
- Fields are only added. Tags are never renumbered or reused. Removed fields are marked `reserved`. Enum values are only appended.
- Packages are versioned (`v1`). A breaking change means a new `v2` package.

Server behaviour:
- An in-memory subscriber registry fans out each published event to the matching streams. There is no database in V1.
- A slow subscriber never blocks the publisher. Each stream gets a bounded buffer (e.g. 256 events). When it overflows, that stream is cancelled with `RESOURCE_EXHAUSTED`.
- On graceful shutdown, open streams complete with `UNAVAILABLE` so clients can reconnect.
- Interceptors: (a) logging plus correlation-id propagation, (b) JWT authentication for **subscribers only**. `SubscribeTaskEvents` needs a user token in the `authorization` metadata, signed with the same key as task-api, and the stream only sees projects the user belongs to.
- **Service-to-service identity (task-api → `PublishTaskEvent`) comes from Istio mTLS, not from the application.** In-cluster (S4), a `PeerAuthentication` in STRICT mode encrypts and authenticates all traffic in the namespace. An `AuthorizationPolicy` allows `PublishTaskEvent` only from the `taskhub-api` ServiceAccount principal (`cluster.local/ns/taskhub/sa/taskhub-api`). The application does not check credentials for publish. Outside the cluster (dev, tests) the channel is plaintext and there is no mesh. That is a known, documented gap, and `PublishTaskEvent` is only reachable when it is exposed on purpose.

Client behaviour (task-api):
- **Best effort.** Publishing happens after the DB transaction commits (`@TransactionalEventListener(AFTER_COMMIT)`). A gRPC failure never fails or rolls back the REST request. It is logged and counted.
- Every call has a deadline (default 500 ms, configurable).
- If notification-service is down, every REST endpoint keeps working.

### `test-strategy`

| Level | Tooling | What it covers | Naming / phase |
|---|---|---|---|
| Unit | JUnit 5, Mockito, AssertJ | Service rules (ownership, status transitions, validation edge cases), JWT service, fan-out registry | `*Test`, `mvn test` |
| Slice | `@WebMvcTest` + security, `@DataJpaTest` + Testcontainers | Controller contract (status codes, ProblemDetail shape, auth rules), repository queries | `*Test` |
| Integration | `@SpringBootTest` + Testcontainers Postgres (`@ServiceConnection`) | Full flow over HTTP. Flyway on a real DB. The N+1 regression test (statement count via Hibernate statistics or a datasource proxy) | `*IT`, Failsafe in `verify` |
| gRPC | In-process server/channel | Unary publish, streaming subscribe with filtering, slow-subscriber eviction, auth interceptor rejections | `*Test` / `*IT` |
| E2E | Spring Boot test that starts both apps | Register → login → create task → event arrives on stream. **Few tests, happy path only** | `*E2EIT` |

Rules:
- Every bug fix starts with a failing test.
- No `Thread.sleep` in tests. Use Awaitility for asynchronous assertions.
- Tests are independent of each other and of execution order. Each integration test cleans up its own data or runs in a rolled-back transaction.
- Coverage gate (JaCoCo, enforced in `verify`): **≥ 80 % line coverage on `service` packages** and ≥ 70 % overall per module. Generated gRPC code and config classes are excluded.

Acceptance criteria:
- Creating a task over REST delivers a matching `TaskEvent` to a subscriber filtered on that project within 1 s.
- Stopping notification-service leaves `POST /tasks` returning 201, and `taskhub_notification_publish_failures_total` goes up.
- The N+1 test fails if someone removes the fetch strategy from the task list query.
- `./mvnw verify` runs every level and enforces the coverage gate.

---

## Session 4 — Delivery & GitOps · `delivery`, `gitops`

### `delivery`
- One Dockerfile per service: multi-stage (a Maven build stage and a slim JRE runtime stage). Uses Spring Boot layered jar extraction so dependency layers get cached. Runs as a non-root user (UID 10001). Sets no secrets in the image and declares no `latest` base tag.
- JVM settings for containers: `-XX:MaxRAMPercentage=75`, container support (the default), and graceful shutdown enabled in Boot.
- `.github/workflows/ci.yml` runs on every PR: checkout → JDK 25 (Temurin, Maven cache) → `./mvnw -B verify` → upload test and coverage reports → build images (no push) → container scan (Trivy, fails on HIGH/CRITICAL with a known fix).
- `publish.yml` runs only on push to `main`: builds and pushes both images to GHCR, tagged with `sha-<short>` and a sortable `main-<run_number>` (or semver on tags). Images get SBOM and provenance attestations.
- Branch protection: CI must be green and one approving review is required before merge.

### `gitops`
- `deploy/base`: a Deployment and a Service per app, a ConfigMap for non-secret config, and Secrets referenced by name (never committed in plain text). Resource requests and limits are set. `securityContext` sets `runAsNonRoot`, `readOnlyRootFilesystem` and drops all capabilities.
- Probes: liveness `/actuator/health/liveness`, readiness `/actuator/health/readiness`, and a startup probe so JVM startup doesn't trip liveness.
- In-cluster PostgreSQL for `local` (a single-replica StatefulSet or chart under `deploy/infrastructure`). The DB URL and credentials come in through env vars.
- Kustomize: `base` + a single `local` overlay (kind, 1 replica). Adding another environment later means adding an overlay, not changing base.
- **Istio** (installed by Flux from `deploy/infrastructure/istio`, before the apps): the `taskhub` namespace has sidecar injection on. `PeerAuthentication` is STRICT for the namespace. `AuthorizationPolicy` rules: (a) `PublishTaskEvent` on notification-service only from the `taskhub-api` ServiceAccount, (b) `SubscribeTaskEvents` from the ingress gateway, (c) deny everything else. Each app has its own ServiceAccount. Kubelet probes keep working through Istio's probe rewrite (the default).
- Flux: `GitRepository` + `Kustomization`s ordered by dependency (infrastructure → apps). Image automation (`ImageRepository`, `ImagePolicy`, `ImageUpdateAutomation`) commits new image tags back to the repo.
- Rollouts: `RollingUpdate` with `maxUnavailable: 0`. Rolling back means reverting the Git commit, never running `kubectl` by hand.

Acceptance criteria:
- A PR with a failing test cannot be merged.
- Merging to `main` publishes images to GHCR. Within about 5 min, Flux rolls the new tag out to the kind cluster without any manual `kubectl apply`.
- `docker run` of each image shows a non-root UID, and the image is under 300 MB.
- Reverting the image-tag commit rolls the deployment back.
- `istioctl x describe pod` (or Kiali/`istioctl proxy-config`) shows mTLS STRICT between the two services. A `grpcurl` call to `PublishTaskEvent` from a pod running under a different ServiceAccount is rejected with `PERMISSION_DENIED`, while task-api's publishes still go through.

---

## Session 5 — Observability & Production Readiness · `observability`

Requirements:
1. **Structured logging:** JSON logs (Boot structured logging, ECS or logstash format) in every profile except `dev`. Each line carries `traceId`, `spanId`, the service name and the user id when known.
2. **Metrics:** Micrometer → `/actuator/prometheus`. HTTP server metrics with percentile histograms. Custom metrics are `taskhub_tasks_created_total`, `taskhub_notification_publish_failures_total`, `taskhub_notification_active_streams` (gauge) and gRPC server/client metrics.
3. **RED dashboard** in Grafana (provisioned from `observability/dashboards/`): rate, error ratio (`or vector(0)` so no data reads as 0) and p99 latency per endpoint, for both services.
4. **Logs at scale:** Alloy ships container logs to Loki, plus a "logs for this trace" link from Tempo to Loki.
5. **Tracing:** OpenTelemetry via Micrometer Tracing and OTLP to Tempo. The context propagates REST → gRPC, so one trace covers both services. Sampling is set in config (100 % in local). App spans stay the source of truth, and Istio's own proxy spans are optional.
6. **Alerting:** alerts fire on symptoms: 5xx ratio above 2 % for 5 min, p99 above 500 ms for 10 min, notification publish failures above 0.5/s, pod restarts. Each alert links to a runbook entry.
7. **Every wait has a limit:** timeouts for the HTTP server, the JDBC pool (Hikari `connectionTimeout`, statement timeout), gRPC deadlines, and graceful shutdown (`spring.lifecycle.timeout-per-shutdown-phase`).
8. **Load test:** a k6 script (`load/`) with thresholds `p(95) < 300 ms` and `http_req_failed < 1 %` at 50 VUs against the kind deployment.
9. **Runbook:** `docs/runbook.md` has one entry per alert: what it means, how to confirm it in Grafana, first actions, and how to escalate.

Acceptance criteria:
- One `POST /tasks` shows up in Tempo as a single trace spanning taskhub-api and notification-service, and its log lines can be found in Loki by `traceId`.
- Stopping notification-service fires the publish-failure alert within its `for:` window. The REST API stays green.
- The k6 run meets its thresholds on the reference setup (documented in `load/README.md`).
- Every alert rule has a matching runbook entry.

---

## Testing Strategy (summary)

See S3 `test-strategy` for the full pyramid. In short: unit and slice tests make up the bulk. Integration tests always run against real PostgreSQL through Testcontainers, never H2. gRPC is tested in-process. A few E2E happy paths cover the rest. `./mvnw verify` is the single local and CI gate and enforces the JaCoCo thresholds.

---

## Boundaries

**Always:**
- Work from a GitHub issue on its own branch, and land changes through a PR that references the issue and the SPEC section or module id.
- Run `./mvnw verify` before you commit or open a PR.
- Write a failing test first for bug fixes and new behaviour.
- Put schema changes in a new Flyway migration.
- Validate every input at the API boundary and return ProblemDetail for every error.
- Keep the protobuf contract backward compatible.
- Record significant decisions as an ADR in `docs/adr/`.

**Ask first:**
- Adding a dependency that isn't managed by the Spring Boot BOM.
- Changing the REST or gRPC contract in a way that changes existing behaviour.
- Changing CI workflows, branch protection, Flux/cluster bootstrap config, or Istio `PeerAuthentication`/`AuthorizationPolicy` resources.
- Changing the security configuration (auth rules, token settings, CORS).
- Lowering coverage thresholds or skipping or disabling a test.

**Never:**
- Commit secrets, tokens, `.env` files or plain-text Kubernetes Secrets.
- Edit a Flyway migration that has already been applied, or set `ddl-auto` to anything other than `validate`.
- Return JPA entities from controllers.
- Merge a PR yourself, force-push `main`, or bypass the CI gate.
- Remove or weaken a failing test to make the build pass.
- Copy code from the reference project (`../taskhub`) without reviewing it against this spec.

---

## Success Criteria (V1 done)

1. Every session's acceptance criteria pass, and `./mvnw clean verify` is green on CI.
2. A fresh clone with Docker, kind and the Flux CLI can reach a running, observable system by following `README.md` alone.
3. Every REST and gRPC behaviour listed in this spec is covered by at least one automated test at the level the test strategy assigns.
4. No HIGH/CRITICAL vulnerabilities with a known fix in either image.
5. The RED dashboard, the cross-service traces and every alert with its runbook entry can be shown live.

---

## Out of Scope (Deferred to V2+)

- Web or mobile frontend: V1 is API-only.
- Refresh tokens, password reset, email verification, and external SSO/OIDC: adds auth surface that isn't needed to teach JWT basics.
- Email, push or webhook delivery of notifications: V1 only streams over gRPC.
- Persisted notification history and replay after a reconnect: needs a store or broker (Kafka) and doesn't fit the session time.
- Comments, attachments, labels, subtasks: domain breadth with no new technique to teach.
- Multi-tenancy (organisations): changes every query, and is a V2 design topic.
- Horizontal scaling of notification-service (shared fan-out across replicas): needs a broker. V1 runs one replica.
- Production cloud cluster and Helm charts for our own apps: kind plus Kustomize is enough for the course. Istio itself may be installed from its upstream charts.
- Extra environments (staging/prod overlays): `local` alone is enough for V1.
- Application-level mTLS (Spring gRPC TLS with our own certificates): Istio covers it in-cluster.
- Istio ambient mode, egress gateway, mesh-wide traffic policies (retries, circuit breaking): not needed for two services.
- Caching (e.g. Hibernate L2/Hazelcast): an optimisation for later, once metrics show a need.

---

## Decisions (resolved 2026-11-24)

1. **Java package root:** `com.huseyin.taskhub` (Maven groupId `com.huseyin`).
2. **Access denied vs. not found:** 403 for existing resources the caller can't access, and 404 only when the resource doesn't exist.
3. **Service-to-service auth:** Istio mTLS (STRICT) plus an `AuthorizationPolicy` keyed on the ServiceAccount identity. There is no application-level service token. Record this as an ADR in S4.
4. **Coverage thresholds:** ≥ 80 % line coverage on `service` packages and ≥ 70 % overall per module.
5. **Environments:** a single `local` overlay (kind).

## Open Questions

None.
