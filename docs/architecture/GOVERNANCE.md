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
