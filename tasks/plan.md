# Implementation Plan: TaskHub V1

Source spec: [`SPEC.md`](../SPEC.md) (approved 2026-11-24)

Task list target: **GitHub Issues** in [huseyinbabal/taskhub-v2](https://github.com/huseyinbabal/taskhub-v2/issues), one issue per task and one PR per issue (`Closes #N`).

## Overview

We build TaskHub in five session phases that follow the capability map: platform-skeleton → identity → task-api → notification-service → test-strategy → delivery → gitops → observability. Every task is a vertical slice that leaves `./mvnw verify` green. Each phase ends with a checkpoint that matches the acceptance criteria of its SPEC session.

## Architecture Decisions

- **Repository:** `taskhub-v2` is its own repo ([huseyinbabal/taskhub-v2](https://github.com/huseyinbabal/taskhub-v2)), because GitHub Actions and Flux need it at the repo root.
- **Test infrastructure from day one:** Surefire runs `*Test`, and Failsafe plus Testcontainers run `*IT`. Both are wired in Task 1, so S2 work can follow TDD against real Postgres. S3 then adds the coverage gate, the N+1 regression test and the E2E tests on top. It doesn't have to invent the test setup.
- **Contract first for gRPC:** the proto (Task 15) lands before the server or client, so Tasks 16–19 build against a frozen contract.
- **Ownership checks live in services**, through one `ProjectAccess` component (`requireMember`, `requireOwnerOrAdmin`). Controllers and URL rules handle only authentication and roles.
- **Event publishing:** task services publish a Spring `TaskChangedEvent`. A `@TransactionalEventListener(AFTER_COMMIT)` adapter in `notification/` turns it into gRPC calls. The task code never sees gRPC.
- **Riskiest work goes first in each phase:** JWT/security (T6), gRPC on Boot 4.1 (T15) and Istio (T29) each start their phase so problems show up early.

## Dependency Graph

`T<n>` is the plan id. Every issue title starts with it.

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

Tasks live in GitHub Issues: https://github.com/huseyinbabal/taskhub-v2/issues. Each issue has the full description, acceptance criteria, verification steps and the files it touches. Dependencies are GitHub "blocked by" links. This list is the ordered index.


### Phase 1 — Session 1: `platform-skeleton`
- [ ] #1 T1: Bootstrap the multi-module Maven project
- [ ] #2 T2: CLAUDE.md and ADR 0001
- [ ] #3 T3: Global ProblemDetail error handling
- [ ] #4 T4: Profiles, typed config, Actuator probes, ping
- [ ] #5 ✅ Checkpoint 1 — S1 done

### Phase 2 — Session 2: `identity`, `task-api`
- [ ] #6 T5: User registration
- [ ] #7 T6: JWT login, resource server, `/users/me`
- [ ] #8 T7: CORS and security error responses
- [ ] #9 T8: Projects: create, list, get
- [ ] #10 T9: Project update, delete, membership
- [ ] #11 ✅ Checkpoint 2 — identity + projects
- [ ] #12 T10: Tasks: create and get
- [ ] #13 T11: Task list: filters, sort, paging, N+1-safe
- [ ] #14 T12: Task update (optimistic lock), status PATCH, delete
- [ ] #15 T13: `GET /tasks?assignee=me`
- [ ] #16 T14: OpenAPI / Swagger with bearer auth
- [ ] #17 ✅ Checkpoint 3 — S2 done

### Phase 3 — Session 3: `notification-service`, `test-strategy`
- [ ] #18 T15: `notification.proto` and stub generation
- [ ] #19 T16: Publish and stream with a subscriber registry
- [ ] #20 T17: Backpressure and graceful shutdown
- [ ] #21 T18: gRPC interceptors: correlation id and subscriber JWT
- [ ] #22 T19: task-api best-effort notification client
- [ ] #23 ✅ Checkpoint 4 — two services talking
- [ ] #24 T20: JaCoCo coverage gate
- [ ] #25 T21: N+1 regression test
- [ ] #26 T22: E2E tests across both services
- [ ] #27 ✅ Checkpoint 5 — S3 done

### Phase 4 — Session 4: `delivery`, `gitops`
- [ ] #28 T23: Production Dockerfiles
- [ ] #29 T24: CI merge gate
- [ ] #30 T25: Publish images to GHCR
- [ ] #31 T26: Kubernetes base, local overlay, kind, Postgres
- [ ] #32 T27: Flux bootstrap and ordered Kustomizations
- [ ] #33 T28: Image automation
- [ ] #34 T29: Istio mTLS and authorization
- [ ] #35 ✅ Checkpoint 6 — S4 done

### Phase 5 — Session 5: `observability`
- [ ] #36 T30: Structured logging
- [ ] #37 T31: Metrics
- [ ] #38 T32: Distributed tracing across REST → gRPC
- [ ] #39 T33: Observability stack (local compose and cluster)
- [ ] #40 T34: RED dashboard
- [ ] #41 T35: Every wait has a limit
- [ ] #42 T36: Alert rules and runbook
- [ ] #43 T37: k6 load test
- [ ] #44 T38: README quick start
- [ ] #45 ✅ Checkpoint 7 — V1 complete

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

## Open Questions

None. The repository question was resolved on 2026-11-24 (own public repo).
