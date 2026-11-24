# Implementation Plan: TaskHub V1

Source spec: [`SPEC.md`](../SPEC.md) (approved 2026-11-24)

Task list target: **GitHub Issues** in the TaskHub v2 repository, one issue per task and one PR per issue (`Closes #N`). The detailed tasks below are the source for those issues. Once the issues exist, this section becomes an ordered index of issue links.

## Overview

We build TaskHub in five session phases that follow the capability map: platform-skeleton → identity → task-api → notification-service → test-strategy → delivery → gitops → observability. Every task is a vertical slice that leaves `./mvnw verify` green. Each phase ends with a checkpoint that matches the acceptance criteria of its SPEC session.

## Architecture Decisions

- **Repository:** `taskhub-v2` becomes its own Git repository and GitHub repo, the same way `../taskhub` is today. GitHub Actions only reads `.github/workflows` at the repo root, and Flux needs a stable repo path, so neither works well inside the course monorepo. *(Needs confirmation. See Open Questions.)*
- **Test infrastructure from day one:** Surefire runs `*Test`, and Failsafe plus Testcontainers run `*IT`. Both are wired in Task 1, so S2 work can follow TDD against real Postgres. S3 then adds the coverage gate, the N+1 regression test and the E2E tests on top. It doesn't have to invent the test setup.
- **Contract first for gRPC:** the proto (Task 15) lands before the server or client, so Tasks 16–19 build against a frozen contract.
- **Ownership checks live in services**, through one `ProjectAccess` component (`requireMember`, `requireOwnerOrAdmin`). Controllers and URL rules handle only authentication and roles.
- **Event publishing:** task services publish a Spring `TaskChangedEvent`. A `@TransactionalEventListener(AFTER_COMMIT)` adapter in `notification/` turns it into gRPC calls. The task code never sees gRPC.
- **Riskiest work goes first in each phase:** JWT/security (T6), gRPC on Boot 4.1 (T15) and Istio (T29) each start their phase so problems show up early.

## Dependency Graph

```
T1 skeleton ─┬─ T2 CLAUDE.md
             ├─ T3 ProblemDetail ─┐
             └─ T4 profiles/actuator
                                  │
            T5 users/register ── T6 JWT/login ── T7 CORS+401/403
                                  │
                   T8 projects ── T9 membership/ownership
                                  │
          T10 tasks create ── T11 list/N+1 ── T12 update/lock ── T13 my tasks ── T14 OpenAPI
                                  │
   T15 proto ── T16 server+stream ── T17 backpressure ── T18 interceptors
                                  └──────── T19 task-api client (needs T12, T15)
                                  │
                T20 JaCoCo gate · T21 N+1 IT · T22 E2E
                                  │
   T23 Dockerfiles ── T24 CI ── T25 publish
                         T26 k8s base+kind ── T27 Flux ── T28 image automation
                                               └── T29 Istio mTLS
                                  │
   T30 logs · T31 metrics · T32 tracing ── T33 obs stack ── T34 dashboard ── T36 alerts+runbook
   T35 timeouts · T37 k6 · T38 README
```

---

## Task List

### Phase 1 — Session 1: `platform-skeleton`

#### Task 1: Bootstrap the multi-module Maven project
**Description:** Generate the project from Spring Initializr (Java 25, Boot 4.1, groupId `com.huseyin`). Restructure it into a parent POM with the `taskhub-proto`, `taskhub-api` and `notification-service` modules. Add the Maven wrapper, Spotless (palantir-java-format), Surefire/Failsafe split, the Testcontainers BOM, `compose.yaml` with Postgres 17, and `.gitignore`.
**Acceptance criteria:**
- [ ] `./mvnw clean verify` passes on a fresh clone, with an empty context-load test per app module.
- [ ] Spotless check fails the build on a badly formatted file.
- [ ] `./mvnw -pl taskhub-api spring-boot:run -Dspring-boot.run.profiles=dev` starts Postgres through Docker Compose support.
**Verification:** `./mvnw clean verify`, then a manual dev run.
**Dependencies:** None (repo exists; see Open Questions)
**Files:** `pom.xml`, `*/pom.xml`, `compose.yaml`, `.gitignore`, `*Application.java`
**Scope:** M

