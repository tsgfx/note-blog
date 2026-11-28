# Architecture Governance Log

## Scope

Systematic architecture entropy reduction for the note-blog product. Work is isolated in this worktree and must not depend on uncommitted redesign changes in the main checkout.

## Invariants

- Preserve public routes, API contracts, article links, RSS, authentication behavior, ICP text, and production data compatibility.
- Prefer deletion and migration over parallel implementations.
- Each batch must include caller migration, verification, and an independent commit.
- Product-impacting or destructive decisions are recorded as pending confirmations instead of being executed unilaterally.

## Current phase

Phase 0: repository census, architecture map, ownership map, duplication inventory, and risk-ordered task pool.

## Pending confirmations

1. Whether uncommitted visual redesign changes in the main checkout should later be incorporated into this governance line or treated as a separate product branch.


## Batch log

### B1 — canonical frontend API unwrapping

- Migrated duplicated Axios response-unwrapping in `api/notes.ts` and `api/comments.ts` to `api/client.ts` helpers.
- Preserved operation-specific fallback error messages as explicit helper arguments.
- Kept `api/account.ts#getMe` as a documented exception because it intentionally avoids the global 401 redirect.
- Verification: `npm run lint`, `npm run format:check`, `npm run build`, and 28 Node unit tests passed.
- Follow-up constraint candidate: prevent new direct `http.*` usage outside `api/client.ts` unless explicitly justified.


### B1-follow-up — enforce canonical API client

- Added ESLint architecture guards preventing business modules from importing `axios` directly or importing the internal `http` object from `api/client.ts`.
- The intentional `fetch` in `api/account.ts#getMe` remains allowed because it is not an Axios bypass and exists specifically to avoid global 401 redirect side effects.
- Verification: `npm run lint` passed.


### B3 — public read ownership and repository layering

- Added `PublicBlogService` as the authoritative public-content boundary: published-only filtering, public pagination/sorting limits, detail adjacent-post composition, tag/stat aggregation, feed/sitemap read models, related-post visibility checks, and PV writes.
- Migrated `PublicBlogController`, `RssController`, and `SitemapController` away from `NoteRepository`; RSS no longer receives JPA entities at the HTTP edge.
- Moved account identity composition into `AuthService`; `AccountController` no longer uses `UserRepository`.
- Added ArchUnit enforcement: controllers must not depend on repositories, and repositories may only be used from service/repository code.
- Verification: full backend suite passed (273 tests, 0 failures/errors, 2 Docker-dependent skips); `git diff --check` clean.


### B2 — canonical display date formatting

- Added `frontend/src/lib/date.ts` as the single owner of the ISO display formats actually used by the UI.
- Migrated ad-hoc date slicing in Blog, Home, Notes, and NoteDetail without changing rendered formats.
- Added focused unit tests; verification passed: lint, format check, production build, 30 frontend Node tests.


### B4 — canonical public URLs and XML escaping

- Added `SiteUrls` as the owner of public-site URL normalization and public route construction (`/`, `/blog`, `/blog/{slug}`, `/sitemap.xml`).
- Added `XmlEscape` as the single XML text escaping implementation.
- Migrated RSS, sitemap, robots, and comment notification links; removed parallel trailing-slash/XML escaping implementations.
- Explicitly did not migrate `InboxService` quick-capture endpoint construction because API endpoint URLs and public article URLs are semantically different despite often sharing a deployed origin.
- Verification: full backend suite passed; focused controller/service/config tests passed.


### B5 — note catalog/write boundary

- Extracted `NoteCatalogService` as the owner of note list filtering, path/library semantics, tag facets, folder navigation, library aggregation, and admin detail reads.
- Kept write lifecycle, publication transitions, batch operations, audit, and index invalidation/rebuild coordination in `NoteService` because those behaviors share transaction side effects.
- Migrated `NoteController` and `PublicBlogService` so public filtered reads depend on the catalog service rather than the write service.
- Updated `FolderTree`, repository documentation, controller tests, and focused service tests; `NoteLibraryTest` now tests the catalog service with only `NoteRepository` as a dependency.
- Verification: full backend suite passed (276 tests, 0 failures/errors, 2 Docker-dependent skips).


### B6 — frontend auth session ownership

- Added `lib/authSession.ts` as the sole owner of browser auth persistence: token lookup, session presence, login storage, and session clearing.
- Migrated Axios interceptors, auth/account API helpers, router guards, login, and ask guest detection away from duplicated raw storage keys.
- Deleted the unused display-name persistence write; `notes_blog_user` is now only a legacy key removed during session cleanup.
- Added a structural regression test that fails if auth storage keys appear outside the owner module.
- Verification: frontend lint, format check, production build, and 32 Node tests passed.


### B7 — public reading-history ownership

- Added `lib/readHistory.ts` as the sole owner of the reading-history storage key, newest-first ordering, deduplication, 10-entry cap, and malformed-data fallback.
- Migrated blog recommendation loading and article-detail history recording to that owner.
- Added behavior tests for ordering/capping/damaged data and a structural test preventing direct key use elsewhere.
- Verification: frontend lint, format check, production build, and 35 Node tests passed.


### B8 — admin catalog paging/sorting ownership

