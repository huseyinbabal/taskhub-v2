# TaskHub: rules for the agent

TaskHub is a team task-management backend: Java 25, Spring Boot 4.1, PostgreSQL, gRPC. It is built in five sessions.
**`SPEC.md` is the source of truth** for scope and behaviour. The work is tracked as GitHub issues `#1–#45`, and `tasks/plan.md` is their ordered index.
If a request conflicts with SPEC.md, stop and ask. Don't silently diverge.

## Commands

```bash
# Run everything with JDK 25 (check: java -version)
./mvnw clean verify                 # full gate: unit + slice + *IT (Testcontainers, needs Docker) + Spotless
./mvnw test                         # fast loop: *Test only, no Docker
./mvnw -pl taskhub-api -am verify   # one module
./mvnw spotless:apply               # fix formatting before committing
./mvnw -pl taskhub-api spring-boot:run -Dspring-boot.run.profiles=dev   # starts Postgres via compose.yaml
```

## Architecture

- **Modules:** `taskhub-proto` (gRPC contract and stubs), `taskhub-api` (REST: identity and task-api), `notification-service` (gRPC server). There's no shared "common" module across apps. The only shared code is the proto.
- **Package by feature**, under `com.huseyin.taskhub`: `auth/`, `user/`, `project/`, `task/`, `notification/`, plus `common/` (errors, paging) and `config/`. Don't create top-level `controller/`, `service/` or `repository/` packages.
- **Layering inside a feature:** `Controller → Service → Repository`.
  - Controllers handle HTTP mapping only. They take and return DTOs and hold no business rules.
  - Services own `@Transactional` boundaries and **ownership/authorization checks** (through `ProjectAccess`).
  - Repositories stay inside their feature package. Other features call the **service**, never another feature's repository.
- **DTOs are the contract, entities are storage.** Request and response types are Java records named `*Request` / `*Response`, mapped explicitly (`static from(...)` or a small mapper). Never return or accept a JPA entity in a controller.
- **Errors:** throw domain exceptions (`NotFoundException`, `ConflictException`, `ForbiddenException`, …). The single `@RestControllerAdvice` turns them into RFC 9457 `ProblemDetail`. Don't build error bodies by hand in controllers.
- **Config:** typed `@ConfigurationProperties` records, validated at startup. No `@Value` scattered through the code. Secrets only come from environment variables.
- **Schema:** Flyway owns it (`db/migration/V<n>__<verb>_<noun>.sql`), and `ddl-auto` is always `validate`.
- **gRPC:** the contract changes only by adding things (new tags, appended enum values, removed fields marked `reserved`). taskhub-api publishes **after commit, best effort, with a deadline**. A notification failure never fails a REST request.

## Code style

- Spotless with palantir-java-format is enforced in `verify`: 4 spaces, 120 columns, no wildcard imports.
- Constructor injection only. Lombok is limited to `@RequiredArgsConstructor`, `@Getter` and `@Slf4j`. No `@Data` on entities.
- Classes are package-private by default and `public` only when another package needs them.
- REST: `/api/v1`, plural kebab-case paths, camelCase JSON. DB: snake_case.

## Testing

- `*Test` runs under Surefire (unit and slice tests, no Docker). `*IT` runs under Failsafe (`@SpringBootTest` plus Testcontainers Postgres 17 via `TestcontainersConfiguration`). `*E2EIT` is for the few tests that span both services.
- Real PostgreSQL only, never H2. Use Awaitility for asynchronous assertions, never `Thread.sleep`. Each test sets up its own data.
- Write a failing test first, for features and bug fixes alike.
- The JaCoCo gate (from T20): ≥ 80 % line coverage on services and ≥ 70 % per module.

## Workflow

1. Pick the next unblocked issue (see `tasks/plan.md`, and check its "blocked by" links).
2. Branch from an up-to-date `main`: `feat/<issue>-<slug>`, `fix/…`, `docs/…`, `build/…`.
3. Work TDD-style in small commits. Run `./mvnw verify` before pushing.
4. Open a PR with `gh pr create`. The body must include `Closes #<issue>`, the SPEC section or module id, and the acceptance criteria with evidence.
5. Never merge it yourself. A human reviews and merges.
6. Record significant decisions as `docs/adr/NNNN-<slug>.md`.

## Boundaries

<!-- Copied verbatim from SPEC.md §Boundaries. Change them there first, then here. -->


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