#### Task 2: CLAUDE.md and ADR 0001
**Description:** Write `CLAUDE.md` covering architecture (package by feature, layering, DTO rules), workflow (issue → branch → PR, `./mvnw verify` before PR) and the boundaries copied from SPEC. Add `docs/adr/0001-architecture.md`.
**Acceptance criteria:**
- [ ] The CLAUDE.md boundaries match SPEC §Boundaries word for word.
- [ ] The ADR records the package-by-feature and multi-module decisions.
**Verification:** Review in the PR.
**Dependencies:** T1
**Files:** `CLAUDE.md`, `docs/adr/0001-architecture.md`
**Scope:** XS

#### Task 3: Global ProblemDetail error handling
**Description:** Add the `common/` exceptions (`NotFoundException`, `ConflictException`, `ForbiddenException`, `UnprocessableException`) and one `@RestControllerAdvice` that maps them, plus validation errors, malformed JSON and unknown routes, to `application/problem+json` with stable `type` URIs. Validation errors get an `errors[]` array.
**Acceptance criteria:**
- [ ] `GET /unknown` returns 404 ProblemDetail with `type`, `title`, `status`, `detail` and `instance`.
- [ ] Malformed JSON returns 400. Bean validation failure returns 400 with field `errors[]`.
- [ ] Each domain exception maps to the right status (slice test per case).
**Verification:** `./mvnw -pl taskhub-api test`
**Dependencies:** T1
**Files:** `common/GlobalExceptionHandler.java`, `common/*Exception.java`, `common/ProblemTypes.java`, test
**Scope:** M

#### Task 4: Profiles, typed config, Actuator probes, ping
**Description:** `application.yml` plus `-dev`/`-test`/`-prod`. Typed `@ConfigurationProperties` for JWT and CORS, validated at startup. Actuator exposes `health`, `info` and `prometheus`, with the liveness/readiness groups. Add a temporary `GET /api/v1/ping`.
**Acceptance criteria:**
- [ ] `/actuator/health/readiness` returns `UP`.
- [ ] The `prod` profile without `JWT_SECRET` fails at startup with a clear message (test).
- [ ] `/api/v1/ping` returns 200.
**Verification:** `./mvnw -pl taskhub-api verify`, plus a manual prod start without the env var.
**Dependencies:** T1
**Files:** `application*.yml`, `config/JwtProperties.java`, `config/CorsProperties.java`, `PingController.java`, test
**Scope:** M

### ✅ Checkpoint 1 — S1 done
- [ ] All S1 acceptance criteria in SPEC pass
- [ ] `./mvnw clean verify` green
- [ ] Review with human

---

### Phase 2 — Session 2: `identity`, `task-api`

#### Task 5: User registration
**Description:** Flyway `V1__create_users.sql`, the `User` entity with `Role` enum, the repository, and `POST /api/v1/auth/register` with BCrypt hashing. A duplicate username or email returns 409.
**Acceptance criteria:**
- [ ] Register returns 201 with a user response that never includes the hash.
- [ ] A duplicate username or email returns 409 ProblemDetail.
- [ ] Flyway applies the migration and `ddl-auto=validate` passes (IT with Testcontainers).
**Verification:** `./mvnw -pl taskhub-api verify`
**Dependencies:** T3, T4
**Files:** `db/migration/V1__create_users.sql`, `user/User.java`, `user/UserRepository.java`, `auth/AuthController.java`, `auth/AuthService.java` (+ tests)
**Scope:** M

#### Task 6: JWT login, resource server, `/users/me` ⚠️ high risk
**Description:** `JwtService` issues HS256 tokens with `sub`, `username`, `roles`, `iat` and `exp` (1 h) through `NimbusJwtEncoder`. `POST /auth/login` checks the credentials. `SecurityConfig` sets up a stateless resource server with roles taken from the `roles` claim. Add `GET /users/me`. Remove `/ping`.
**Acceptance criteria:**
- [ ] Login returns `{accessToken, tokenType, expiresIn}`. Bad credentials return 401 with a generic message.
- [ ] `/users/me` works with a valid token and returns 401 ProblemDetail for a missing, expired or tampered token.
- [ ] Public routes (`/auth/**`, health, docs) are reachable without a token.
**Verification:** `./mvnw -pl taskhub-api verify`
**Dependencies:** T5
**Files:** `config/SecurityConfig.java`, `auth/JwtService.java`, `auth/AuthController.java`, `user/UserController.java` (+ tests)
**Scope:** M

#### Task 7: CORS and security error responses
**Description:** Configure an explicit CORS allow-list from `CorsProperties`. Add an `AuthenticationEntryPoint` and an `AccessDeniedHandler` that return ProblemDetail.
**Acceptance criteria:**
- [ ] A preflight from an allowed origin succeeds and one from any other origin is rejected.
- [ ] 401 and 403 responses from the security filter chain are `application/problem+json`.
**Verification:** `./mvnw -pl taskhub-api test`
**Dependencies:** T6
**Files:** `config/SecurityConfig.java`, `config/ProblemDetailSecurityHandlers.java`, test
**Scope:** S

