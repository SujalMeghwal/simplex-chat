package chat.simplex.common.views.downloads

import chat.simplex.common.model.*
import chat.simplex.common.platform.*

// Everything in this file is local-device-only: no network call, no sync, no telemetry.
// Favorites/collections and per-chat storage budgets all persist inside chat.db
// (local_file_favorites/local_file_collections/local_chat_storage_budgets tables) — same
// on-device SQLCipher tier as the rest of the app's data, never bundled into any export/share/
// backup path, never touched by SMP/XFTP protocol code.

enum class FileCategory { Image, Video, Voice, File }

fun MsgContentTag.toFileCategory(): FileCategory? = when (this) {
  MsgContentTag.Image -> FileCategory.Image
  MsgContentTag.Video -> FileCategory.Video
  MsgContentTag.Voice -> FileCategory.Voice
  MsgContentTag.File -> FileCategory.File
  else -> null
}

enum class DownloadStatusFilter { All, Downloaded, Downloading, Pending, Failed }

fun CIFileStatus.toStatusFilter(): DownloadStatusFilter = when (this) {
  is CIFileStatus.RcvComplete, is CIFileStatus.SndComplete -> DownloadStatusFilter.Downloaded
  // Actively transferring right now (accepted / receiving bytes) — its own bucket, separate from
  // items that merely have an offer and haven't started.
  is CIFileStatus.RcvAccepted, is CIFileStatus.RcvTransfer -> DownloadStatusFilter.Downloading
  is CIFileStatus.RcvInvitation -> DownloadStatusFilter.Pending
  is CIFileStatus.RcvAborted, is CIFileStatus.RcvError, is CIFileStatus.RcvCancelled,
  is CIFileStatus.SndError, is CIFileStatus.SndCancelled -> DownloadStatusFilter.Failed
  else -> DownloadStatusFilter.All
}

enum class SortOrder { Newest, Oldest, Largest, Smallest }

// One file, wherever it came from. hash is filled in lazily (on-demand, on-device) — never persisted
// off-device, only cached in-memory for the lifetime of this screen plus the local favorites file.
data class UnifiedFileEntry(
  val item: ChatItem,
  val chat: Chat,
  val category: FileCategory,
  // When a file has several copies (sent/received/forwarded/resent), dedup keeps one openable copy
  // but stamps it with the NEWEST timestamp among all copies so re-sharing an already-downloaded
  // file floats it back to the top of "Newest". Null = just use this item's own timestamp.
  val sortTsOverride: kotlinx.datetime.Instant? = null
) {
  val fileId: Long? get() = item.file?.fileId
  val fileName: String get() = item.file?.fileName ?: item.text
  val fileSize: Long get() = item.file?.fileSize ?: 0L
  val isLocal: Boolean get() = item.file != null && getLoadedFilePath(item.file) != null
  val statusFilter: DownloadStatusFilter get() = item.file?.fileStatus?.toStatusFilter() ?: DownloadStatusFilter.All
  // Actively receiving right now (accepted or mid-transfer) — drives the per-tile spinner so the
  // user can see a download is in flight instead of the button looking dead.
  val isDownloading: Boolean get() = item.file?.fileStatus.let { it is CIFileStatus.RcvAccepted || it is CIFileStatus.RcvTransfer }
  val isFailed: Boolean get() = statusFilter == DownloadStatusFilter.Failed
  // A file that has an offer but no local bytes yet and isn't already downloading/failed — i.e. the
  // set that "Download all pending" and the pending filter act on.
  val isPending: Boolean get() = !isLocal && !isDownloading && item.file != null
  // Live receive progress (0..100) when the core reports RcvTransfer bytes; null while merely queued
  // (RcvAccepted, no bytes yet) so the tile shows an indeterminate spinner instead of a fake 0%.
  val progressPct: Int? get() = (item.file?.fileStatus as? CIFileStatus.RcvTransfer)?.let {
    if (it.rcvTotal > 0) (it.rcvProgress * 100 / it.rcvTotal).toInt().coerceIn(0, 100) else null
  }
  // Monotonic-ish bytes-so-far for aggregate speed sampling: mid-transfer -> bytes received,
  // finished -> full size, not started -> 0. Summed across refreshes to derive download speed.
  val rcvBytes: Long get() = (item.file?.fileStatus as? CIFileStatus.RcvTransfer)?.rcvProgress ?: if (isLocal) fileSize else 0L
  val senderName: String get() = if (item.chatDir.sent) "me" else chat.chatInfo.displayName
  val chatName: String get() = chat.chatInfo.displayName
  val createdAt get() = item.meta.itemTs
  // Timestamp used for sorting: the newest across all duplicate copies when set (see dedup), else own.
  val sortTs: kotlinx.datetime.Instant get() = sortTsOverride ?: createdAt

  // Identity of the underlying file, independent of which message/chat carries it. The same file
  // sent, received, forwarded, or shared into a note folder produces different ChatItems (and
  // different fileIds), but the media content is identical — so we key on the media preview
  // (identical bytes for identical image/video) plus size, falling back to name+size for
  // voice/documents. This is what collapses the "same file shows up N times" duplicates.
  fun dedupKey(): String {
    val previewSig = when (val mc = item.content.msgContent) {
      is MsgContent.MCImage -> mc.image
      is MsgContent.MCVideo -> mc.image
      else -> null
    }
    return if (!previewSig.isNullOrEmpty()) "p:${previewSig.hashCode()}:$fileSize"
    else "n:${fileName.lowercase()}:$fileSize"
  }
}

