{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE QuasiQuotes #-}

module Simplex.Chat.Store.Postgres.Migrations.M20260702_download_manager_v2 where

import Data.Text (Text)
import Text.RawString.QQ (r)

-- Local-device-only, mirrors Store/SQLite/Migrations/M20260702_download_manager_v2.hs.
m20260702_download_manager_v2 :: Text
m20260702_download_manager_v2 =
  [r|
CREATE TABLE local_hash_key (
  user_id BIGINT PRIMARY KEY REFERENCES users ON DELETE CASCADE,
  hash_key BYTEA NOT NULL
);

CREATE TABLE local_chat_storage_budgets (
  contact_id BIGINT REFERENCES contacts ON DELETE CASCADE,
  group_id BIGINT REFERENCES groups ON DELETE CASCADE,
  note_folder_id BIGINT REFERENCES note_folders ON DELETE CASCADE,
  user_id BIGINT NOT NULL REFERENCES users ON DELETE CASCADE,
  budget_bytes BIGINT NOT NULL
);

CREATE UNIQUE INDEX idx_local_chat_storage_budgets_chat ON local_chat_storage_budgets (
  user_id,
  COALESCE(contact_id, -1),
  COALESCE(group_id, -1),
  COALESCE(note_folder_id, -1)
);
|]

down_m20260702_download_manager_v2 :: Text
down_m20260702_download_manager_v2 =
  [r|
DROP INDEX idx_local_chat_storage_budgets_chat;
DROP TABLE local_chat_storage_budgets;
DROP TABLE local_hash_key;
|]
