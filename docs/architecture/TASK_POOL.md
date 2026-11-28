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

### B2 — Canonical display date formatting ✅ completed

**Entropy:** ad-hoc ISO slicing and locale formatting across views.

**Target:** small date display utility with explicit formats, then migrate callers.

**Caution:** do not create a generic date framework; only canonicalize formats actually used.

**Result:** completed. Added `lib/date.ts` for the four ISO display formats actually used (`isoDate`, `isoYear`, compact and spaced month-day), migrated Blog/Home/Notes/NoteDetail call sites, and added unit tests. Verification: lint/format/build passed; 30 frontend Node tests passed.

### B3 — Public-blog read ownership ✅ completed

**Entropy:** `PublicBlogController`, `RssController`, and `SitemapController` directly query `NoteRepository`; public publication rules spread over web classes.

**Target:** one service-owned public read facade for posts/detail/stats/feed/sitemap data while keeping XML generation at the edge.

**Caution:** avoid shallow passthrough. The service must own publication filtering/query composition, not simply forward every repository method.

**Result:** completed. Added `PublicBlogService` as the public-content boundary for posts/detail/tags/related/stats/feed/sitemap and PV writes. RSS/sitemap controllers now consume service-owned read models instead of repositories/entities. `AccountController` display-name lookup moved into `AuthService`. Added ArchUnit rules: controllers may not depend on repositories, and only service/repository code may depend on repositories. Verification: full backend test suite passed, 273 tests, 0 failures/errors, 2 Docker-dependent skips.


### B4 — Canonical public URLs and XML escaping ✅ completed

**Entropy:** RSS and sitemap duplicated XML escaping and `site.base-url` trailing-slash normalization; notification article links repeated the same public URL construction semantics.

**Target:** one authority for public URL construction and one XML text escaping implementation, without conflating public article URLs with API endpoint URLs.

**Result:** completed. Added `config/SiteUrls` and `common/XmlEscape`; migrated RSS, sitemap, robots, and comment notification links. `InboxService` intentionally remains separate because it builds an API endpoint example, not a public article URL. Verification: full backend suite passed.


### B5 — Note catalog/write boundary ✅ completed

**Entropy:** `NoteService` mixed read-side catalog filtering and navigation aggregation with write lifecycle, publication state transitions, batch operations, and index side effects.

**Target:** extract one cohesive catalog read module without scattering transaction/index semantics.

**Result:** completed. Added `NoteCatalogService` for list filtering, folder/library/tag navigation, and admin detail reads. `NoteService` now owns write lifecycle, publish/unpublish, batch operations, and indexing side effects. `PublicBlogService` depends only on the catalog read service for filtered public posts. Verification: full backend suite passed, 276 tests, 0 failures/errors, 2 Docker-dependent skips.


### B6 — Frontend auth session ownership ✅ completed

**Entropy:** token/localStorage keys and auth-session clearing were duplicated across the Axios client, auth API, account API, router guard, login view, and ask state.

**Target:** one owner for browser auth-session persistence and lifecycle, without changing login/token semantics.

**Result:** completed. Added `lib/authSession.ts`; migrated all auth token reads/writes/clears. Stopped writing unused `notes_blog_user` while still removing that legacy key when the session is cleared. Added a structural Node test preventing auth storage keys from being reintroduced outside the owner module. Verification: lint/format/build passed; 32 frontend Node tests passed.


### B7 — Public reading-history ownership ✅ completed

**Entropy:** reading-history parsing and writing were implemented separately in `BlogView` and `NoteDetailView`, while recommendation ordering depends on one shared newest-first/deduplicated/capped contract.

**Target:** one owner for the local reading-history contract.

**Result:** completed. Added `lib/readHistory.ts`; migrated recommendation input and article-history recording. Corrupt or foreign stored data now consistently falls back to an empty history rather than leaking malformed values into recommendations. Added behavior tests and a structural ownership test for the storage key. Verification: lint/format/build passed; 35 frontend Node tests passed.


### B8 — Admin catalog paging/sorting ownership ✅ completed

**Entropy:** admin note-list paging bounds and sort mapping lived in `NoteController`, making the HTTP layer own catalog business semantics.

**Target:** `NoteCatalogService` owns admin-list paging limits and stable ordering; the controller only passes raw query parameters and handles the legacy `library` alias.

**Result:** completed. Added `adminList(...)` to `NoteCatalogService`; removed `PageRequest`/`Sort` assembly from `NoteController`. Controller tests now verify parameter pass-through, while new service tests own clamping, the 100-row cap, and deterministic sort fallbacks. Verification: full backend suite passed, 279 tests, 0 failures/errors, 2 Docker-dependent skips.


### B9 — Audit paging ownership and pagination anti-regression ✅ completed

