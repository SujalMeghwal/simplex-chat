{-# LANGUAGE QuasiQuotes #-}

module Simplex.Chat.Store.SQLite.Migrations.M20260701_download_manager where

import Database.SQLite.Simple (Query)
import Database.SQLite.Simple.QQ (sql)

-- All tables/columns below are local-device-only: file_hash/file_mime are
-- computed after download and never referenced by agent/SMP/XFTP protocol
-- code, and local_* tables are not part of any export/sync payload.
m20260701_download_manager :: Query
m20260701_download_manager =
  [sql|
ALTER TABLE files ADD COLUMN file_hash TEXT; -- SHA-256 of decrypted content, computed once after RcvComplete, local dedup only
ALTER TABLE files ADD COLUMN file_mime TEXT; -- derived at download time, avoids re-deriving type from file_name on every list render

CREATE INDEX idx_files_file_hash ON files (user_id, file_hash);
CREATE INDEX idx_files_file_name ON files (user_id, file_name);

CREATE TABLE local_file_collections (
  collection_id INTEGER PRIMARY KEY,
  user_id INTEGER NOT NULL REFERENCES users ON DELETE CASCADE,
  collection_name TEXT NOT NULL,
  created_at TEXT NOT NULL DEFAULT (datetime('now'))
) STRICT;

CREATE TABLE local_file_collection_members (
  collection_id INTEGER NOT NULL REFERENCES local_file_collections ON DELETE CASCADE,
  file_id INTEGER NOT NULL REFERENCES files ON DELETE CASCADE,
  added_at TEXT NOT NULL DEFAULT (datetime('now')),
  PRIMARY KEY (collection_id, file_id)
) WITHOUT ROWID, STRICT;

CREATE TABLE local_file_favorites (
  file_id INTEGER PRIMARY KEY REFERENCES files ON DELETE CASCADE,
  favorited_at TEXT NOT NULL DEFAULT (datetime('now'))
) STRICT;

CREATE TABLE local_download_stats (
  stat_date TEXT NOT NULL, -- yyyy-mm-dd, device-local calendar day
  user_id INTEGER NOT NULL REFERENCES users ON DELETE CASCADE,
  files_downloaded INTEGER NOT NULL DEFAULT 0,
  bytes_downloaded INTEGER NOT NULL DEFAULT 0,
  bytes_deduped INTEGER NOT NULL DEFAULT 0, -- storage reclaimed by dedup cleanup that day
  PRIMARY KEY (stat_date, user_id)
) WITHOUT ROWID, STRICT;
|]

down_m20260701_download_manager :: Query
down_m20260701_download_manager =
  [sql|
DROP TABLE local_download_stats;
DROP TABLE local_file_favorites;
DROP TABLE local_file_collection_members;
DROP TABLE local_file_collections;

DROP INDEX idx_files_file_name;
DROP INDEX idx_files_file_hash;

ALTER TABLE files DROP COLUMN file_mime;
ALTER TABLE files DROP COLUMN file_hash;
|]
