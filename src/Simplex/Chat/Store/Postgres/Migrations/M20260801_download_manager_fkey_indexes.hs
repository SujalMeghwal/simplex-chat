{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE QuasiQuotes #-}

module Simplex.Chat.Store.Postgres.Migrations.M20260801_download_manager_fkey_indexes where

import Data.Text (Text)
import Text.RawString.QQ (r)

-- Indexes for the foreign keys of the download manager tables (M20260701/M20260702).
m20260801_download_manager_fkey_indexes :: Text
m20260801_download_manager_fkey_indexes =
  [r|
CREATE INDEX idx_local_file_collections_user_id ON local_file_collections(user_id);
CREATE INDEX idx_local_file_collection_members_collection_id ON local_file_collection_members(collection_id);
CREATE INDEX idx_local_file_collection_members_file_id ON local_file_collection_members(file_id);
CREATE INDEX idx_local_download_stats_user_id ON local_download_stats(user_id);
CREATE INDEX idx_local_chat_storage_budgets_contact_id ON local_chat_storage_budgets(contact_id);
CREATE INDEX idx_local_chat_storage_budgets_group_id ON local_chat_storage_budgets(group_id);
CREATE INDEX idx_local_chat_storage_budgets_note_folder_id ON local_chat_storage_budgets(note_folder_id);
|]

down_m20260801_download_manager_fkey_indexes :: Text
down_m20260801_download_manager_fkey_indexes =
  [r|
DROP INDEX idx_local_chat_storage_budgets_note_folder_id;
DROP INDEX idx_local_chat_storage_budgets_group_id;
DROP INDEX idx_local_chat_storage_budgets_contact_id;
DROP INDEX idx_local_download_stats_user_id;
DROP INDEX idx_local_file_collection_members_file_id;
DROP INDEX idx_local_file_collection_members_collection_id;
DROP INDEX idx_local_file_collections_user_id;
|]