- Moved admin note-list page clamping, the 100-row cap, and stable sort mapping from `NoteController` into `NoteCatalogService.adminList`.
- Kept public paging bounds separate in `PublicBlogService`; no artificial global pagination constant was introduced because the two audiences have different constraints.
- Reweighted tests so controller slices verify HTTP-to-service parameter pass-through and service unit tests verify business paging/sorting behavior.
- Verification: full backend suite passed (279 tests, 0 failures/errors, 2 Docker-dependent skips).


### B9 — audit paging ownership and pagination anti-regression

- Moved audit-log page/size clamping, repository paging, and DTO mapping into `AuditService`.
- Invalid paging input now converges to safe values instead of risking a 500 from `PageRequest` construction.
- Added ArchUnit rules preventing controllers from depending on `PageRequest` or `Sort` again.
- Verification: full backend suite passed (283 tests, 0 failures/errors, 2 Docker-dependent skips).


### B10 — editor draft persistence ownership

- Added `lib/editorDraft.ts` as the sole owner of the editor-draft storage key, record shape, serialization, malformed-record fallback, and storage failure handling.
- Kept dirty-state timing, restore confirmation, and Tiptap content synchronization in `NoteEditorView`, where that orchestration belongs.
- Added behavior tests for round-trip/corruption/cleanup and a structural test preventing direct key use elsewhere.
- Verification: frontend lint, format check, production build, and 38 Node tests passed.


### B11 — admin tag-option ordering

- Extracted the tag filter bar's selected-first/search/expanded/collapsed ordering into `lib/tagOptions.ts`.
- Kept tag loading, query state, and selection actions in `NotesView`; only the deterministic presentation rule moved.
- Added focused tests covering each ordering branch, reducing the amount of untestable logic embedded in the 1,900-line view.
- Verification: frontend lint, format check, production build, and 42 Node tests passed.


### B12 — admin note-folder labels

- Extracted relative folder-label semantics into `lib/noteFolder.ts`.
- Locked down the four display branches—new note, root directory, nested path, and active-path collapse—with focused tests.
- The large view now only adapts reactive state to the canonical rule; no rendering or filtering behavior changed.
- Verification: frontend lint, format check, production build, and 46 Node tests passed.


### B13 — resizable width controller

- Extracted duplicated drag-to-resize behavior from `NotesView` and `AskDrawer` into `lib/resizableWidth.ts`.
- The canonical controller owns viewport clamping, pointer listener lifecycle, body drag state, persistence, restore, reset, and cleanup.
- Callers retain only their semantic differences: measurement direction, size bounds, mobile breakpoint, storage key, and CSS/reactive application.
- Avoided copying CSS-owned default widths into TypeScript; the controller uses `null` for the caller-owned default state.
- Verification: frontend lint, format check, production build, and 49 Node tests passed.


### B14 — publish-preflight dialog boundary

- Extracted the preflight modal into `NotePreflightDialog.vue` with a small props/events contract (`open`, `report`, `close`, `confirm(ids)`).
- Moved exclusion state, warning cleanup, publishable counting, Escape handling, modal markup, and scoped styles into that owner.
- Extracted blocker/warning/publishable-id decisions into `lib/preflight.ts` so the core rule is testable without mounting Vue.
- `NotesView` now only fetches the report, opens the dialog, and executes the confirmed batch publish; it shrinks by roughly 330 lines.
- Verification: frontend lint, format check, production build, and 52 Node tests passed.


### B15 — rate-limit configuration ownership

- Added typed `RateLimitProperties` for the five high-cost endpoint limits.
- Removed duplicated Java-side `@Value` defaults; `application.yml` is now the sole owner of defaults and environment-variable mapping.
- `RateLimitConfig` constructor-injects the typed contract and explicitly enables it so controller test slices continue to boot.
- Added a Binder contract test for the canonical `app.rate-limit.*` keys.
- Verification: full backend suite passed (284 tests, 0 failures/errors, 2 Docker-dependent skips).


### B16 — embedding probe configuration ownership

- `OllamaProbe` and `OpenAiEmbeddingProbe` now consume canonical typed `NotesProperties` instead of re-reading `notes.embedding.*` with `@Value`.
- Monitoring degradation semantics remain local to the probes: missing Ollama config falls back to localhost; external failures still return false rather than breaking application startup or health reporting.
- Added local HTTP-server tests covering endpoint selection, trailing-slash normalization, auth propagation, and failure containment.
- Verification: full backend suite passed (286 tests, 0 failures/errors, 2 Docker-dependent skips).


### F10 — markdown source-edit ownership

- Added `frontend/src/lib/sourceTextEdit.ts` as the canonical owner of pure Markdown source-editing transformations.
- `NoteEditorView` now owns only textarea measurement, focus, and selection restoration through one adapter.
- Preserved historical edge semantics, including leading newline insertion, placeholder selection, idempotent line prefixes, and partial-line replacement behavior.
- Verification: 58/58 frontend Node tests passed; lint, format check, and production build passed.


### B17 — public site metadata ownership

- `SiteUrls` now owns the public site title as well as canonical public URLs.
- RSS no longer injects `site.title` independently; notification copy no longer hardcodes the same title.
- Notification test constructors now consume `SiteUrls` directly, removing a parallel string-based normalization path.
- Bark notification grouping was intentionally not merged into site title because it is a separate provider-specific display concept.
- Verification: full backend suite passed (287 tests, 0 failures/errors, 2 Docker-dependent skips).