#### Task 8: Projects: create, list, get
**Description:** `V2__create_projects.sql` (projects plus `project_members`, with indexes). `Project` entity, repository, service, and the `ProjectAccess` component. `POST /projects` makes the caller owner and member. `GET /projects` is paged (own and member projects; all for ADMIN). `GET /projects/{id}` returns 403 to non-members and 404 when the project doesn't exist.
**Acceptance criteria:**
- [ ] The creator is owner and member, and the list shows only accessible projects (ADMIN sees all).
- [ ] A non-member gets 403 and a missing id gets 404.
- [ ] The paging response has the shape `{content, page, size, totalElements, totalPages}` and `size` is capped at 100.
**Verification:** `./mvnw -pl taskhub-api verify`
**Dependencies:** T6
**Files:** `V2__create_projects.sql`, `project/*` (entity, repo, service, controller, dto), `project/ProjectAccess.java`, `common/PageResponse.java`
**Scope:** M

#### Task 9: Project update, delete, membership
**Description:** `PUT` and `DELETE /projects/{id}` (owner/admin). `POST /projects/{id}/members` is idempotent. `DELETE /projects/{id}/members/{userId}` refuses to remove the owner. Unassigning the removed member's tasks is added in T10 once tasks exist (tracked there).
**Acceptance criteria:**
- [ ] A member who isn't the owner gets 403 on update, delete and member management.
- [ ] Adding an existing member is a no-op 200/204. Removing the owner returns 409/422.
- [ ] Deleting a project removes its membership rows.
**Verification:** `./mvnw -pl taskhub-api verify`
**Dependencies:** T8
**Files:** `project/ProjectController.java`, `project/ProjectService.java`, dto, tests
**Scope:** M

### ✅ Checkpoint 2 — identity + projects
- [ ] Register → login → create project → add member flow works through `api.http`
- [ ] `./mvnw clean verify` green
- [ ] Review with human

#### Task 10: Tasks: create and get
**Description:** `V3__create_tasks.sql` (FKs, `version`, indexes on `project_id`, `assignee_id`, `status`, `due_date`). `Task` entity with `TaskStatus` and `TaskPriority`. `POST` and `GET /projects/{id}/tasks/{taskId}`. An assignee who isn't a project member returns 422. Removing a member unassigns their tasks (closes the follow-up from T9). Deleting a project cascades to its tasks.
**Acceptance criteria:**
- [ ] A member can create a task and gets 201 with a `Location` header. A non-member gets 403.
- [ ] An assignee who isn't a member returns 422 ProblemDetail.
- [ ] Removing a member sets `assignee_id` to null on their tasks in that project.
**Verification:** `./mvnw -pl taskhub-api verify`
**Dependencies:** T9
**Files:** `V3__create_tasks.sql`, `task/*` (entity, repo, service, controller, dto), `project/ProjectService.java`
**Scope:** M

#### Task 11: Task list: filters, sort, paging, N+1-safe
**Description:** `GET /projects/{id}/tasks` with `status`, `assigneeId` and `priority` filters and sorting by `dueDate`, `createdAt` or `priority`. Use an `@EntityGraph`/fetch join or a DTO projection so a page of 50 runs at most 3 statements. Check the indexes with `EXPLAIN ANALYZE` and add a migration if the plan needs one.
**Acceptance criteria:**
- [ ] Each filter and sort combination returns the correct rows (IT).
- [ ] A page of 50 tasks with assignee and project names runs ≤ 3 SQL statements (asserted for now with Hibernate statistics; T21 adds the regression test).
- [ ] The PR description includes the `EXPLAIN ANALYZE` output for the main filter query.
**Verification:** `./mvnw -pl taskhub-api verify`
**Dependencies:** T10
**Files:** `task/TaskRepository.java`, `task/TaskService.java`, `task/TaskController.java`, `task/TaskSpecifications.java`, test
**Scope:** M

