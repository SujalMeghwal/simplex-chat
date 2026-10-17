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
- **Storage budgets** — `ChatStorageBudgets` in `DownloadManagerModel.kt`, stored in the
  `local_chat_storage_budgets` table inside encrypted `chat.db` (migration
  `M20260702_download_manager_v2`; originally a plaintext JSON file, moved for consistency).
- **File verification** — the core checks every received group/channel file against the
  digest its sender declared (upstream v7). Files that pass are listed in
  `FileVault.verifiedFileIds` and shown with a verified badge; duplicate cleanup keeps a
  verified copy. Files that fail are never retried (`FileError.retryable`).
- **Delete files older than N days** — done by the core (`/_remove local files older than N`):
  deletes the local copy, clears its path, local encryption key and dedup hash, and marks
  received files as no longer available. Favorites, collection members and saved notes are kept.

Toolchain note: development moved to Linux (Oct 2026). Build the Haskell core with
`scripts/desktop/build-lib-linux.sh` (GHC 9.6.3 + cabal via ghcup; needs libssl-dev, libgmp-dev,
zlib1g-dev, patchelf), then run the desktop app with `./launch-fork.sh` from the repo root.
The earlier Windows setup (GHC + MSYS2 at `C:\ghcup`, pre-built DLLs from the installed app)
is no longer used.

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
