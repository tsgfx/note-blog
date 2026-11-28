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
- Verification: full backend suite passed (287 tests, 0 failures/errors, 2 Docker-dependent skips).


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


### B18 — inbox route contract ownership

- Added scoped `ApiRoutes.INBOX_QUICK_CAPTURE` for the one route genuinely shared across layers.
- Controller mapping, security permit rule, and generated integration examples now use the same compile-time contract.
- `InboxService` consumes canonical `SiteUrls` rather than independently reading and trimming `site.base-url`.
- `ApiRoutes` documentation explicitly restricts it to routes shared across layers, avoiding a generic string grab-bag.
- Verification: full backend suite passed (287 tests, 0 failures/errors, 2 Docker-dependent skips).


### B19 — notification payload formatting ownership

- Added package-private `NotificationPayloadFormatter` as the sole owner of webhook-provider payload schemas.
- Removed the public `buildPayloadJson` method from `NotificationService`; formatting is no longer leaked through the transport service interface.
- `NotificationService` now owns only enablement, canonical article URL construction, asynchronous execution, transport, logging, and failure containment.
- Payload tests target the formatter directly; transport tests remain on the service.
- Corrected the disabled-webhook test to verify the actual `send` transport method instead of a vacuous `sendAsync` assertion.
- Verification: full backend suite passed (287 tests, 0 failures/errors, 2 Docker-dependent skips).


### F11 — notes list keyboard decision ownership

- Added `frontend/src/lib/notesListKeyboard.ts` as the canonical owner of note-list keyboard shortcut decisions.
- The Vue view still owns DOM focus, scrolling, selection mutation, and route navigation.
- Preserved modal interception, typing behavior, modifier exclusion, focused-row requirements, and Escape semantics.
- Verification: 61/61 frontend Node tests passed; lint, format check, and production build passed.


### B20 — shared note series taxonomy

- Added `NoteTaxonomy.seriesName` as the single owner of `专栏:` / `系列:` tag interpretation.
- Migrated both backlink and related-note recommendation consumers.
- Preserved Backlink's separate source-folder fallback rather than merging distinct semantics.
- Verification: full backend suite passed (289 tests, 0 failures/errors, 2 Docker-dependent skips).


### B20 — shared note series taxonomy

- Added `NoteTaxonomy.seriesName` as the single owner of `专栏:` / `系列:` tag interpretation.
- Migrated both backlink and related-note recommendation consumers.
- Preserved the distinct Backlink source-folder fallback rather than merging different semantics.
- Verification: full backend suite passed (289 tests, 0 failures/errors, 2 Docker-dependent skips).


### B21 — transactional side-effect scheduling

- Added `TransactionSideEffects.afterCommitOrNow` as the canonical implementation of post-commit-or-immediate execution.
- Migrated note reindexing, vector-index invalidation, and inbox reindexing away from copied Spring synchronization boilerplate.
- Kept domain rationale at the business call sites; the utility owns only transaction synchronization mechanics.
- Verification: full backend suite passed (291 tests, 0 failures/errors, 2 Docker-dependent skips).


### E3 — transaction synchronization guardrail

- Added an ArchUnit rule preventing production code outside `common` from depending directly on `TransactionSynchronizationManager`.
- This makes the B21 post-commit side-effect boundary machine-enforceable rather than convention-only.
- Verification: full backend suite passed (292 tests, 0 failures/errors, 2 Docker-dependent skips).


### F12 — post-view claiming ownership

- Added `frontend/src/lib/viewCounter.ts` as the sole owner of `blog_viewed_` session claiming.
- `NoteDetailView` now asks the canonical owner whether a view should be reported instead of manipulating session keys inline.
- Added source-scan guard preventing the raw key prefix from reappearing outside the owner.
- Verification: 63/63 frontend Node tests passed; lint, format check, and production build passed.


### F13 — editor preference ownership

- Added `frontend/src/lib/editorPreferences.ts` as the sole owner of editor mode and code-wrap localStorage keys/defaults.
- `NoteEditorView` no longer manipulates those raw keys directly.
- Added source-scan guard preventing both keys from reappearing outside the owner.
- Verification: 66/66 frontend Node tests passed; lint, format check, and production build passed.


### B22 — syndication limit ownership

- Moved RSS/sitemap max-item configuration ownership from controllers into `PublicBlogService`.
- `feedItems()` and `sitemapRefs()` are now complete read-model operations rather than parameterized leaks of HTTP-edge configuration.
- Controllers no longer use `@Value`; added an ArchUnit guard keeping configuration ownership below the HTTP edge.
- Verification: full backend suite passed before the test-only guard addition (292 tests, 0 failures/errors, 2 Docker-dependent skips); guard and affected controller tests passed afterward.


### F14 — parameterized client route ownership

- Migrated parameterized blog-detail, note-detail, and editor navigation to named Vue Router routes with params.
- Preserved numeric editor identifiers exactly as-is; whether those should be slugs instead is a separate API/product issue.
- Static entry links were deliberately not mass-rewritten in this batch because they do not duplicate dynamic path-construction logic.
- Added a source-scan guard preventing client-route template interpolation outside the API layer.
- Verification: 67/67 frontend Node tests passed; lint, format check, and production build passed.


### F15 — rich Markdown editor configuration ownership

- Extracted TipTap extension composition, Chinese-friendly Markdown input rules, task-list shortcuts, code-block input handling, and image paste/drop interception into `frontend/src/lib/richMarkdownEditor.ts`.
- `NoteEditorView` retains page lifecycle responsibilities: Markdown debounce synchronization, draft persistence, uploads, save/publish flow, and routing.
- Removed the redundant second `taskListShortcut` registration after verifying Tiptap input-rule aggregation semantics; the duplicate only produced a warning and had no independent behavior.
- Added a source-scan guard so rich-editor extension imports do not spread back into views.
- Verification: 68/68 frontend Node tests, lint, format check, production build, and a Playwright browser smoke test all passed. The smoke test used a non-conflicting port after discovering port 5173 was serving the unrelated main-workspace dev server.


### B23 — note metadata output parsing ownership

- Extracted fragile LLM output parsing from `NoteMetaService` into `NoteMetaParser`.
- The parser now owns code-fence stripping, JSON extraction, summary/tag length limits, tag cleanup, deduplication, and transient parse errors.
- `NoteMetaService` retains transaction orchestration and LLM invocation, and now uses the application-managed `ObjectMapper` through the parser instead of a hidden private mapper.
- Removed stale parsing constants and the old static parsing API after migrating tests to the canonical parser.
- Verification: full backend suite passed with 293 tests, 0 failures, 0 errors, and the same 2 expected Docker/Testcontainers skips.


### B24 — embedding vector contract ownership

- Added package-private `EmbeddingVector` as the sole owner of JSON-array conversion and the shared 1024-dimensional vector invariant.
- Migrated both Ollama and OpenAI embedding clients while preserving provider-specific endpoints, payload shapes, missing-response validation, and timeout behavior.
- Verification: full backend suite passed with 295 tests, 0 failures, 0 errors, and 2 expected Docker/Testcontainers skips.


### B25 — LLM HTTP client lifecycle

- `LlmStreamingClient` now reuses one thread-safe `HttpClient`, preserving the existing 20-second connect timeout and all request/stream parsing behavior while retaining connection pooling across calls.
- Verification: full backend suite passed.


### F16 — quick-capture draft key ownership guard

- The quick-capture draft key already had a single owner in `quickCaptureState.ts`; added a source-scan regression guard so views/components cannot bypass that owner with the raw session-storage key. No runtime behavior changed.