#### Task 12: Task update (optimistic lock), status PATCH, delete
**Description:** `PUT` requires `version`, and a stale version returns 409. `PATCH /status`. `DELETE` is allowed for owner/admin or the task creator; add a `created_by` column through `V4` if T10 doesn't already have it. Publish a `TaskChangedEvent` (Spring app event) on every mutation, ready for T19.
**Acceptance criteria:**
- [ ] Two concurrent `PUT`s with the same version: one 200 and one 409 ProblemDetail (IT).
- [ ] `PATCH` status works for members. `DELETE` by an ordinary member who isn't the creator returns 403.
- [ ] One `TaskChangedEvent` is published per successful mutation (unit test).
**Verification:** `./mvnw -pl taskhub-api verify`
**Dependencies:** T11
**Files:** `task/TaskService.java`, `task/TaskController.java`, `task/TaskChangedEvent.java`, dto, test
**Scope:** M

#### Task 13: `GET /tasks?assignee=me`
**Description:** A paged list of the caller's assigned tasks across all projects, with the same N+1-safe fetch strategy.
**Acceptance criteria:**
- [ ] It returns only tasks assigned to the caller, from any of their projects.
- [ ] It stays within the statement bound from T11.
**Verification:** `./mvnw -pl taskhub-api verify`
**Dependencies:** T11
**Files:** `task/MyTasksController.java`, `task/TaskRepository.java`, test
**Scope:** S

#### Task 14: OpenAPI / Swagger with bearer auth
**Description:** springdoc with a `bearerAuth` security scheme, operation summaries, and ProblemDetail documented as the error schema. Add `api.http` covering the full flow.
**Acceptance criteria:**
- [ ] Swagger UI lists every SPEC endpoint, and "Authorize" plus a token makes protected calls work.
- [ ] `/v3/api-docs` is public. Everything else stays protected.
**Verification:** Manual check in Swagger UI, plus a test that `/v3/api-docs` contains every path.
**Dependencies:** T12, T13
**Files:** `config/OpenApiConfig.java`, `pom.xml`, `api.http`, test
**Scope:** S

### ✅ Checkpoint 3 — S2 done
- [ ] All S2 acceptance criteria in SPEC pass
- [ ] Review with human

---

### Phase 3 — Session 3: `notification-service`, `test-strategy`

#### Task 15: `notification.proto` and stub generation ⚠️ high risk
**Description:** `taskhub-proto/src/main/proto/notification/v1/notification.proto` (package `taskhub.notification.v1`) with `PublishTaskEvent`, `SubscribeTaskEvents`, `TaskEvent`, `TaskEventType` (`*_UNSPECIFIED = 0`), `PublishAck` and `SubscribeRequest`. Generate the stubs with the protobuf Maven plugin. Wire Spring gRPC into both app modules (server in notification-service, client in taskhub-api), using the gRPC support that ships with Boot 4.1.
**Acceptance criteria:**
- [ ] `./mvnw -pl taskhub-proto install` generates and compiles the stubs.
- [ ] Both apps start with the Spring gRPC auto-configuration on (smoke test).
- [ ] The proto follows the SPEC contract rules (versioned package, an UNSPECIFIED zero value in every enum).
**Verification:** `./mvnw verify`
**Dependencies:** T1
**Files:** `notification.proto`, `taskhub-proto/pom.xml`, `notification-service/pom.xml`, `taskhub-api/pom.xml`
**Scope:** S

#### Task 16: Publish and stream with a subscriber registry
**Description:** `SubscriberRegistry` (thread-safe, in-memory). `PublishTaskEvent` fans out to the matching subscribers. `SubscribeTaskEvents` filters by `project_ids` and/or `assignee_id` and removes the subscriber when the client cancels.
**Acceptance criteria:**
- [ ] A published event reaches only the matching subscribers (in-process gRPC test).
- [ ] When a client cancels, its subscription is removed (the registry size goes down).
- [ ] `PublishAck.delivered_to` equals the number of matching streams.
**Verification:** `./mvnw -pl notification-service verify`
**Dependencies:** T15
**Files:** `grpc/NotificationGrpcService.java`, `subscription/SubscriberRegistry.java`, `subscription/Subscription.java`, tests
**Scope:** M

#### Task 17: Backpressure and graceful shutdown
**Description:** Give each subscription a bounded buffer (256, configurable). When it overflows, cancel that stream with `RESOURCE_EXHAUSTED`. Publishing never blocks. On shutdown, complete the open streams with `UNAVAILABLE`.
**Acceptance criteria:**
- [ ] A slow subscriber gets evicted while the other subscribers and the publisher are unaffected (test with Awaitility).
- [ ] Shutting down the app ends open streams with `UNAVAILABLE`.
**Verification:** `./mvnw -pl notification-service verify`
**Dependencies:** T16
**Files:** `subscription/Subscription.java`, `subscription/SubscriberRegistry.java`, `config/NotificationProperties.java`, test
**Scope:** S

