{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE QuasiQuotes #-}

module Simplex.Chat.Store.Postgres.Migrations.M20260701_download_manager where

import Data.Text (Text)
import Text.RawString.QQ (r)

-- All tables/columns below are local-device-only: file_hash/file_mime are computed after download
-- and never referenced by agent/SMP/XFTP protocol code, and local_* tables are not part of any
-- export/sync payload. Mirrors Store/SQLite/Migrations/M20260701_download_manager.hs.
m20260701_download_manager :: Text
m20260701_download_manager =
  [r|
ALTER TABLE files ADD COLUMN file_hash TEXT;
ALTER TABLE files ADD COLUMN file_mime TEXT;

CREATE INDEX idx_files_file_hash ON files (user_id, file_hash);
CREATE INDEX idx_files_file_name ON files (user_id, file_name);

CREATE TABLE local_file_collections (
  collection_id BIGINT PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
  user_id BIGINT NOT NULL REFERENCES users ON DELETE CASCADE,
  collection_name TEXT NOT NULL,
  created_at TEXT NOT NULL DEFAULT (now())
);

CREATE TABLE local_file_collection_members (
  collection_id BIGINT NOT NULL REFERENCES local_file_collections ON DELETE CASCADE,
  file_id BIGINT NOT NULL REFERENCES files ON DELETE CASCADE,
  added_at TEXT NOT NULL DEFAULT (now()),
  PRIMARY KEY (collection_id, file_id)
);

CREATE TABLE local_file_favorites (
  file_id BIGINT PRIMARY KEY REFERENCES files ON DELETE CASCADE,
  favorited_at TEXT NOT NULL DEFAULT (now())
);

CREATE TABLE local_download_stats (
  stat_date TEXT NOT NULL,
  user_id BIGINT NOT NULL REFERENCES users ON DELETE CASCADE,
  files_downloaded BIGINT NOT NULL DEFAULT 0,
  bytes_downloaded BIGINT NOT NULL DEFAULT 0,
  bytes_deduped BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (stat_date, user_id)
);
|]

down_m20260701_download_manager :: Text
down_m20260701_download_manager =
  [r|
DROP TABLE local_download_stats;
DROP TABLE local_file_favorites;
DROP TABLE local_file_collection_members;
DROP TABLE local_file_collections;

DROP INDEX idx_files_file_name;
DROP INDEX idx_files_file_hash;

ALTER TABLE files DROP COLUMN file_mime;
ALTER TABLE files DROP COLUMN file_hash;
|]