**Entropy:** `AuditController` still assembled `PageRequest` and DTO mapping at the HTTP edge after the same pattern had been removed from the note catalog.

**Target:** services own pagination construction and query semantics; controllers remain HTTP edges. Prevent reintroduction with architecture tests.

**Result:** completed. `AuditService.list(page, size)` now owns safe page/size clamping, the 200-row cap, repository paging, and DTO mapping. `AuditController` only passes query parameters. ArchUnit now rejects controller dependencies on `PageRequest` and `Sort`. Verification: full backend suite passed, 283 tests, 0 failures/errors, 2 Docker-dependent skips.


### B10 — Editor draft persistence ownership ✅ completed

**Entropy:** `NoteEditorView` directly owned the draft storage key, JSON schema, malformed-data behavior, and storage failure handling in addition to editor orchestration.

**Target:** a small, testable owner for local editor-draft persistence while the view keeps user-facing orchestration (dirty checks, confirmation, Tiptap synchronization).

**Result:** completed. Added `lib/editorDraft.ts` and migrated draft write/load/clear. Malformed draft records now consistently read as absent instead of leaking partial shapes into restore logic. Added behavior tests and a structural ownership test for the storage key. Verification: lint/format/build passed; 38 frontend Node tests passed.


### B11 — Admin tag-option ordering ✅ completed

**Entropy:** the large `NotesView` embedded the tag filter's ordering rules—selected tags first, search ignores the collapsed cap, expanded shows all—without any direct behavioral test.

**Target:** extract one cohesive presentation rule into a small testable module while leaving data loading and view state in `NotesView`.

**Result:** completed. Added `lib/tagOptions.ts` with behavior-preserving `visibleTagOptions`; `NotesView` now delegates the computed ordering. Added Node tests for selected-first, query matching, expansion, and collapsed hot-order behavior. Verification: lint/format/build passed; 42 frontend Node tests passed.


### B12 — Admin note-folder labels ✅ completed

**Entropy:** `NotesView` embedded the relative-folder display rules for new notes, root notes, nested paths, active-path collapse, and the special “本目录” case without direct tests.

**Target:** isolate the deterministic folder-label rule from list loading, selection, batch operations, and rendering.

**Result:** completed. Added `lib/noteFolder.ts`; `NotesView` now adapts `NoteSummary` plus active path to that rule. Added tests for root/new/nested/relative/outside-scope cases. Verification: lint/format/build passed; 46 frontend Node tests passed.


### B13 — Resizable width controller ✅ completed

**Entropy:** `NotesView` and `AskDrawer` independently duplicated pointer-drag resizing, viewport clamping, body drag state, persistence, restore, reset, and listener cleanup while differing only in direction/bounds/CSS application.

**Target:** one canonical framework-independent controller for drag-width lifecycle; callers declare measurement direction, bounds, breakpoint, storage key, and how width is applied.

**Result:** completed. Added `lib/resizableWidth.ts` and migrated both callers. The controller does not duplicate caller-owned default widths; `null` represents “use the caller’s default”. It also normalizes storage failure handling and unmount cleanup. Added Node tests for drag/clamp/persist/cleanup, restore/reset, and small-viewport behavior. Verification: lint/format/build passed; 49 frontend Node tests passed.


### B14 — Publish-preflight dialog boundary ✅ completed

**Entropy:** `NotesView` mixed list loading/selection/batch orchestration with the complete preflight-dialog lifecycle: exclusion state, warning grouping, publishable-id calculation, keyboard close behavior, template, and modal styling.

**Target:** the dialog owns its UI decision workflow; the parent owns only fetching preflight data and executing the confirmed batch publish. Pure publishability rules remain directly testable outside Vue.

**Result:** completed. Added `NotePreflightDialog.vue` and `lib/preflight.ts`. The dialog now owns exclusion state, warning exclusion, publishable count, Escape close, rendering, and scoped modal styles; `NotesView` shrank by about 330 lines and receives only the final confirmed id list. Added pure tests for blocker/warning/publishable rules. Verification: lint/format/build passed; 52 frontend Node tests passed.


### B15 — Rate-limit configuration ownership ✅ completed

**Entropy:** five rate-limit values were bound through scattered `@Value` fields whose defaults duplicated `application.yml`; the codebase already documented a real incident risk from these two sources drifting.

**Target:** one typed configuration contract for rate limits, with `application.yml` as the sole owner of environment-variable mapping and defaults.

**Result:** completed. Added `RateLimitProperties` and constructor-injected it into `RateLimitConfig`; removed all five duplicated `@Value` fields. `@EnableConfigurationProperties` keeps WebMvcTest slices working. Added a Binder contract test for canonical kebab-case keys. Verification: full backend suite passed, 284 tests, 0 failures/errors, 2 Docker-dependent skips.