#### Task 18: gRPC interceptors: correlation id and subscriber JWT
**Description:** A server interceptor that logs and propagates the correlation id. A JWT interceptor applies **only to `SubscribeTaskEvents`**: it validates the user token with the shared key and limits `project_ids` to projects the user belongs to. Membership is taken from a `project_ids` claim or checked against task-api; record the choice in an ADR. `PublishTaskEvent` has no app-level auth because Istio protects it (T29).
**Acceptance criteria:**
- [ ] Subscribe without a token, or with an invalid one, returns `UNAUTHENTICATED`.
- [ ] A user can't subscribe to a project they don't belong to (`PERMISSION_DENIED`, or the project is filtered out, as the ADR decides).
- [ ] The correlation id from client metadata shows up in the server logs.
**Verification:** `./mvnw -pl notification-service verify`
**Dependencies:** T16, T6
**Files:** `grpc/CorrelationIdInterceptor.java`, `grpc/JwtAuthInterceptor.java`, `config/GrpcSecurityConfig.java`, `docs/adr/0002-*.md`, tests
**Scope:** M

#### Task 19: task-api best-effort notification client
**Description:** In `notification/`, add a `@TransactionalEventListener(AFTER_COMMIT)` that maps a `TaskChangedEvent` to a `TaskEvent` and calls `PublishTaskEvent` with a configurable deadline (500 ms). On failure it logs, increments `taskhub_notification_publish_failures_total`, and does not throw.
**Acceptance criteria:**
- [ ] A rolled-back transaction publishes nothing (test).
- [ ] With notification-service down or timing out, `POST /tasks` still returns 201 and the failure counter goes up.
- [ ] Every call sets a deadline (asserted with an in-process server that delays).
**Verification:** `./mvnw -pl taskhub-api verify`
**Dependencies:** T12, T15
**Files:** `notification/TaskEventPublisher.java`, `notification/TaskEventMapper.java`, `config/GrpcClientConfig.java`, tests
**Scope:** M

### ✅ Checkpoint 4 — two services talking
- [ ] Running both apps locally, a `grpcurl` subscriber sees events from REST mutations
- [ ] Review with human

#### Task 20: JaCoCo coverage gate
**Description:** JaCoCo in the parent POM, a `coverage` profile for the reports, and a `check` in `verify`: ≥ 80 % line coverage on `**/service`-layer classes and ≥ 70 % per module, excluding generated gRPC code and config.
**Acceptance criteria:**
- [ ] `verify` fails when coverage drops below a threshold (shown by temporarily skipping a test).
- [ ] The reports are at `*/target/site/jacoco/index.html`.
**Verification:** `./mvnw verify -Pcoverage`
**Dependencies:** T19
**Files:** `pom.xml`, module `pom.xml`s
**Scope:** S

#### Task 21: N+1 regression test
**Description:** An IT that counts SQL statements (Hibernate `Statistics` or datasource-proxy) for the task list endpoint and the my-tasks endpoint, with 50 tasks seeded across several assignees.
**Acceptance criteria:**
- [ ] It asserts ≤ 3 statements for each endpoint.
- [ ] Removing the fetch strategy makes it fail (shown in the PR).
**Verification:** `./mvnw -pl taskhub-api verify`
**Dependencies:** T13
**Files:** `task/TaskListQueryCountIT.java`, test support class
**Scope:** S

#### Task 22: E2E tests across both services
**Description:** `*E2EIT`: start notification-service on a random port, plus taskhub-api with Testcontainers Postgres. Run register → login → subscribe → create task → assert the event arrives within 1 s. A second test stops notification-service and asserts that REST still returns 201.
**Acceptance criteria:**
- [ ] The event arrives within 1 s (Awaitility, no `Thread.sleep`).
- [ ] With notification-service down, REST returns 201 and the failure metric goes up.
**Verification:** `./mvnw verify`
**Dependencies:** T18, T19
**Files:** `taskhub-api/src/test/.../e2e/TaskNotificationE2EIT.java`, `taskhub-api/pom.xml` (test dep on notification-service)
**Scope:** M

### ✅ Checkpoint 5 — S3 done
- [ ] All S3 acceptance criteria in SPEC pass, and the coverage gate is enforced
- [ ] Review with human

---

### Phase 4 — Session 4: `delivery`, `gitops`

