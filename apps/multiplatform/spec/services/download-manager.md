# Unified Download Manager — status and deferred work

Local-only feature: browse/search/filter/sort every downloaded and pending file across every
chat in one screen, with dedup, favorites/collections, per-chat storage budgets, and storage
insights. Nothing in this feature makes a network call, syncs to another device, or is part of
any export/backup payload unless explicitly noted below.

Entry point: Settings → Chat database → "Download manager".

## Built and compiler-verified (both Kotlin and Haskell, real builds)

- **DB migration** — `src/Simplex/Chat/Store/SQLite/Migrations/M20260701_download_manager.hs`
  (+ Postgres equivalent in `Store/Postgres/Migrations/`). Adds `files.file_hash`/`file_mime`,
  and local-only tables `local_file_favorites`, `local_file_collections`,
  `local_file_collection_members`, `local_download_stats` (stats table created but not yet
  written to — see deferred section).
- **Cross-chat file model** — `apps/multiplatform/common/.../views/downloads/DownloadManagerModel.kt`.
  Aggregates images/videos/voice/files across every chat via existing `apiGetChat` pagination.
- **UI** — `.../views/downloads/DownloadManagerView.kt`. Four tabs:
  - **Browse**: search, category/status/favorite filters, sort, grid/list toggle, multi-select
    batch bar (download / favorite / delete-local-only-with-confirm / clear).
  - **Duplicates**: SHA-256 groups, one-click cleanup per group or all (with confirm dialog).
  - **Storage**: usage by category + per-chat storage budgets (set/clear a MB cap per chat;
    auto-enforced oldest-first on every screen open).
  - **Insights**: storage health score (heuristic: dup ratio + old-file ratio), file age
    distribution, largest local file.
- **Favorites/collections backend** — real command pair (`ApiGetFileVault`,
  `ApiToggleFileFavorite`, `ApiAddFileToCollection`, `ApiRemoveFileFromCollection`,
  `ApiDeleteFileCollection`), persisted inside `chat.db` (`FileVault`/`FileCollectionEntry` in
  `Types.hs`, functions in `Store/Shared.hs`, commands in `Controller.hs`/`Library/Commands.hs`).
  Same SQLCipher tier as the rest of the app — not a separate plaintext file (that was tried
  first and deliberately replaced for the security downgrade it represented).
- **File hash persistence** — `Simplex.Chat.Util.hashFile` (hashes plaintext content via
  `CF.readFile`, which decrypts transparently if local-file encryption is on — never hashes
  ciphertext). Wired into both rcv-file-complete paths in `Library/Subscriber.hs` (XFTP `RFDONE`
  and SMP inline `RcvChunkFinal`). Best-effort — a hash failure never blocks the download.
- **Auto-duplicate notice** — `CEvtRcvFileDuplicate` (new `ChatEvent`), fires right after a
  download completes if its hash matches an existing file; Kotlin shows a toast.
- **Storage budgets** — `ChatStorageBudgets` in `DownloadManagerModel.kt`. Deliberately
  **not** DB-backed like favorites — a byte threshold per chat isn't sensitive metadata the way
  "which files did you star" is, so plaintext-local (same tier as `themes.yaml`) is an
  appropriate choice here, not a downgrade.

Toolchain note: this machine had no Haskell toolchain at session start. Installed GHC 9.6.3 +
cabal 3.16.1.0 + MSYS2 (OpenSSL, libpq) at `C:\ghcup` — permanent, reusable for future work on
this fork. 11 real compiler-caught bugs were fixed along the way (missing exhaustive-match
branches, `DuplicateRecordFields` ambiguity, missing pragmas/exports) — none of this was
guessed, all builds are real `cabal build`/`gradlew compileKotlinDesktop` passes.

## Deferred — needs a design decision before building

### OCR / full-text search inside file content
Currently search only matches filename/sender/chat name — not what's actually *in* a file.
Highest-value remaining feature (turns search from cosmetic into actually useful — "find that
screenshot with the wifi password"), but needs upfront decisions:
- OCR engine for images/screenshots (Tesseract is the obvious offline choice — need to check
  JVM bindings that work across desktop+Android, licensing, and binary size impact).
- PDF/DOCX text extraction library (offline only).
- Where the extracted text is indexed — needs a new local-only DB table/column, and a decision
  on whether extraction runs synchronously on download-complete or as a background sweep (large
  existing libraries would need a one-time backfill pass).
- Extracted text is itself sensitive (OCR'd screenshot content) — must be scoped with the same
  care as favorites (DB-backed, not plaintext-local).

### PQ-encrypted export
From the original spec: select files/collections, export as a single password-protected,
quantum-resistant-encrypted archive. Needs decisions before implementation:
- Archive format (zip vs tar, whether to strip EXIF/metadata by default).
- KEM choice — SimpleX core already ships a PQ hybrid ratchet for E2E messaging
  (`src/Simplex/Messaging/Crypto/Ratchet*`); reusing those primitives vs a separate
  export-specific scheme needs a decision.
- KDF parameters for password → key (Argon2id is the reasonable default, but cost parameters
  need picking).
- UI flow for the password prompt (never persisted, obviously) and progress for large exports.

### Smaller, not yet built
- Batch rename/compress/encrypt/share (only delete/download/favorite are wired into the batch
  bar today).
- Bandwidth-aware download scheduling ("only auto-download large files on Wi-Fi").
- Recently-viewed sort (needs a "last opened" touch on gallery view, not yet wired).
- Drag-and-drop file organization (long-press "add to collection" menu would be the realistic
  desktop+mobile-compatible version of this).
- `local_download_stats` table exists in the schema but nothing writes to it yet — "downloaded
  today/this week", bandwidth-used, and the storage forecast from the original spec all depend
  on this being populated.
- Postgres schema dump (`Store/Postgres/Migrations/chat_schema.sql`) was not hand-updated for
  the new tables — that file is pg_dump output normally regenerated from a live DB, not
  hand-edited; the actual migration `.hs` file is what's compiler-verified and correct.
