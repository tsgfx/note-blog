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