### B16 — Embedding probe configuration ownership ✅ completed

**Entropy:** both embedding health probes bypassed the already-canonical typed `NotesProperties` and re-read `notes.embedding.*` through scattered `@Value` strings, duplicating configuration ownership and null/default semantics.

**Target:** one typed owner for embedding configuration, with probes consuming the same object as the embedding clients.

**Result:** completed. `OllamaProbe` and `OpenAiEmbeddingProbe` now constructor-inject `NotesProperties` while preserving their monitoring degradation semantics. Added local HTTP-server behavior tests proving canonical base-url normalization, endpoint selection, auth header propagation, and failure containment. Verification: full backend suite passed, 287 tests, 0 failures/errors, 2 Docker-dependent skips.


### B17 — Public site metadata ownership ✅ completed

**Entropy:** `site.title` was configured once but consumed through two independent paths: RSS injected it directly while notification copy hardcoded the same brand string. `NotificationService` also carried several string-base-url test constructors that bypassed the canonical `SiteUrls` boundary.

**Target:** one canonical owner for public site metadata and URL construction, with consumers depending on that boundary rather than raw configuration or copied brand text.

**Result:** completed. `SiteUrls` now owns the public site title alongside canonical URLs; RSS and notification payloads consume `siteUrls.title()`. Notification test-only constructors now take `SiteUrls` directly, removing the parallel string normalization path. Bark grouping semantics were deliberately left unchanged because they are not the same concept as the site title. Verification: full backend suite passed, 287 tests, 0 failures/errors, 2 Docker-dependent skips.


### B18 — Inbox route contract ownership ✅ completed

**Entropy:** the quick-capture route was independently repeated by the HTTP mapping, security permit rule, and generated integration example. Changing the endpoint required synchronized edits across layers, while `InboxService` also re-read and normalized `site.base-url` outside the canonical `SiteUrls` owner.

**Target:** one shared compile-time route contract for cross-layer API paths and one canonical owner for the external site base URL.

**Result:** completed. Added scoped `ApiRoutes.INBOX_QUICK_CAPTURE`; controller, security, and configuration examples now consume the same contract. `InboxService` uses `SiteUrls` instead of its own `@Value` normalization. Verification: full backend suite passed, 287 tests, 0 failures/errors, 2 Docker-dependent skips.


### B19 — Notification payload formatting ownership ✅ completed

**Entropy:** `NotificationService` mixed two unrelated responsibilities: asynchronous webhook transport side effects and provider-specific payload adaptation. The formatting logic was exposed as a public service method only so tests could reach it, leaking an internal implementation detail through the service interface.

**Target:** transport ownership in `NotificationService`; webhook-provider schema ownership in an internal formatter with direct behavior tests.

**Result:** completed. Added package-private `NotificationPayloadFormatter`, moved all provider detection/schema generation there, removed the leaked public `buildPayloadJson` service method, and migrated payload tests to the formatter. `NotificationService` no longer stores an unused ObjectMapper and now owns only enablement, URL construction, async execution, transport, and failure containment. Also corrected the disabled-webhook test to assert against the actual synchronous transport method. Verification: full backend suite passed, 287 tests, 0 failures/errors, 2 Docker-dependent skips.


### B20 — Shared note series taxonomy ✅ completed

**Entropy:** `BacklinkService` and `RelatedNoteService` each implemented the same domain rule for interpreting `专栏:` / `系列:` tags, while Backlink additionally owned a separate folder fallback. The duplicate definition could drift even though both services rely on the same taxonomy concept.

**Result:** completed. Added package-private `NoteTaxonomy.seriesName` as the single owner of series-tag interpretation. Both services migrated; Backlink retains its distinct folder fallback. Added direct taxonomy tests. Verification: full backend suite passed, 289 tests, 0 failures/errors, 2 Docker-dependent skips.


### B21 — Transactional side-effect scheduling ✅ completed

**Entropy:** Note and inbox write flows each copied the same Spring synchronization boilerplate to defer index/cache side effects until after commit. The duplicated pattern hides a critical invariant: async work must never read uncommitted note state.

**Result:** completed. Added `TransactionSideEffects.afterCommitOrNow` as the single scheduling implementation and migrated all three call sites. Domain comments explaining why indexing/invalidation must happen after commit remain at the business call sites. Verification: full backend suite passed, 291 tests, 0 failures/errors, 2 Docker-dependent skips.


### E3 — Transaction synchronization guardrail ✅ completed

**Entropy risk:** B21 canonicalized post-commit side-effect scheduling, but nothing prevented future services from re-copying Spring `TransactionSynchronizationManager` boilerplate and bypassing the invariant.