// Collapse duplicate copies of the same file down to one entry. Keeps the most useful copy:
// already-downloaded first, then one that's actively downloading, then the newest — so the surviving
// tile is the one you can actually open. Deterministic ordering keeps the choice stable across reloads.
fun List<UnifiedFileEntry>.dedupByContent(): List<UnifiedFileEntry> =
  groupBy { it.dedupKey() }
    .map { (_, group) ->
      val best = group.sortedWith(
        compareByDescending<UnifiedFileEntry> { it.isLocal }
          .thenByDescending { it.isDownloading }
          .thenByDescending { it.createdAt }
      ).first()
      // Keep the best openable copy, but sort it by the newest timestamp across all copies — so
      // forwarding/resending an already-downloaded file bumps it to the top of "Newest".
      val newestTs = group.maxOf { it.createdAt }
      if (newestTs > best.createdAt) best.copy(sortTsOverride = newestTs) else best
    }

// Favorites/collections live inside chat.db (local_file_favorites / local_file_collections(_members),
// added by migration M20260701_download_manager) — same SQLCipher encryption tier as every other
// local chat record, instead of a separate plaintext file. Still 100% local: these tables are never
// touched by SMP/XFTP/agent protocol code and never ride along on migrate-to-device/export.
//
// This object just caches the last-fetched FileVaultData in memory for synchronous reads (list
// filtering needs isFavorite() without suspending); every mutation goes to core first and only
// updates the cache from the authoritative response.
object LocalFileVault {
  private var cache: FileVaultData = FileVaultData(emptyList(), emptyList())

  suspend fun load(rhId: Long?) {
    chatModel.controller.apiGetFileVault(rhId)?.let { cache = it }
  }

  fun isFavorite(fileId: Long): Boolean = cache.favoriteFileIds.contains(fileId)

  suspend fun toggleFavorite(rhId: Long?, fileId: Long) {
    chatModel.controller.apiToggleFileFavorite(rhId, fileId)?.let { cache = it }
  }

  fun collectionNames(): List<String> = cache.fileCollections.map { it.collectionName }.sorted()

  suspend fun addToCollection(rhId: Long?, name: String, fileId: Long) {
    chatModel.controller.apiAddFileToCollection(rhId, fileId, name)?.let { cache = it }
  }

  suspend fun removeFromCollection(rhId: Long?, name: String, fileId: Long) {
    chatModel.controller.apiRemoveFileFromCollection(rhId, fileId, name)?.let { cache = it }
  }

  fun filesInCollection(name: String): Set<Long> =
    cache.fileCollections.find { it.collectionName == name }?.fileIds?.toSet() ?: emptySet()

  suspend fun deleteCollection(rhId: Long?, name: String) {
    chatModel.controller.apiDeleteFileCollection(rhId, name)?.let { cache = it }
  }
}

// One dedup group: same content hash, multiple files. All entries after the first (oldest) are
// candidates for cleanup.
data class DuplicateGroup(val hash: String, val entries: List<UnifiedFileEntry>) {
  val keep: UnifiedFileEntry get() = entries.minBy { it.createdAt }
  val removable: List<UnifiedFileEntry> get() = entries.filter { it.item.id != keep.item.id }
  val reclaimableBytes: Long get() = removable.sumOf { it.fileSize }
}