#### Task 23: Production Dockerfiles
**Description:** A multi-stage Dockerfile per app: a Maven build stage, then `java -Djarmode=tools extract --layers`, then a slim JRE runtime. Runs as UID 10001 with `-XX:MaxRAMPercentage=75`, graceful shutdown, a pinned base image digest, and `.dockerignore`.
**Acceptance criteria:**
- [ ] Each image is under 300 MB and `docker run … id -u` prints 10001.
- [ ] Rebuilding after a code-only change reuses the dependency layers.
**Verification:** `docker build …` for both apps, plus a manual check.
**Dependencies:** T22
**Files:** `taskhub-api/Dockerfile`, `notification-service/Dockerfile`, `.dockerignore`, `application.yml` (shutdown)
**Scope:** S

#### Task 24: CI merge gate
**Description:** `.github/workflows/ci.yml` on `pull_request`: JDK 25 Temurin with Maven cache, `./mvnw -B verify`, upload the surefire, failsafe and jacoco reports, build both images, and run Trivy (fail on fixable HIGH/CRITICAL).
**Acceptance criteria:**
- [ ] A PR with a failing test shows a red check.
- [ ] The Trivy step fails on a fixable HIGH/CRITICAL vulnerability.
**Verification:** Open a test PR and see the workflow run.
**Dependencies:** T23
**Files:** `.github/workflows/ci.yml`
**Scope:** S
**Ask first:** this is a change to CI config, so it's approved through this plan.

#### Task 25: Publish images to GHCR
**Description:** `.github/workflows/publish.yml` on push to `main`: build and push both images, tagged `sha-<short>` and `main-<run_number>`, with SBOM and provenance attestations. Branch protection (green CI plus 1 review) is set by the human; document the steps in `docs/ci-cd.md`.
**Acceptance criteria:**
- [ ] Merging to `main` publishes both images to GHCR with both tags.
- [ ] Attestations are visible on the package.
**Verification:** Merge a PR and check GHCR.
**Dependencies:** T24
**Files:** `.github/workflows/publish.yml`, `docs/ci-cd.md`
**Scope:** S

#### Task 26: Kubernetes base, local overlay, kind, Postgres
**Description:** `deploy/kind/cluster.yaml`. `deploy/base`: Deployments, Services, a ServiceAccount per app, ConfigMap, Secret references, resources, a hardened `securityContext`, and startup/liveness/readiness probes. `deploy/overlays/local` with 1 replica. `deploy/infrastructure/postgres` as a single StatefulSet.
**Acceptance criteria:**
- [ ] `kubectl apply -k deploy/overlays/local` on kind brings both apps to Ready.
- [ ] `kubectl kustomize` renders without errors, and no Secret values are committed.
**Verification:** Manual run on kind, plus `kubeconform` on the rendered output.
**Dependencies:** T23
**Files:** `deploy/kind/cluster.yaml`, `deploy/base/*.yaml`, `deploy/overlays/local/kustomization.yaml`, `deploy/infrastructure/postgres/*.yaml`
**Scope:** M

#### Task 27: Flux bootstrap and ordered Kustomizations
**Description:** `deploy/clusters/local`: Flux `GitRepository`, plus `Kustomization`s for infrastructure → apps with `dependsOn` and health checks. Document `flux bootstrap github` in `docs/kubernetes-gitops.md`.
**Acceptance criteria:**
- [ ] After bootstrap, Flux reconciles infrastructure and then the apps with no manual `kubectl apply`.
- [ ] A manual change to the Deployment gets reverted by Flux.
**Verification:** `flux get kustomizations` shows everything Ready.
**Dependencies:** T26
**Files:** `deploy/clusters/local/*.yaml`, `docs/kubernetes-gitops.md`
**Scope:** S

#### Task 28: Image automation
**Description:** `ImageRepository`, an `ImagePolicy` that picks the highest `main-<n>`, `ImageUpdateAutomation` that commits to `main`, and setter markers in the overlay.
**Acceptance criteria:**
- [ ] Within about 5 min of a merge, Flux commits the new tag and the new pods roll out.
- [ ] Reverting that commit rolls back.
**Verification:** Merge a trivial change and watch `flux get images all`.
**Dependencies:** T25, T27
**Files:** `deploy/clusters/local/image-automation.yaml`, `deploy/overlays/local/kustomization.yaml`
**Scope:** S

