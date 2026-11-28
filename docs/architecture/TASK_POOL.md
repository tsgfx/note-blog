# Architecture Optimization Task Pool

Statuses: `ready` = enough evidence and safe batch shape; `research` = needs caller/contract analysis; `blocked` = product/destructive decision required.

## Ready batches

### B1 — Canonical frontend API unwrapping ✅ completed

**Entropy:** `api/client.ts` owns `get/post/patch/del`, but `api/notes.ts` manually re-implements Axios unwrapping for update/delete/upload/publish/unpublish/generate-meta.

**Target:** one canonical response-unwrapping implementation.

**Migration shape:** replace manual `http.*` calls with existing helpers; no API contract or behavior change.

**Verification:** frontend typecheck/lint/build; existing unit/e2e smoke where available.

**Result:** completed in this branch. All manual Axios unwrap call sites in `api/notes.ts` and `api/comments.ts` migrated to canonical helpers. `api/account.ts#getMe` remains an intentional raw-fetch exception because the axios 401 redirect would interfere with router guard behavior. Verification: lint/format/build/node tests all passed.

**Deletion test:** deleting duplicated unwrap blocks reduces complexity without pushing details into callers.

### B2 — Canonical display date formatting

**Entropy:** ad-hoc ISO slicing and locale formatting across views.

**Target:** small date display utility with explicit formats, then migrate callers.

**Caution:** do not create a generic date framework; only canonicalize formats actually used.

### B3 — Public-blog read ownership ✅ completed

**Entropy:** `PublicBlogController`, `RssController`, and `SitemapController` directly query `NoteRepository`; public publication rules spread over web classes.

**Target:** one service-owned public read facade for posts/detail/stats/feed/sitemap data while keeping XML generation at the edge.

**Caution:** avoid shallow passthrough. The service must own publication filtering/query composition, not simply forward every repository method.

**Result:** completed. Added `PublicBlogService` as the public-content boundary for posts/detail/tags/related/stats/feed/sitemap and PV writes. RSS/sitemap controllers now consume service-owned read models instead of repositories/entities. `AccountController` display-name lookup moved into `AuthService`. Added ArchUnit rules: controllers may not depend on repositories, and only service/repository code may depend on repositories. Verification: full backend test suite passed, 273 tests, 0 failures/errors, 2 Docker-dependent skips.

## Research batches

### R1 — NoteService responsibility boundaries

Possible seams: query/filtering, write lifecycle, publishing lifecycle, navigation aggregations, indexing orchestration. Must map callers and transaction semantics before splitting.

### R2 — Giant Vue views

`NotesView`, `NoteEditorView`, `NoteDetailView`, `HomeView`, `BlogView`, `AskDrawer` need state ownership maps. Extract only cohesive logic with real reuse or independent testability; avoid cosmetic component splitting.

### R3 — Configuration contract inventory

Inventory every env var, YAML default, compose mapping, deploy script dependency, and docs mention. Then decide whether typed configuration properties are worth it. Do not mechanically replace working `@Value` uses.

### R4 — Error taxonomy and retry behavior

Backend has unified response/errors; frontend Axios layer handles 401 globally. Need map domain errors, user-facing messages, retry/idempotency expectations, especially publish/index/LLM operations.

### R5 — Architecture enforcement

Candidate checks after first migrations:

- frontend lint rule or AST check preventing new direct `http` usage outside `api/client.ts` unless justified ✅ implemented;
- expand backend ArchUnit rules beyond repository ownership after dependency directions are mapped;
- dependency-direction test for domain/dto/service/controller layers.

## Blocked / pending product decisions

- Whether the uncommitted visual redesign in the main checkout should be merged into this governance line later. Governance work currently treats it as a separate product branch.
- Whether public read model DTOs can be optimized/shrunk. Any response-shape change is an external contract decision.
- Whether `library` legacy query compatibility can eventually be removed. Current comments indicate real bookmark compatibility, so retain.