// Pull every image/video/voice/file item across every chat (paged, read-only) and tag it with its
// source chat + category. Bounded per-chat page size keeps this responsive on large accounts;
// exhaustive history scan is a stretch goal (task: backend cross-chat query) once this proves the UX.
suspend fun loadAllFilesAcrossChats(pageSizePerChatPerTag: Int = 500): List<UnifiedFileEntry> {
  val all = ArrayList<UnifiedFileEntry>()
  for (chat in chatModel.chats.value.toList()) {
    val info = chat.chatInfo
    if (info !is ChatInfo.Direct && info !is ChatInfo.Group && info !is ChatInfo.Local) continue
    for (tag in listOf(MsgContentTag.Image, MsgContentTag.Video, MsgContentTag.Voice, MsgContentTag.File)) {
      val category = tag.toFileCategory() ?: continue
      val res = chatModel.controller.apiGetChat(
        chat.remoteHostId, info.chatType, info.apiId, null, tag, ChatPagination.Last(pageSizePerChatPerTag), ""
      ) ?: continue
      res.first.chatItems.forEach { all.add(UnifiedFileEntry(it, chat, category)) }
    }
  }
  // distinctBy id drops the exact-same message pulled under two tags; dedupByContent then collapses
  // send/forward/share copies of the same underlying file so each unique file appears exactly once.
  return all.distinctBy { it.item.id }.dedupByContent()
}

fun List<UnifiedFileEntry>.applyFilters(
  category: FileCategory? = null,
  status: DownloadStatusFilter = DownloadStatusFilter.All,
  favoritesOnly: Boolean = false,
  collection: String? = null,
  query: String = ""
): List<UnifiedFileEntry> {
  var out = this.asSequence()
  if (category != null) out = out.filter { it.category == category }
  when (status) {
    DownloadStatusFilter.All -> {}
    // Use the live getters (not just the snapshot status) so "Downloading" tracks active transfers
    // and "Pending" is strictly the not-started offers — the two the user wanted split apart.
    DownloadStatusFilter.Downloading -> out = out.filter { it.isDownloading }
    DownloadStatusFilter.Pending -> out = out.filter { it.isPending }
    else -> out = out.filter { it.statusFilter == status }
  }
  if (favoritesOnly) out = out.filter { it.fileId != null && LocalFileVault.isFavorite(it.fileId!!) }
  if (collection != null) {
    val ids = LocalFileVault.filesInCollection(collection)
    out = out.filter { it.fileId != null && it.fileId in ids }
  }
  if (query.isNotBlank()) {
    val q = query.trim().lowercase()
    out = out.filter {
      it.fileName.lowercase().contains(q) ||
        it.senderName.lowercase().contains(q) ||
        it.chatName.lowercase().contains(q)
    }
  }
  return out.toList()
}

fun List<UnifiedFileEntry>.sortedBy(order: SortOrder): List<UnifiedFileEntry> = when (order) {
  SortOrder.Newest -> sortedByDescending { it.sortTs }
  SortOrder.Oldest -> sortedBy { it.sortTs }
  SortOrder.Largest -> sortedByDescending { it.fileSize }
  SortOrder.Smallest -> sortedBy { it.fileSize }
}

fun storageStats(entries: List<UnifiedFileEntry>): Map<FileCategory, Pair<Int, Long>> {
  return entries.filter { it.isLocal }
    .groupBy { it.category }
    .mapValues { (_, list) -> list.size to list.sumOf { it.fileSize } }
}

// --- Insights: age distribution, storage health, biggest offenders --------------------------
// Everything here is arithmetic over the already-loaded local file index. No new storage, no
// new query, nothing that leaves the device.

enum class AgeBucket(val label: String) { Last30Days("Last 30 days"), ThreeToSixMonths("3-6 months"), OneYear("Up to 1 year"), TwoYearsPlus("2+ years") }

fun ageDistribution(entries: List<UnifiedFileEntry>): Map<AgeBucket, Long> {
  val now = kotlinx.datetime.Clock.System.now()
  val out = linkedMapOf(AgeBucket.Last30Days to 0L, AgeBucket.ThreeToSixMonths to 0L, AgeBucket.OneYear to 0L, AgeBucket.TwoYearsPlus to 0L)
  for (e in entries) {
    if (!e.isLocal) continue
    val ageDays = (now - e.createdAt).inWholeDays
    val bucket = when {
      ageDays <= 30 -> AgeBucket.Last30Days
      ageDays <= 183 -> AgeBucket.ThreeToSixMonths
      ageDays <= 365 -> AgeBucket.OneYear
      else -> AgeBucket.TwoYearsPlus
    }
    out[bucket] = (out[bucket] ?: 0L) + e.fileSize
  }
  return out
}

data class StorageHealth(val score: Int, val notes: List<Pair<String, Boolean>>) // (note, good)