**Result:** completed. Added an ArchUnit rule making `TransactionSideEffects` the sole production-code owner of transaction synchronization mechanics. Verification: full backend suite passed, 292 tests, 0 failures/errors, 2 Docker-dependent skips.


### B22 — Syndication limit ownership ✅ completed

**Entropy:** RSS and sitemap item limits were injected and applied at the HTTP controller edge even though feed/sitemap size is part of the public content read-model boundary. Controllers also still carried configuration ownership after earlier read-model extraction.

**Result:** completed. `PublicBlogService` now owns `app.rss.max-items` and `app.sitemap.max-items`, exposes no-arg `feedItems()` / `sitemapRefs()`, and controllers only serialize XML. Defaults and behavior are unchanged. Added an ArchUnit guard preventing controllers from depending on `@Value`, making configuration ownership below the HTTP edge machine-enforceable. Verification: full backend suite passed before the test-only guard addition (292 tests, 0 failures/errors, 2 Docker-dependent skips); guard and affected controller tests passed afterward.

### F14 — Parameterized client route ownership ✅ completed

**Result:** completed. Parameterized blog-detail, note-detail, and editor navigation now uses named Vue Router routes with params instead of duplicated template-string path construction. Static entry literals were intentionally left unchanged; numeric editor identifiers were preserved exactly because their ID-vs-slug semantics are a separate product/API question. Added a source-scan guard against parameterized client-route interpolation outside the API layer. Verification: 67/67 frontend Node tests, lint, format check, and production build passed.

### F15 — Rich Markdown editor configuration ownership ✅ completed

**Result:** completed. `lib/richMarkdownEditor.ts` now owns TipTap extension composition, Chinese-friendly Markdown input rules, task-list shortcuts, code-block input handling, and image paste/drop interception. `NoteEditorView` retains page lifecycle, draft persistence, upload, save/publish, and routing. Removed the redundant duplicate `taskListShortcut` registration after verifying it had no independent input-rule semantics. Added an ownership scan and browser smoke verification. Verification: 68/68 frontend Node tests, lint, format check, production build, and Playwright editor smoke passed.

### B23 — Note metadata output parsing ownership ✅ completed

**Result:** completed. `NoteMetaParser` now owns fragile LLM output parsing: code-fence stripping, JSON extraction, summary/tag truncation, tag cleanup, deduplication, count limits, and transient parse errors. `NoteMetaService` retains transaction orchestration and LLM invocation. Removed the hidden private `new ObjectMapper()` and stale parsing constants. Verification: full backend suite passed with 293 tests, 0 failures, 0 errors, 2 expected Docker/Testcontainers skips.

### B24 — Embedding vector contract ownership ✅ completed

**Result:** completed. `EmbeddingVector` now owns conversion from provider-specific JSON arrays to the shared 1024-dimensional vector contract. Ollama and OpenAI clients keep their provider-specific endpoints, payload shapes, missing-response validation, and timeout semantics; only the duplicated vector conversion/dimension invariant was canonicalized. Verification: full backend suite passed with 295 tests, 0 failures, 0 errors, 2 expected skips.

## Research batches

### R1 — Remaining NoteService responsibility boundaries

Catalog/read navigation has been extracted. Remaining seams: write lifecycle vs publishing lifecycle vs indexing orchestration/batch operations. Must map transaction synchronization and async indexing callers before any further split.

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

### R6 — HTTP client lifecycle and timeout ownership

`LlmStreamingClient` constructs a new `HttpClient` per streaming call while probes/notification services retain injected or field-level clients. Before changing behavior, map connection reuse, timeout, and test-injection requirements across all external HTTP callers. Do not introduce a generic HTTP utility unless it encapsulates real shared semantics rather than hiding provider differences.

## Blocked / pending product decisions

- Whether the uncommitted visual redesign in the main checkout should be merged into this governance line later. Governance work currently treats it as a separate product branch.
- Whether public read model DTOs can be optimized/shrunk. Any response-shape change is an external contract decision.
- Whether `library` legacy query compatibility can eventually be removed. Current comments indicate real bookmark compatibility, so retain.
- Code-highlight language policy: the editor offers `dockerfile`, `nginx`, and `vue`, but the reader currently uses `highlight.js/lib/common`; `dockerfile`/`nginx` are not registered and `vue` has no upstream highlight.js language module. Decide whether to register additional grammars, alias Vue to HTML/XML, or remove misleading editor options. Do not mix this behavior-visible decision into a pure refactor.
- Editor links sometimes use numeric note IDs (`quickCaptureState`, preflight dialog, settings integrity list) even though the editor route parameter is named `slug` and note detail lookup is slug-based. Determine whether this is an intentional backend-compatible identifier or an existing broken-link bug before changing behavior.
