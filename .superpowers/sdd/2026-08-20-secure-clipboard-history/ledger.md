# SDD ledger — plan: docs/superpowers/plans/2026-08-20-secure-clipboard-history.md

## Workspace

- Worktree: `/mnt/c/users/jefe/documents/github/jefe-keyboard/.worktrees/keyboard-clipboard`
- Branch: `codex/keyboard-clipboard`
- Plan dependency: all six tasks in `2026-08-20-keyboard-rail-feedback.md` must be complete and approved before clipboard Task 1 dispatch.
- Design/spec baseline: `1b44e170316a4c6a222136a2ef5c7c08c618245c`.

## Preflight — self consistency

| Task | Required input is produced earlier | RED before production | Exact gate/commit | Finding |
|---|---|---|---|---|
| 1 — build/domain foundation | Rail plan complete; current Gradle project | Yes | Yes | Consistent; introduces Room/KSP and bounded metadata-only domain |
| 2 — gateway/policy | Task 1 limits/models and rail privacy Boolean | Yes | Yes | Consistent; defensive snapshot is redacted and opens no URI |
| 3 — crypto/manifest | Tasks 1–2 domain types | JVM + device RED | Yes | Consistent; stable same-fd two-pass verification and streaming fingerprint are explicit |
| 4 — Room schema/DAO | Tasks 1 and 3 enums/envelopes | Device RED | Yes | Consistent; all five storage states and container schema are defined |
| 5 — blob store/ingestion | Tasks 1–4 crypto, policy and DAO-side capacity contract | Yes | Yes | Consistent; one preallocated container, per-URI MIME, one source read |
| 6 — repository/payload reader | Tasks 3–5 store/schema/crypto | JVM + device RED | Yes | Consistent; quotas, copy-on-write promotion, revocation barrier and reconciliation are explicit |
| 7 — opt-in/FIFO/component | Tasks 2, 5, 6 and rail privacy | Yes | Yes | Consistent; one application-scoped restartable controller, active+queued capacity 32 |
| 8 — grants/provider/paste | Tasks 3, 6, 7 and editor session | JVM + inter-UID device RED | Yes | Consistent; authenticated resolved payload owns MIME/size/order; API 24/25+ split explicit |
| 9 — panel/search/consent | Tasks 6–8 and completed rail root/theme | Yes | Yes | Consistent; controller owns plaintext lifetime; IME and management activity modes differ explicitly |
| 10 — prompt/countdown | Tasks 6–9 and rail priority | Yes | Yes | Consistent; timer measures only effective visible state and schedules its own expiry |
| 11 — sensitive provenance | Tasks 6, 8, 10 and rail suggestion gate | JVM + device RED | Yes | Consistent; HMAC exact match fails closed and session taint persists |
| 12 — settings/admin/backup | Tasks 7, 9, 11 | Yes | Yes | Consistent; durable clearing/disabling/reset states precede destructive cleanup |
| 13 — real-device matrix/final gate | All prior tasks | Architecture + device RED | Yes | Consistent; API 24/34, process-death phases, artifact and visual inspection are explicit |

## Preflight — shared files and interfaces

| Pair / sequence | Shared surface | Ruling before dispatch |
|---|---|---|
| Rail 1/4/5/6 → Clipboard 2/7/9/10/11 | Privacy state, root/rail states, suggestion gate, Ink tokens | Clipboard extends the approved rail contracts; it must not redefine or bypass them |
| 1 → 2–13 | Limits, IDs, kinds, storage states, failures | Single ownership in Task 1; later tasks consume constants and safe errors |
| 2 → 5/7 | Snapshot, accepted representations, sensitivity, typed source-change marker | Policy never opens/persists; ingestor/controller own those later actions. Task 7 persists the pre-clear marker and only clears suppression on `DEFINITELY_CHANGED`; equal API 31+ timestamps are `SAME_OR_COLLIDING`, and unknown evidence fails closed. |
| 3 → 5/6/8/11 | Crypto, AAD, manifest, stable snapshots, HMAC builder | Later tasks must use authenticated selectors/frames and never create alternate plaintext paths |
| 4 → 6 | Entity/DAO transaction primitives | Repository owns state transitions and quotas; UI/controller never call DAO directly |
| 5 ↔ 6 | Store, space manager, repository reclaimer | Construction order avoids cycles: low-level store first, repository, then space manager/ingestor |
| 6 → 7/9/10/11/12 | Repository/events/payload reader/revocation hub | All presentation and orchestration consume the interface; Room stays hidden |
| 7 → 8/9/10/12 | Single component/controller/admin runtime | Settings/service/panel share the same controller; no second listener/FIFO is permitted |
| 8 → 9/10/11 | Paste results, session identity, grants/provider | UI/service pass current authoritative target; no package-derived UID or stale connection |
| 9 → 10 | Tile models and panel/root modes | Prompt uses safe loaded metadata; panel/search pauses prompt visibility without expiring it |
| 10 → 11 | Prompt and paste success sensitivity | Sensitive paste dismisses/masks prompt and taints the session gate |
| 11 → 12 | KEY_UNAVAILABLE and sensitive promotion | Settings exposes reset only for typed key failure and never auto-erases |
| 12 → 13 | Backup rules and crash-safe admin states | Final tests inspect packaged exclusions and resume clear/reset after real process death |

## Preflight rulings

- Execute Tasks 1–13 serially with a fresh implementer and a separate reviewer for every task.
- Do not dispatch Clipboard Task 1 until Rail Tasks 1–6 are fully reviewed and the rail worktree is clean.
- Instrumented RED commands are required where specified. If no emulator/device is available locally, the implementer must still compile the androidTest target, record the infrastructure limitation precisely, and Task 13/CI remains the non-waivable real-device gate.
- No field carrying plaintext may use generated `data class.toString`; provider metadata must be derived from an authenticated resolved payload, never caller-supplied MIME/size/order.
- State transitions involving removal/promotion must cross `REVOKING` and await the shared revocation barrier before deletion/publication; admin operations persist their durable state before stopping capture or deleting.
- No unresolved P0/P1 or missing dependency remains after the final plan consistency review.

## Task log

| Task | Base | Head | Implementer | Review | Verification | Status |
|---|---|---|---|---|---|---|
| 1 | `d45fb67` | `6f3718e` | `clipboard_task1_impl` | APPROVED — spec/quality/security PASS, no findings | Reviewer: 161 tests, full unit/lint/assemble + androidTest assemble + KSP probe PASS | complete |
| 2 | `6f3718e` | `4ca220b` | `clipboard_task2_impl` | review pending | RED unresolved gateway/policy; focused + full unit/lint/assemble PASS | in review |
| 3 | — | — | — | — | — | pending |
| 4 | — | — | — | — | — | pending |
| 5 | — | — | — | — | — | pending |
| 6 | — | — | — | — | — | pending |
| 7 | — | — | — | — | — | pending |
| 8 | — | — | — | — | — | pending |
| 9 | — | — | — | — | — | pending |
| 10 | — | — | — | — | — | pending |
| 11 | — | — | — | — | — | pending |
| 12 | — | — | — | — | — | pending |
| 13 | — | — | — | — | — | pending |
