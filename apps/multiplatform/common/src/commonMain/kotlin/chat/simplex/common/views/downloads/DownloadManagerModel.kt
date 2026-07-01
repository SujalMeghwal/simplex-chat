package chat.simplex.common.views.downloads

import chat.simplex.common.model.*
import chat.simplex.common.platform.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

// Everything in this file is local-device-only: no network call, no sync, no telemetry.
// Favorites/collections persist inside chat.db (local_file_favorites/local_file_collections
// tables) — same on-device SQLCipher tier as the rest of the app's data, never bundled into any
// export/share/backup path, never touched by SMP/XFTP protocol code.
// Storage budgets (below) are plaintext-local by design, not DB-backed like favorites — a byte
// threshold per chat carries none of the sensitivity a "which files did you star" list does, so
// the plaintext-JSON tier (same as themes.yaml) is an appropriate, not a downgraded, choice here.

enum class FileCategory { Image, Video, Voice, File }

fun MsgContentTag.toFileCategory(): FileCategory? = when (this) {
  MsgContentTag.Image -> FileCategory.Image
  MsgContentTag.Video -> FileCategory.Video
  MsgContentTag.Voice -> FileCategory.Voice
  MsgContentTag.File -> FileCategory.File
  else -> null
}

enum class DownloadStatusFilter { All, Downloaded, Pending, Failed }

fun CIFileStatus.toStatusFilter(): DownloadStatusFilter = when (this) {
  is CIFileStatus.RcvComplete, is CIFileStatus.SndComplete -> DownloadStatusFilter.Downloaded
  is CIFileStatus.RcvInvitation, is CIFileStatus.RcvAccepted, is CIFileStatus.RcvTransfer -> DownloadStatusFilter.Pending
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
  val category: FileCategory
) {
  val fileId: Long? get() = item.file?.fileId
  val fileName: String get() = item.file?.fileName ?: item.text
  val fileSize: Long get() = item.file?.fileSize ?: 0L
  val isLocal: Boolean get() = item.file != null && getLoadedFilePath(item.file) != null
  val statusFilter: DownloadStatusFilter get() = item.file?.fileStatus?.toStatusFilter() ?: DownloadStatusFilter.All
  val senderName: String get() = if (item.chatDir.sent) "me" else chat.chatInfo.displayName
  val chatName: String get() = chat.chatInfo.displayName
  val createdAt get() = item.meta.itemTs
}

// Computes SHA-256 of the decrypted on-disk bytes. Only ever called for files already local
// (isLocal == true) — never triggers a download, never touches the network.
fun sha256OfLocalFile(path: String): String? {
  return try {
    val digest = MessageDigest.getInstance("SHA-256")
    File(path).inputStream().use { input ->
      val buf = ByteArray(64 * 1024)
      while (true) {
        val n = input.read(buf)
        if (n < 0) break
        digest.update(buf, 0, n)
      }
    }
    digest.digest().joinToString("") { "%02x".format(it) }
  } catch (e: Throwable) {
    Log.e(TAG, "sha256OfLocalFile error: $e")
    null
  }
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
  return all.distinctBy { it.item.id }
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
  if (status != DownloadStatusFilter.All) out = out.filter { it.statusFilter == status }
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
  SortOrder.Newest -> sortedByDescending { it.createdAt }
  SortOrder.Oldest -> sortedBy { it.createdAt }
  SortOrder.Largest -> sortedByDescending { it.fileSize }
  SortOrder.Smallest -> sortedBy { it.fileSize }
}

// Groups already-local files by content hash. Hashing runs only over files that are already
// downloaded (isLocal) — never triggers new downloads. Safe to call off the main thread.
fun findDuplicates(entries: List<UnifiedFileEntry>): List<DuplicateGroup> {
  val byHash = HashMap<String, MutableList<UnifiedFileEntry>>()
  for (e in entries) {
    if (!e.isLocal) continue
    val path = getLoadedFilePath(e.item.file) ?: continue
    val hash = sha256OfLocalFile(path) ?: continue
    byHash.getOrPut(hash) { mutableListOf() }.add(e)
  }
  return byHash.filter { it.value.size > 1 }.map { (hash, list) -> DuplicateGroup(hash, list) }
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

@kotlinx.serialization.Serializable
private data class ChatStorageBudgetsData(val budgets: MutableMap<String, Long> = mutableMapOf())

private fun chatBudgetKey(chat: Chat): String = "${chat.chatInfo.chatType}:${chat.chatInfo.apiId}"

object ChatStorageBudgets {
  private val file = File(getPreferenceFilePath("download_manager_budgets.json"))
  private var data: ChatStorageBudgetsData = load()

  private fun load(): ChatStorageBudgetsData =
    try {
      if (file.exists()) Json.decodeFromString(file.readText()) else ChatStorageBudgetsData()
    } catch (e: Throwable) {
      Log.e(TAG, "ChatStorageBudgets load error: $e")
      ChatStorageBudgetsData()
    }

  private fun persist() {
    try {
      file.parentFile?.mkdirs()
      file.writeText(Json.encodeToString(data))
    } catch (e: Throwable) {
      Log.e(TAG, "ChatStorageBudgets persist error: $e")
    }
  }

  fun get(chat: Chat): Long? = data.budgets[chatBudgetKey(chat)]

  fun set(chat: Chat, budgetBytes: Long?) {
    if (budgetBytes == null) data.budgets.remove(chatBudgetKey(chat)) else data.budgets[chatBudgetKey(chat)] = budgetBytes
    persist()
  }
}

data class ChatStorageInfo(val chat: Chat, val totalBytes: Long, val budgetBytes: Long?)

fun perChatStorage(entries: List<UnifiedFileEntry>): List<ChatStorageInfo> {
  return entries.filter { it.isLocal }
    .groupBy { it.chat }
    .map { (chat, list) -> ChatStorageInfo(chat, list.sumOf { it.fileSize }, ChatStorageBudgets.get(chat)) }
    .sortedByDescending { it.totalBytes }
}

// For every chat over its budget, deletes the local copy of the oldest files first (leaving the
// most recent ones) until the chat is back under budget. Local-only delete, never touches the
// network. Safe to call after every reload — a chat already under budget is a no-op.
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