#### Task 29: Istio mTLS and authorization ⚠️ high risk
**Description:** Flux installs Istio (base + istiod, from upstream charts through a `HelmRelease`) in `deploy/infrastructure/istio`, ordered before the apps. Turn on sidecar injection for the `taskhub` namespace. Add `PeerAuthentication` STRICT and `AuthorizationPolicy` rules: `PublishTaskEvent` only from `cluster.local/ns/taskhub/sa/taskhub-api`, Subscribe from the ingress gateway, deny all else. Write ADR 0003 (Istio mTLS for service identity).
**Acceptance criteria:**
- [ ] `istioctl x describe pod` shows STRICT mTLS between the services, and probes stay healthy.
- [ ] `grpcurl` to `PublishTaskEvent` from a pod with another ServiceAccount returns `PERMISSION_DENIED`, while task-api's publishes succeed.
- [ ] Removing the AuthorizationPolicy is a Git change that Flux reconciles (no hand edits).
**Verification:** Manual on kind, with the commands listed in the PR.
**Dependencies:** T27
**Files:** `deploy/infrastructure/istio/*.yaml`, `deploy/base/namespace.yaml`, `deploy/base/istio-policies.yaml`, `docs/adr/0003-istio-mtls.md`
**Scope:** M
**Ask first:** this adds Istio policies, so it's approved through this plan.

### ✅ Checkpoint 6 — S4 done
- [ ] All S4 acceptance criteria in SPEC pass (push → GHCR → Flux → kind, with mTLS)
- [ ] Review with human

---

### Phase 5 — Session 5: `observability`

#### Task 30: Structured logging
**Description:** Boot structured logging (ECS) for every profile except dev. MDC carries `traceId`, `spanId`, the service name and `userId`, the last through a filter after authentication.
**Acceptance criteria:**
- [ ] Logs in prod/local are one JSON object per line, with `traceId` and `userId` on authenticated requests.
- [ ] A test asserts that passwords and tokens never appear in log output for the login and register paths.
**Verification:** `./mvnw verify`, plus a manual log check.
**Dependencies:** T22
**Files:** `application.yml` (both apps), `common/UserMdcFilter.java`, test
**Scope:** S

#### Task 31: Metrics
**Description:** Percentile histograms for `http.server.requests`. Add `taskhub_tasks_created_total`, `taskhub_notification_active_streams` (gauge) and gRPC server/client metrics. `taskhub_notification_publish_failures_total` already exists from T19.
**Acceptance criteria:**
- [ ] `/actuator/prometheus` exposes every metric named in SPEC S5.
- [ ] The gauge matches the number of open streams (test).
**Verification:** `./mvnw verify`, plus a manual scrape.
**Dependencies:** T19
**Files:** `application.yml`, `task/TaskMetrics.java`, `subscription/SubscriberRegistry.java`, test
**Scope:** S

#### Task 32: Distributed tracing across REST → gRPC
**Description:** Micrometer Tracing with an OTLP exporter in both apps, and gRPC client/server observation so the context propagates. Sampling is set from config.
**Acceptance criteria:**
- [ ] One `POST /tasks` produces one trace with spans from both services (in-memory exporter test or Tempo check).
- [ ] The log `traceId` matches the trace id.
**Verification:** `./mvnw verify`, plus a manual check in Tempo.
**Dependencies:** T30
**Files:** module `pom.xml`s, `application.yml` (both), `config/GrpcObservationConfig.java`, test
**Scope:** M

#### Task 33: Observability stack (local compose and cluster)
**Description:** Prometheus, Grafana, Loki, Alloy and Tempo. Add them to `compose.yaml` under an `observability` profile, and to `deploy/infrastructure/observability` through Flux HelmReleases. Grafana datasources are provisioned, with Tempo→Loki trace-to-logs links.
**Acceptance criteria:**
- [ ] In compose and on kind, Grafana shows the Prometheus, Loki and Tempo datasources working.
- [ ] Clicking from a trace span in Tempo opens the matching Loki logs.
**Verification:** Manual check, both environments.
**Dependencies:** T27, T32
**Files:** `compose.yaml`, `observability/alloy/config.alloy`, `observability/grafana/datasources.yaml`, `deploy/infrastructure/observability/*.yaml`
**Scope:** M

#### Task 34: RED dashboard
**Description:** A provisioned Grafana dashboard showing rate, error ratio (`or vector(0)`) and p99 per endpoint for both services, plus gRPC panels.
**Acceptance criteria:**
- [ ] The dashboard loads from provisioning with no manual import.
- [ ] The error panel shows 0, not "No data", when there are no 5xx responses.
**Verification:** Manual, under load from T37.
**Dependencies:** T31, T33
**Files:** `observability/dashboards/taskhub-red.json`, dashboard provider config
**Scope:** S