// Simple heuristic, not a precise metric: starts at 100, docked for signals that make a local
// library harder to manage (lots of duplicate bytes, lots of stale/never-revisited old media).
fun storageHealth(entries: List<UnifiedFileEntry>, duplicates: List<DuplicateGroup>): StorageHealth {
  val local = entries.filter { it.isLocal }
  val totalBytes = local.sumOf { it.fileSize }.coerceAtLeast(1)
  val dupBytes = duplicates.sumOf { it.reclaimableBytes }
  val dupRatio = dupBytes.toDouble() / totalBytes
  val oldBytes = ageDistribution(local)[AgeBucket.TwoYearsPlus] ?: 0L
  val oldRatio = oldBytes.toDouble() / totalBytes

  var score = 100
  val notes = mutableListOf<Pair<String, Boolean>>()
  if (dupRatio > 0.15) { score -= 20; notes.add("High duplicate ratio (${"%.0f".format(dupRatio * 100)}%)" to false) } else notes.add("Few duplicates" to true)
  if (oldRatio > 0.4) { score -= 15; notes.add("Lots of 2+ year old local media" to false) } else notes.add("Local media is reasonably fresh" to true)
  if (local.size > 5000) { score -= 10; notes.add("Large local file index (${local.size} files)" to false) }
  return StorageHealth(score.coerceIn(0, 100), notes)
}

// --- Per-chat storage budgets ---------------------------------------------------------------
//
// Local-only setting: cap how much local storage a chat is allowed to use. When a chat exceeds
// its budget, oldest local copies are deleted first (cidmInternal — this device only, senders
// and other devices untouched), same mechanism already used everywhere else in this file.
//
// Persisted inside chat.db (local_chat_storage_budgets table) — same SQLCipher tier as
// favorites/collections. Originally shipped as a plaintext local JSON file on the reasoning that
// a byte-count cap isn't sensitive; moved here anyway so nothing in this feature sits at a lower
// protection tier than the rest of the account for no real reason.

object ChatStorageBudgets {
  private var cache: List<ChatStorageBudget> = emptyList()

  private fun keyFor(type: ChatType, id: Long): String = "$type:$id"

  private fun keyFor(b: ChatStorageBudget): String? = when {
    b.sbContactId != null -> keyFor(ChatType.Direct, b.sbContactId)
    b.sbGroupId != null -> keyFor(ChatType.Group, b.sbGroupId)
    b.sbNoteFolderId != null -> keyFor(ChatType.Local, b.sbNoteFolderId)
    else -> null
  }

  suspend fun load(rhId: Long?) {
    chatModel.controller.apiGetChatStorageBudgets(rhId)?.let { cache = it }
  }

  fun get(chat: Chat): Long? {
    val key = keyFor(chat.chatInfo.chatType, chat.chatInfo.apiId)
    return cache.firstOrNull { keyFor(it) == key }?.sbBudgetBytes
  }

  suspend fun set(rhId: Long?, chat: Chat, budgetBytes: Long?) {
    val updated = if (budgetBytes == null) {
      chatModel.controller.apiClearChatStorageBudget(rhId, chat.chatInfo.chatType, chat.chatInfo.apiId)
    } else {
      chatModel.controller.apiSetChatStorageBudget(rhId, chat.chatInfo.chatType, chat.chatInfo.apiId, budgetBytes)
    }
    updated?.let { cache = it }
  }
}

data class ChatStorageInfo(val chat: Chat, val totalBytes: Long, val budgetBytes: Long?)

fun perChatStorage(entries: List<UnifiedFileEntry>): List<ChatStorageInfo> {
  return entries.filter { it.isLocal }
    .groupBy { it.chat }
    .map { (chat, list) -> ChatStorageInfo(chat, list.sumOf { it.fileSize }, ChatStorageBudgets.get(chat)) }
    .sortedByDescending { it.totalBytes }
}

// For every chat over its budget, deletes the local copy of the oldest files first (leaving the most
// recent ones) until the chat is back under budget. Local-only delete, never touches the network.
//
// DANGER: this permanently removes local items with NO confirmation. Invoke it only from an explicit,
// user-initiated + confirmed action — never automatically on reload/open (that silently deletes the
// oldest media every time the screen is viewed). It is intentionally not called anywhere right now.
// Also pass the FULL per-file list, not a content-deduped one: dedup keeps the single downloaded copy,
// so enforcing over a deduped list would delete exactly the copy the user can open.
suspend fun enforceStorageBudgets(entries: List<UnifiedFileEntry>) {
  val byChat = entries.filter { it.isLocal }.groupBy { it.chat }
  for ((chat, files) in byChat) {
    val budget = ChatStorageBudgets.get(chat) ?: continue
    var total = files.sumOf { it.fileSize }
    if (total <= budget) continue
    val oldestFirst = files.sortedBy { it.createdAt }
    val toDelete = mutableListOf<UnifiedFileEntry>()
    for (e in oldestFirst) {
      if (total <= budget) break
      toDelete.add(e)
      total -= e.fileSize
    }
    toDelete.chunked(100).forEach { chunk ->
      chatModel.controller.apiDeleteChatItems(
        chat.remoteHostId, chat.chatInfo.chatType, chat.chatInfo.apiId, null,
        chunk.map { it.item.id }, CIDeleteMode.cidmInternal
      )
    }
  }
}
