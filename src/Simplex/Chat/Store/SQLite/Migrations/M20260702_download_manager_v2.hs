{-# LANGUAGE QuasiQuotes #-}

module Simplex.Chat.Store.SQLite.Migrations.M20260702_download_manager_v2 where

import Database.SQLite.Simple (Query)
import Database.SQLite.Simple.QQ (sql)

-- Local-device-only, same as M20260701:
--   local_hash_key    -- per-user random key so file_hash (files.file_hash) is an HMAC, not a
--                         plain hash -- an attacker with a precomputed hash of a known file can't
--                         match it against this profile without this key.
--   local_chat_storage_budgets -- per-chat storage cap, moved here from a plaintext local file so
--                         it gets the same SQLCipher protection as the rest of the account
--                         (mirrors the contact_id/group_id/note_folder_id convention already used
--                         by the files table itself, since Saved Messages is a valid "chat" too).
m20260702_download_manager_v2 :: Query
m20260702_download_manager_v2 =
  [sql|
CREATE TABLE local_hash_key (
  user_id INTEGER PRIMARY KEY REFERENCES users ON DELETE CASCADE,
  hash_key BLOB NOT NULL
) STRICT;

CREATE TABLE local_chat_storage_budgets (
  contact_id INTEGER REFERENCES contacts ON DELETE CASCADE,
  group_id INTEGER REFERENCES groups ON DELETE CASCADE,
  note_folder_id INTEGER REFERENCES note_folders ON DELETE CASCADE,
  user_id INTEGER NOT NULL REFERENCES users ON DELETE CASCADE,
  budget_bytes INTEGER NOT NULL
) STRICT;

CREATE UNIQUE INDEX idx_local_chat_storage_budgets_chat ON local_chat_storage_budgets (
  user_id,
  IFNULL(contact_id, -1),
  IFNULL(group_id, -1),
  IFNULL(note_folder_id, -1)
);
|]

down_m20260702_download_manager_v2 :: Query
down_m20260702_download_manager_v2 =
  [sql|
DROP INDEX idx_local_chat_storage_budgets_chat;
DROP TABLE local_chat_storage_budgets;
DROP TABLE local_hash_key;
|]
