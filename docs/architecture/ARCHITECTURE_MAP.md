# Architecture Map — Phase 0 Census

> Snapshot: `arch/governance` worktree, based on `6fb9469` plus governance docs.
> This document records the system as it is, not an aspirational redesign.

## 1. Product truth

一隅博客 is a private note system and public blog with three core flows:

1. **查 / read & retrieve**: keyword + semantic search, AI Q&A, related notes, backlinks, recommendations.
2. **写 / write & manage**: Markdown editor, draft autosave, folders/libraries, tags, assets, publish/republish.
3. **发布 / public blog**: public posts, comments, RSS, sitemap, stats, recommendations, SEO-facing routes.

Hard external contracts that must not be broken:

- public article URLs `/blog/{slug}` and backend `/api/blog/**` responses;
- RSS `/api/blog/rss.xml` plus feed aliases;
- sitemap and robots behavior;
- admin authentication and token invalidation semantics;
- ICP text/links and deployment topology;
- MySQL schema is explicit (`db/schema.sql`), Hibernate does not own DDL;
- vector dimensionality is tied to `bge-m3` / `VECTOR(1024)`.

## 2. Runtime and module topology

```text
browser SPA (Vue 3)
  ├─ public reading routes: /, /blog, /blog/:slug
  ├─ admin routes: /notes, /editor, /settings, /comments, /audit...
  └─ /api ──nginx/vite proxy──> Spring Boot
                                  ├─ controller: HTTP/query validation + response wrapping
                                  ├─ service: business rules, indexing, AI clients, publishing
                                  ├─ repository: Spring Data JPA / JDBC persistence
                                  ├─ domain: JPA entities and enums
                                  ├─ dto: API-shaped response/request records/classes
                                  ├─ config: security, cache, rate limit, schema upgrade
                                  └─ common: ApiResponse, errors, client IP
                                       │
                                       ├─ MySQL 9 (+ VECTOR(1024))
                                       ├─ Ollama or OpenAI-compatible embeddings
                                       ├─ OpenAI-compatible LLM streaming endpoint
                                       └─ local asset storage / backup directory / webhook
```

## 3. Size and ownership census

Backend production code:

| package | files | lines | observed role |
|---|---:|---:|---|
| `service` | 38 | 4852 | most business complexity |
| `controller` | 16 | 1500 | REST endpoints, some business/data-access leakage |
| `config` | 11 | 982 | security, rate limiting, schema bootstrap |
| `dto` | 33 | 749 | API contracts |
| `repository` | 7 | 403 | persistence queries |
| `domain` | 9 | 363 | entities/enums |
| `common` | 4 | 163 | response/error infrastructure |

Largest backend complexity centers:

- `NoteService` (540 lines): list/filter, CRUD, publishing, folder/library/tag aggregation, indexing orchestration.
- `SchemaUpgrade` (300 lines): startup schema compatibility.
- `RecommendationService` (297), `RelatedNoteService` (267): vector/behavioral recommendation logic.
- `NoteRepository` (250 lines): complex JPQL/native queries.
- `InboxService`, `CommentService`, `NoteMetaService`: external input + LLM/index side effects.

Frontend complexity centers:

- `NotesView.vue` (~1949 lines)
- `HomeView.vue` (~1653 lines in this snapshot)
- `NoteDetailView.vue` (~1499)
- `NoteEditorView.vue` (~1490)
- `AskDrawer.vue` (~1070)
- `SettingsView.vue` (~1021)
- `BlogView.vue` (~935)

These files mix route orchestration, remote state, local UI state, formatting, and domain display rules. They are the largest frontend entropy sources, but refactoring them must be behavior-preserving and incremental.

## 4. Current dependency observations

### Good constraints already present

- Spring controllers generally use constructor injection via Lombok.
- DB schema is explicit and tested by startup smoke tests.
- `ApiResponse` provides a canonical backend response envelope.
- Frontend `api/client.ts` already has canonical `get/post/patch/del` helpers.
- CI has backend tests, frontend lint/build, and real-stack Playwright smoke tests.
- No frontend component library parallel system; design tokens are centralized in `style.css` in this snapshot.

### Cross-layer violations / leaks

Initially controllers imported repositories directly (`AccountController -> UserRepository`, public blog/RSS/sitemap controllers -> `NoteRepository`). Batch B3 removed these violations: controllers no longer import repositories, and `LayeringArchitectureTest` now fails the build if that coupling returns.

### Duplicate implementations already confirmed

- XML escaping duplicated in `RssController` and `SitemapController`.
- trailing-slash normalization of `site.base-url` duplicated in `RssController` and `SitemapController`.
- date formatting/slicing duplicated across `BlogView`, `HomeView`, `NotesView`, `NoteDetailView`, `SettingsView`, `ShareQuoteModal`.
- `library` / `path` compatibility exists intentionally in backend and frontend API comments; it is a contract compatibility layer, not accidental duplication, but its ownership should remain explicit.

### Configuration observations

Configuration is environment-driven and documented, but ownership is mixed:

- typed properties exist for `notes.*` and `security.*`;
- many `app.*` and `site.*` values use scattered `@Value` injection;
- defaults are embedded in YAML and sometimes repeated in `.env.example` / compose docs.

This is not yet a confirmed defect because Spring Boot YAML plus env interpolation is a valid single runtime source. It becomes entropy only when defaults or semantics diverge; needs a config contract inventory before changing.

## 5. Verification baseline

Phase 0 baseline in this worktree:

- `cd backend && mvn test -q` exited `0`.
  - Log contains expected warnings/stack traces from negative-path tests; exit code is authoritative.
- Initial frontend commands failed with `eslint/prettier/vue-tsc: command not found` because the fresh worktree had no `node_modules`; this was environment setup, not a code failure. After `npm ci`, all baseline checks passed:
  - `npm run lint` exit `0`;
  - `npm run format:check` exit `0`;
  - `npm run build` exit `0`;
  - `node --experimental-strip-types --test test/*.test.ts` exit `0` (28 tests).

## 6. Architectural risk ranking

High value / high caution:

1. Splitting or clarifying `NoteService` responsibilities without breaking publication/index side effects.
2. Reducing giant Vue views into domain-oriented composables/components while preserving exact UX behavior.
4. Configuration ownership and deployment contract normalization.

High value / low risk first batches:

1. Canonicalize frontend API unwrapping through `api/client.ts` and delete duplicated manual unwrap code.
2. Canonicalize date formatting helpers and migrate all display call sites.
3. Clarify `NoteService` responsibility boundaries before extracting larger write/publish modules.
4. Canonicalize site URL normalization and XML escaping where RSS/sitemap share semantics.

Do not start with:

- broad folder moves;
- rewrites of editor or rendering pipeline;
- changing public API response shapes;
- merging semantically different recommendation/search services just because they both use vectors.