#### Task 35: Every wait has a limit
**Description:** HTTP server timeouts, Hikari `connectionTimeout`, `maxLifetime` and a statement timeout, the gRPC deadline (T19, re-checked), and `spring.lifecycle.timeout-per-shutdown-phase`. Document each value and the reason for it.
**Acceptance criteria:**
- [ ] Every timeout is set explicitly in config with a comment.
- [ ] A slow query beyond the statement timeout returns a ProblemDetail error, not a hang (IT with `pg_sleep`).
**Verification:** `./mvnw verify`
**Dependencies:** T19
**Files:** `application.yml` (both), `common/GlobalExceptionHandler.java`, test
**Scope:** S

#### Task 36: Alert rules and runbook
**Description:** Prometheus alert rules: 5xx > 2 % for 5 min, p99 > 500 ms for 10 min, publish failures > 0.5/s, pod restarts. Each has a `runbook_url` annotation. `docs/runbook.md` gets one entry per alert (what it means, how to confirm, first actions, escalation).
**Acceptance criteria:**
- [ ] `promtool check rules` passes.
- [ ] Stopping notification-service fires the publish-failure alert within its `for:` window.
- [ ] Every alert has a runbook entry.
**Verification:** Manual fire drill on kind.
**Dependencies:** T34
**Files:** `observability/alerts/taskhub.rules.yaml`, `docs/runbook.md`, `deploy/infrastructure/observability/alerts.yaml`
**Scope:** M

#### Task 37: k6 load test
**Description:** `load/tasks-smoke.js` sets up users and projects and mixes reads and creates at 50 VUs, with thresholds `p(95) < 300 ms` and `http_req_failed < 1 %`. `load/README.md` documents the reference setup.
**Acceptance criteria:**
- [ ] The k6 run passes its thresholds against kind on the documented setup.
- [ ] The README explains how to make sure the test measures the app and not the laptop.
**Verification:** `k6 run load/tasks-smoke.js`
**Dependencies:** T28
**Files:** `load/tasks-smoke.js`, `load/README.md`
**Scope:** S

#### Task 38: README quick start
**Description:** `README.md` covers prerequisites (JDK 25, Docker, kind, Flux CLI, istioctl), local dev, running tests, and the full kind + Flux path. Check it by following the README from a fresh clone.
**Acceptance criteria:**
- [ ] Following only the README from a fresh clone reaches a running, observable system.
**Verification:** Manual run from a fresh clone.
**Dependencies:** T36, T37
**Files:** `README.md`
**Scope:** S

### ✅ Checkpoint 7 — V1 complete
- [ ] All SPEC success criteria met
- [ ] `/ship` production-readiness gate passes
- [ ] Review with human

---

## Parallelization

- **Sequential:** T5 → T6 → T8 → T9 → T10 → T11 → T12 (migrations V1–V4 in order).
- **Parallel once the contract is fixed:** after T15, T16–T18 (server) and T19 (client) can go side by side. T2, T3 and T4 can run in parallel after T1. T30, T31 and T35 can run in parallel.
- **Coordination:** T18's membership-lookup choice changes the JWT claims from T6. Decide it before T18 starts.

## Risks and Mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| Spring gRPC / Boot 4.1 auto-config differs from what we expect | High | T15 is a smoke task early in S3. Check against the Boot 4.1 reference docs before writing code |
| Testcontainers needs Docker on every machine and in CI | Med | `./mvnw test` stays Docker-free. The README states the Docker requirement |
| Istio on kind is heavy (CPU/RAM) and adds sidecars that affect probes and startup | High | Use the minimal Istio profile, keep the default probe rewrite, document kind resource needs, and let T29 roll back by Git revert |
| Flux image automation writing to `main` conflicts with branch protection | Med | Give the Flux deploy key a bypass, or let it push to a branch with an auto-merged PR. Decide in T28 |
| JaCoCo thresholds block progress early | Low | The gate starts in T20, after the S2 code exists, and the per-package rule targets services only |
| Subscriber authorization needs project membership in notification-service | Med | An ADR in T18: a `project_ids` claim (simple, can go stale within 1 h) or a lookup against task-api |
| The monorepo layout breaks GitHub Actions and Flux paths | High | Make taskhub-v2 its own repo (Open Question 1) |

## Open Questions

1. **Repository:** should `taskhub-v2` become its own Git repo and GitHub repo (e.g. `huseyinbabal/taskhub-v2`), the way `../taskhub` is today? GitHub Actions (T24) and Flux (T27) need it at the repo root, and the GitHub issues for this plan should live there. It's currently a plain folder inside `spring-boot-agentic-ai`.
