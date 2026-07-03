package chat.simplex.common.views.downloads

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.simplex.common.model.*
import chat.simplex.common.platform.*
import chat.simplex.common.views.chat.providerForGallery
import chat.simplex.common.views.chat.item.openGalleryModal
import chat.simplex.common.views.helpers.AlertManager
import chat.simplex.common.views.helpers.ModalManager
import chat.simplex.res.MR
import dev.icerock.moko.resources.compose.painterResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Unified Download Manager: every image/video/voice/file, downloaded or not, across every chat,
// in one screen. Everything below reads local data only (chatModel.chats already loaded in memory,
// on-disk file bytes for hashing) — no network call is made by this screen, nothing here is synced
// to any other device.

private enum class MainTab { Browse, Duplicates, Storage, Insights }
private enum class ViewMode { Grid, List }

@Composable
fun DownloadManagerView(close: () -> Unit) {
  val tab = remember { mutableStateOf(MainTab.Browse) }
  val allFiles = remember { mutableStateOf<List<UnifiedFileEntry>>(emptyList()) }
  val loading = remember { mutableStateOf(true) }
  val speedBps = remember { mutableStateOf(0L) } // aggregate download speed (bytes/s), sampled by the live loop

  // showSpinner=false does an in-place refresh (list stays visible) — used for post-download and the
  // periodic catch-up reloads so the view doesn't flash the full-screen spinner every couple seconds.
  suspend fun reload(showSpinner: Boolean = true) {
    if (showSpinner) loading.value = true
    val rhId = chatModel.remoteHostId()
    LocalFileVault.load(rhId)
    ChatStorageBudgets.load(rhId)
    val files = loadAllFilesAcrossChats()
    enforceStorageBudgets(files)
    allFiles.value = if (files.any { ChatStorageBudgets.get(it.chat) != null }) loadAllFilesAcrossChats() else files
    // Reconcile the live-progress overlays with reality: keep only files the core still reports as
    // actively receiving. A transfer that finished, failed, or was cancelled between events would
    // otherwise leave a stale entry — the "stuck at 0 / stuck at 75%" tiles the user saw. After this,
    // a stalled tile means the core genuinely still lists it as transferring (a real stall to retry).
    val active = allFiles.value.filter { it.isDownloading }.mapNotNull { it.fileId }.toHashSet()
    chatModel.fileProgress.keys.retainAll(active)
    chatModel.fileSpeed.keys.retainAll(active)
    if (showSpinner) loading.value = false
  }

  LaunchedEffect(Unit) { reload() }

  // Live progress + speed come from the event-fed chatModel.fileProgress map — cheap, no reload.
  // Sample once a second; prune finished transfers so counts and speed stay accurate.
  LaunchedEffect(Unit) {
    val prev = HashMap<Long, Long>()
    var prevTime = System.currentTimeMillis()
    while (true) {
      delay(1000)
      val snap = chatModel.fileProgress.toMap()
      snap.forEach { (id, pair) -> if (pair.second in 1..pair.first) chatModel.fileProgress.remove(id) }
      val now = System.currentTimeMillis()
      val dt = (now - prevTime).coerceAtLeast(1)
      var delta = 0L
      for ((id, pair) in snap) {
        val p = prev[id] ?: 0L
        val d = if (pair.first > p) pair.first - p else 0L
        delta += d
        chatModel.fileSpeed[id] = d * 1000 / dt
        prev[id] = pair.first
      }
      prev.keys.retainAll(snap.keys)
      chatModel.fileSpeed.keys.retainAll(snap.keys)
      speedBps.value = if (snap.isEmpty()) 0L else delta * 1000 / dt
      prevTime = now
    }
  }

  // Reconcile the list (finished files move to Downloaded, removed ones drop out) at a gentle cadence,
  // only while transfers are active. The heavy cross-chat reload no longer drives live progress.
  LaunchedEffect(Unit) {
    while (true) {
      delay(6000)
      if (chatModel.fileProgress.isNotEmpty()) reload(false)
    }
  }

  Column(Modifier.fillMaxSize().background(MaterialTheme.colors.background)) {
    Row(
      Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      IconButton(onClick = close) {
        Icon(painterResource(MR.images.ic_arrow_back_ios_new), contentDescription = "Back", tint = MaterialTheme.colors.primary)
      }
      Text("Downloads", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp))
      Spacer(Modifier.weight(1f))
      if (!loading.value) {
        val downloading = chatModel.fileProgress.size
        val spd = speedBps.value
        Text(
          when {
            downloading > 0 && spd > 0 -> "${allFiles.value.size} files · $downloading downloading · ${formatSpeed(spd)}"
            downloading > 0 -> "${allFiles.value.size} files · $downloading downloading"
            else -> "${allFiles.value.size} files"
          },
          fontSize = 13.sp,
          color = if (downloading > 0) MaterialTheme.colors.primary else MaterialTheme.colors.secondary
        )
      }
    }
    TabRow(selectedTabIndex = tab.value.ordinal, backgroundColor = MaterialTheme.colors.background) {
      Tab(selected = tab.value == MainTab.Browse, onClick = { tab.value = MainTab.Browse }, text = { Text("Browse") })
      Tab(selected = tab.value == MainTab.Duplicates, onClick = { tab.value = MainTab.Duplicates }, text = { Text("Duplicates") })
      Tab(selected = tab.value == MainTab.Storage, onClick = { tab.value = MainTab.Storage }, text = { Text("Storage") })
      Tab(selected = tab.value == MainTab.Insights, onClick = { tab.value = MainTab.Insights }, text = { Text("Insights") })
    }
    Divider()
    when {
      loading.value -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MaterialTheme.colors.primary)
      }
      tab.value == MainTab.Browse -> BrowseTab(allFiles.value) { showSpinner -> reload(showSpinner) }
      tab.value == MainTab.Duplicates -> DuplicatesTab(allFiles.value) { reloadScope -> reloadScope.launch { reload() } }
      tab.value == MainTab.Storage -> StorageTab(allFiles.value) { reloadScope -> reloadScope.launch { reload() } }
      tab.value == MainTab.Insights -> InsightsTab(allFiles.value)
    }
  }
}

// --- Browse tab: filters, search, sort, grid/list, multi-select, batch actions ------------------

@Composable
private fun BrowseTab(all: List<UnifiedFileEntry>, reload: suspend (Boolean) -> Unit) {
  val scope = rememberCoroutineScope()
  val actionMsg = remember { mutableStateOf("") }
  val busy = remember { mutableStateOf<String?>(null) } // non-null while a long delete/cleanup runs
  val category = remember { mutableStateOf<FileCategory?>(null) }
  val status = remember { mutableStateOf(DownloadStatusFilter.All) }
  val favoritesOnly = remember { mutableStateOf(false) }
  val sort = remember { mutableStateOf(SortOrder.Newest) }
  val viewMode = remember { mutableStateOf(ViewMode.Grid) }
  val query = remember { mutableStateOf("") }
  val selected = remember { mutableStateListOf<Long>() }
  val favToggle = remember { mutableStateOf(0) } // bump to force favorite-icon recomposition

  val filtered = remember(all, category.value, status.value, favoritesOnly.value, query.value, sort.value, favToggle.value) {
    all.applyFilters(category.value, status.value, favoritesOnly.value, null, query.value).sortedBy(sort.value)
  }
  val byId = remember(filtered) { filtered.associateBy { it.fileId } }

  fun toggleSelect(id: Long) { if (!selected.remove(id)) selected.add(id) }

  // Double-click opens the tapped image/video in the same full-screen gallery chat uses: arrow/swipe
  // between items, in-app video playback, zoom. Previews show even for not-yet-downloaded media
  // (no download is triggered just by viewing).
  fun openItem(entry: UnifiedFileEntry) {
    if (entry.category != FileCategory.Image && entry.category != FileCategory.Video) return
    // The viewer navigates downloaded media only. Double-clicking a not-downloaded item would open a
    // blank frame, so instead kick off its download (respecting the IP-exposure policy).
    if (!entry.isLocal) {
      scope.launch { actionMsg.value = downloadRespectingPrivacy(listOf(entry)); reload(false) }
      return
    }
    val items = all.filter { it.category == FileCategory.Image || it.category == FileCategory.Video }
      .map { it.item }.sortedBy { it.meta.createdAt }
    ModalManager.fullscreen.showCustomModal { gClose ->
      openGalleryModal({ downloadedOnly -> providerForGallery(items, entry.item.id, downloadedOnly) {} }, gClose)
    }
  }

  Box(Modifier.fillMaxSize()) {
  Column(Modifier.fillMaxSize()) {
    // Search — always visible, first thing a returning user reaches for.
    OutlinedTextField(
      value = query.value,
      onValueChange = { query.value = it },
      placeholder = { Text("Search name, sender, chat…") },
      leadingIcon = { Icon(painterResource(MR.images.ic_search), contentDescription = null) },
      trailingIcon = {
        if (query.value.isNotEmpty()) {
          IconButton(onClick = { query.value = "" }) {
            Icon(painterResource(MR.images.ic_close), contentDescription = "Clear")
          }
        }
      },
      singleLine = true,
      modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
    )

    // Category + status + favorites chips — the "smart filters" from the spec, one tap each.
    LazyRow(
      Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
      horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
      item { FilterChip("All", category.value == null) { category.value = null } }
      item { FilterChip("Images", category.value == FileCategory.Image) { category.value = FileCategory.Image } }
      item { FilterChip("Videos", category.value == FileCategory.Video) { category.value = FileCategory.Video } }
      item { FilterChip("Voice", category.value == FileCategory.Voice) { category.value = FileCategory.Voice } }
      item { FilterChip("Documents", category.value == FileCategory.File) { category.value = FileCategory.File } }
      item { Divider(Modifier.width(1.dp).height(24.dp)) }
      item { FilterChip("Downloaded", status.value == DownloadStatusFilter.Downloaded) { status.value = if (status.value == DownloadStatusFilter.Downloaded) DownloadStatusFilter.All else DownloadStatusFilter.Downloaded } }
      item { FilterChip("Downloading", status.value == DownloadStatusFilter.Downloading) { status.value = if (status.value == DownloadStatusFilter.Downloading) DownloadStatusFilter.All else DownloadStatusFilter.Downloading } }
      item { FilterChip("Not downloaded", status.value == DownloadStatusFilter.Pending) { status.value = if (status.value == DownloadStatusFilter.Pending) DownloadStatusFilter.All else DownloadStatusFilter.Pending } }
      item { FilterChip("Failed", status.value == DownloadStatusFilter.Failed) { status.value = if (status.value == DownloadStatusFilter.Failed) DownloadStatusFilter.All else DownloadStatusFilter.Failed } }
      item { FilterChip("★ Favorites", favoritesOnly.value) { favoritesOnly.value = !favoritesOnly.value } }
    }

    // Sort + view-mode toggle — plain text, no icon guesswork, always legible.
    Row(
      Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      SortDropdown(sort.value) { sort.value = it }
      Spacer(Modifier.weight(1f))
      TextButton(onClick = { viewMode.value = if (viewMode.value == ViewMode.Grid) ViewMode.List else ViewMode.Grid }) {
        Text(if (viewMode.value == ViewMode.Grid) "List view" else "Grid view")
      }
    }

    // Bulk actions — pick many at once, or grab everything pending, without tapping each tile.
    val filteredIds = remember(filtered) { filtered.mapNotNull { it.fileId } }
    val pending = remember(filtered) { filtered.filter { it.isPending } }
    val allSelected = filteredIds.isNotEmpty() && selected.containsAll(filteredIds)
    Row(
      Modifier.fillMaxWidth().padding(horizontal = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
      TextButton(onClick = {
        if (allSelected) selected.clear() else { selected.clear(); selected.addAll(filteredIds) }
      }, enabled = filteredIds.isNotEmpty()) { Text(if (allSelected) "Deselect all" else "Select all") }
      TextButton(onClick = {
        pending.mapNotNull { it.fileId }.forEach { if (!selected.contains(it)) selected.add(it) }
      }, enabled = pending.isNotEmpty()) { Text("Select pending") }
      Spacer(Modifier.weight(1f))
      val activeCount = all.count { val f = it.fileId; it.isDownloading || (f != null && f in chatModel.fileProgress) }
      if (activeCount > 0) {
        TextButton(onClick = {
          scope.launch { actionMsg.value = pauseDownloads(all); reload(false) }
        }) { Text("Pause all ($activeCount)") }
        TextButton(onClick = {
          scope.launch { actionMsg.value = stopDownloads(all); reload(false) }
        }) { Text("Stop all ($activeCount)", color = MaterialTheme.colors.error) }
      }
      if (pending.isNotEmpty()) {
        TextButton(onClick = {
          scope.launch {
            actionMsg.value = "Starting…"
            actionMsg.value = downloadRespectingPrivacy(pending)
            reload(false)
          }
        }) { Text("Download all (${pending.size})") }
      }
    }
    if (actionMsg.value.isNotEmpty()) {
      Text(actionMsg.value, fontSize = 11.sp, color = MaterialTheme.colors.primary, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
    }
    Divider()

    Box(Modifier.weight(1f)) {
      when {
        filtered.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          Text(
            if (query.value.isNotBlank()) "No files match \"${query.value}\"." else "No files here yet. Images, videos, voice messages, and attachments from your chats will show up here once you receive them.",
            color = MaterialTheme.colors.secondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(24.dp)
          )
        }
        viewMode.value == ViewMode.Grid -> FileGrid(filtered, selected, onOpen = { openItem(it) }) { toggleSelect(it) }
        else -> FileList(filtered, selected, onOpen = { openItem(it) }) { toggleSelect(it) }
      }
    }

    // Batch action bar — only appears once something is selected, keeps the default view uncluttered.
    // NOTE: the bar runs its long operations on BrowseTab's `scope` (passed in), NOT its own — clearing
    // the selection removes the bar, and a coroutine started on the bar's own scope would be cancelled
    // mid-delete, freezing the progress overlay. BrowseTab's scope outlives the selection.
    if (selected.isNotEmpty()) {
      BatchActionBar(scope, selected.mapNotNull { byId[it] }, onClear = { selected.clear() }, onChanged = { favToggle.value++ }, reload = reload, onMsg = { actionMsg.value = it }, setBusy = { busy.value = it })
    }
  }

    // Operation overlay — blocks the screen with live progress while a bulk delete/cleanup runs, so
    // "delete everything" no longer looks frozen. Dismisses itself when the operation finishes; the
    // Hide button is an escape hatch so the user is never trapped by it.
    val op = busy.value
    if (op != null) {
      Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).clickable(enabled = false) {},
        contentAlignment = Alignment.Center
      ) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colors.surface, elevation = 8.dp) {
          Column(Modifier.padding(horizontal = 24.dp, vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              CircularProgressIndicator(color = MaterialTheme.colors.primary, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
              Text(op, fontSize = 15.sp, modifier = Modifier.padding(start = 16.dp))
            }
            TextButton(onClick = { busy.value = null }, modifier = Modifier.padding(top = 8.dp)) {
              Text("Hide", fontSize = 13.sp)
            }
          }
        }
      }
    }
  }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
  Surface(
    color = if (selected) MaterialTheme.colors.primary else MaterialTheme.colors.surface,
    contentColor = if (selected) Color.White else MaterialTheme.colors.onSurface,
    shape = RoundedCornerShape(16.dp),
    border = if (!selected) BorderStroke(1.dp, MaterialTheme.colors.secondary.copy(alpha = 0.3f)) else null,
    modifier = Modifier.clickable(onClick = onClick)
  ) {
    Text(label, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
  }
}

@Composable
private fun SortDropdown(current: SortOrder, onSelect: (SortOrder) -> Unit) {
  val expanded = remember { mutableStateOf(false) }
  Box {
    TextButton(onClick = { expanded.value = true }) {
      Text("Sort: ${sortLabel(current)}")
    }
    DropdownMenu(expanded = expanded.value, onDismissRequest = { expanded.value = false }) {
      SortOrder.entries.forEach { order ->
        DropdownMenuItem(onClick = { onSelect(order); expanded.value = false }) {
          Text(sortLabel(order))
        }
      }
    }
  }
}

private fun sortLabel(order: SortOrder) = when (order) {
  SortOrder.Newest -> "Newest"
  SortOrder.Oldest -> "Oldest"
  SortOrder.Largest -> "Largest"
  SortOrder.Smallest -> "Smallest"
}

@Composable
private fun FileGrid(entries: List<UnifiedFileEntry>, selected: SnapshotStateList<Long>, onOpen: (UnifiedFileEntry) -> Unit, onToggle: (Long) -> Unit) {
  LazyVerticalGrid(
    columns = GridCells.Adaptive(minSize = 168.dp),
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(8.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp)
  ) {
    items(entries, key = { it.item.id }) { e ->
      GridTile(e, selected.contains(e.fileId), onOpen = { onOpen(e) }) { onToggle(e.fileId ?: return@GridTile) }
    }
  }
}

@Composable
private fun FileList(entries: List<UnifiedFileEntry>, selected: SnapshotStateList<Long>, onOpen: (UnifiedFileEntry) -> Unit, onToggle: (Long) -> Unit) {
  LazyColumn(Modifier.fillMaxSize()) {
    items(entries, key = { it.item.id }) { e ->
      ListRow(e, selected.contains(e.fileId), onOpen = { onOpen(e) }) { onToggle(e.fileId ?: return@ListRow) }
      Divider()
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridTile(e: UnifiedFileEntry, isSelected: Boolean, onOpen: () -> Unit, onToggle: () -> Unit) {
  val b64 = when (val mc = e.item.content.msgContent) {
    is MsgContent.MCImage -> mc.image
    is MsgContent.MCVideo -> mc.image
    else -> ""
  }
  val bitmap = remember(e.item.id) { if (b64.isNotEmpty()) runCatching { base64ToBitmap(b64) }.getOrNull() else null }
  // Live progress straight from the event map (recomposes this tile only when its file advances) —
  // falls back to the snapshot status when the map has no entry yet.
  val liveProg = e.fileId?.let { chatModel.fileProgress[it] }
  val downloading = liveProg != null || e.isDownloading
  val livePct = when {
    liveProg != null && liveProg.second > 0 -> (liveProg.first * 100 / liveProg.second).toInt().coerceIn(0, 100)
    else -> e.progressPct
  }
  val base = Modifier.aspectRatio(1f).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.15f))
  Box(
    (if (isSelected) base.border(3.dp, MaterialTheme.colors.primary, RoundedCornerShape(6.dp)) else base)
      .combinedClickable(onClick = { onToggle() }, onDoubleClick = { onOpen() }),
    contentAlignment = Alignment.Center
  ) {
    if (bitmap != null) {
      Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    } else {
      Icon(categoryIcon(e.category), contentDescription = null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colors.secondary)
    }
    if (e.category == FileCategory.Video) {
      Icon(
        painterResource(MR.images.ic_play_arrow_filled), contentDescription = null, tint = Color.White,
        modifier = Modifier.size(34.dp).background(Color.Black.copy(alpha = 0.45f), CircleShape).padding(6.dp)
      )
    }
    when {
      downloading -> Box(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 4.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
      ) {
        val pct = livePct
        if (pct != null) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            LinearProgressIndicator(progress = pct / 100f, color = Color.White, backgroundColor = Color.White.copy(alpha = 0.3f), modifier = Modifier.weight(1f).height(3.dp))
            Text("$pct%", color = Color.White, fontSize = 9.sp, modifier = Modifier.padding(start = 4.dp))
          }
        } else {
          CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
        }
      }
      e.isFailed -> Box(Modifier.align(Alignment.BottomEnd).padding(4.dp)) {
        Icon(painterResource(MR.images.ic_close), contentDescription = "Failed", tint = Color(0xFFE57373), modifier = Modifier.size(16.dp))
      }
      !e.isLocal -> Box(Modifier.align(Alignment.BottomEnd).padding(4.dp)) {
        Icon(painterResource(MR.images.ic_download), contentDescription = "Not downloaded", tint = Color.White, modifier = Modifier.size(16.dp))
      }
    }
    if (isSelected) {
      Box(Modifier.fillMaxSize().background(MaterialTheme.colors.primary.copy(alpha = 0.25f)))
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListRow(e: UnifiedFileEntry, isSelected: Boolean, onOpen: () -> Unit, onToggle: () -> Unit) {
  val liveProg = e.fileId?.let { chatModel.fileProgress[it] }
  val downloading = liveProg != null || e.isDownloading
  val livePct = when {
    liveProg != null && liveProg.second > 0 -> (liveProg.first * 100 / liveProg.second).toInt().coerceIn(0, 100)
    else -> e.progressPct
  }
  Row(
    Modifier.fillMaxWidth()
      .combinedClickable(onClick = { onToggle() }, onDoubleClick = { onOpen() })
      .background(if (isSelected) MaterialTheme.colors.primary.copy(alpha = 0.1f) else Color.Transparent)
      .padding(horizontal = 12.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Icon(categoryIcon(e.category), contentDescription = null, tint = MaterialTheme.colors.secondary, modifier = Modifier.size(28.dp))
    Column(Modifier.padding(start = 12.dp).weight(1f)) {
      Text(e.fileName, fontSize = 14.sp, maxLines = 1)
      Text(
        "${e.chatName} · ${e.senderName} · ${formatBytes(e.fileSize)}",
        fontSize = 12.sp, color = MaterialTheme.colors.secondary, maxLines = 1
      )
      // While downloading, show exactly how much of THIS file has arrived and its live speed —
      // "3.2 MB / 8.0 MB · 1.1 MB/s" — so per-file progress is visible without opening anything.
      if (downloading && liveProg != null) {
        val spd = e.fileId?.let { chatModel.fileSpeed[it] } ?: 0L
        val speedPart = if (spd > 0) " · ${formatSpeed(spd)}" else ""
        Text(
          "${formatBytes(liveProg.first)} / ${formatBytes(liveProg.second)}$speedPart",
          fontSize = 11.sp, color = MaterialTheme.colors.primary, maxLines = 1
        )
      }
    }
    when {
      downloading -> {
        val pct = livePct
        if (pct != null) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(progress = pct / 100f, color = MaterialTheme.colors.primary, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            Text("$pct%", fontSize = 11.sp, color = MaterialTheme.colors.secondary, modifier = Modifier.padding(start = 4.dp))
          }
        } else {
          CircularProgressIndicator(color = MaterialTheme.colors.primary, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        }
      }
      e.isFailed -> Icon(painterResource(MR.images.ic_close), contentDescription = "Failed", tint = Color(0xFFE57373), modifier = Modifier.size(18.dp))
      !e.isLocal -> Icon(painterResource(MR.images.ic_download), contentDescription = "Not downloaded", tint = MaterialTheme.colors.secondary, modifier = Modifier.size(18.dp))
    }
    if (e.fileId != null && LocalFileVault.isFavorite(e.fileId!!)) {
      Icon(painterResource(MR.images.ic_star_filled), contentDescription = "Favorite", tint = MaterialTheme.colors.primary, modifier = Modifier.size(18.dp).padding(start = 6.dp))
    }
  }
}

@Composable
private fun categoryIcon(category: FileCategory) = when (category) {
  FileCategory.Image -> painterResource(MR.images.ic_folder_open)
  FileCategory.Video -> painterResource(MR.images.ic_play_arrow_filled)
  FileCategory.Voice -> painterResource(MR.images.ic_mic)
  FileCategory.File -> painterResource(MR.images.ic_attach_file_filled)
}

fun formatBytes(bytes: Long): String {
  if (bytes <= 0) return "0 B"
  val units = arrayOf("B", "KB", "MB", "GB")
  var value = bytes.toDouble()
  var unit = 0
  while (value >= 1024 && unit < units.size - 1) { value /= 1024; unit++ }
  return if (unit == 0) "${bytes} B" else "%.1f %s".format(value, units[unit])
}

fun formatSpeed(bytesPerSec: Long): String = if (bytesPerSec <= 0) "" else "${formatBytes(bytesPerSec)}/s"

// Downloads only what won't leak the user's IP. Files already reachable via trusted relays start
// downloading; any file whose download would expose the IP to unknown XFTP relays is NOT downloaded
// and its item is deleted locally (this device only — sender/other devices unaffected), per the
// user's chosen policy. No approval popup is ever shown. Returns a short summary for feedback.
suspend fun downloadRespectingPrivacy(entries: List<UnifiedFileEntry>): String {
  val ids = entries.mapNotNull { it.fileId }
  if (ids.isEmpty()) return ""
  val rhId = entries.firstOrNull()?.chat?.remoteHostId
  val user = chatModel.currentUser.value ?: return ""
  val notApproved = chatModel.controller.receiveFilesSkippingUnapproved(rhId, user, ids).toHashSet()
  if (notApproved.isNotEmpty()) {
    val remove = entries.filter { val fid = it.fileId; fid != null && fid in notApproved }
    remove.chunked(100).forEach { chunk ->
      chunk.groupBy { it.chat }.forEach { (chat, es) ->
        chatModel.controller.apiDeleteChatItems(
          chat.remoteHostId, chat.chatInfo.chatType, chat.chatInfo.apiId, null,
          es.map { it.item.id }, CIDeleteMode.cidmInternal
        )
      }
    }
  }
  val started = ids.size - notApproved.size
  return "Downloading $started" + if (notApproved.isNotEmpty()) " · removed ${notApproved.size} that would expose your IP" else ""
}

// Pause = cancel the in-flight XFTP receive. SimpleX has no true byte-level resume, so a paused file
// returns to pending and can be started again with Download. Local-only, senders unaffected.
suspend fun pauseDownloads(entries: List<UnifiedFileEntry>): String {
  val user = chatModel.currentUser.value ?: return ""
  var n = 0
  for (e in entries) {
    val fid = e.fileId ?: continue
    if (!(e.isDownloading || fid in chatModel.fileProgress)) continue
    chatModel.controller.cancelFile(e.chat.remoteHostId, user, fid)
    chatModel.fileProgress.remove(fid)
    n++
  }
  return if (n > 0) "Paused $n" else ""
}

// Stop = cancel the in-flight receive AND drop the item from the download queue on this device, so it
// won't sit around as pending. Stronger than Pause (which leaves it restartable). Local-only: the
// sender and other devices keep their copy, this just abandons the transfer here.
suspend fun stopDownloads(entries: List<UnifiedFileEntry>): String {
  val user = chatModel.currentUser.value ?: return ""
  val active = entries.filter { val fid = it.fileId; fid != null && (it.isDownloading || fid in chatModel.fileProgress) }
  var n = 0
  for (e in active) {
    val fid = e.fileId ?: continue
    chatModel.controller.cancelFile(e.chat.remoteHostId, user, fid)
    chatModel.fileProgress.remove(fid)
    n++
  }
  active.groupBy { it.chat }.forEach { (chat, es) ->
    chatModel.controller.apiDeleteChatItems(
      chat.remoteHostId, chat.chatInfo.chatType, chat.chatInfo.apiId, null,
      es.map { it.item.id }, CIDeleteMode.cidmInternal
    )
  }
  return if (n > 0) "Stopped $n" else ""
}

// --- Batch action bar ----------------------------------------------------------------------------

@Composable
private fun BatchActionBar(scope: CoroutineScope, entries: List<UnifiedFileEntry>, onClear: () -> Unit, onChanged: () -> Unit, reload: suspend (Boolean) -> Unit, onMsg: (String) -> Unit, setBusy: (String?) -> Unit) {
  Surface(elevation = 8.dp, color = MaterialTheme.colors.surface) {
    Row(
      Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text("${entries.size} selected", fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
      Spacer(Modifier.weight(1f))
      WithTooltip("Download selected") {
        IconButton(onClick = {
          scope.launch {
            val es = entries
            onClear() // drop the selection so the bar collapses and tiles show live download state
            onMsg(downloadRespectingPrivacy(es)) // trusted relays download; IP-exposing files skipped + removed
            reload(false) // refresh now -> tiles flip to the downloading spinner; live loop keeps updating
          }
        }) { Icon(painterResource(MR.images.ic_download), contentDescription = "Download") }
      }
      WithTooltip("Pause download") {
        IconButton(onClick = {
          scope.launch {
            val es = entries
            onClear()
            onMsg(pauseDownloads(es))
            reload(false)
          }
        }) { Icon(painterResource(MR.images.ic_pause_filled), contentDescription = "Pause") }
      }
      WithTooltip("Stop download (remove from queue)") {
        IconButton(onClick = {
          scope.launch {
            val es = entries
            onClear()
            onMsg(stopDownloads(es))
            reload(false)
          }
        }) { Icon(painterResource(MR.images.ic_stop_filled), contentDescription = "Stop", tint = MaterialTheme.colors.error) }
      }
      WithTooltip("Favorite / unfavorite") {
        IconButton(onClick = {
          scope.launch {
            val rhId = chatModel.remoteHostId()
            entries.forEach { e -> e.fileId?.let { LocalFileVault.toggleFavorite(rhId, it) } }
            onChanged()
          }
        }) { Icon(painterResource(MR.images.ic_star), contentDescription = "Favorite") }
      }
      WithTooltip("Delete local copy") {
      IconButton(onClick = {
        AlertManager.shared.showAlertDialog(
          title = "Delete ${entries.size} files?",
          text = "This removes the local copy only on this device. Senders and other devices are not affected.",
          confirmText = "Delete",
          destructive = true,
          onConfirm = {
            val es = entries
            onClear()
            // Runs on BrowseTab's scope (survives the bar collapsing). try/finally guarantees the
            // overlay is cleared even if a delete call throws — no more permanent "Deleting 0/N".
            scope.launch {
              val total = es.size
              var done = 0
              setBusy("Deleting 0 / $total…")
              try {
                es.chunked(50).forEach { chunk ->
                  chunk.groupBy { it.chat }.forEach { (chat, group) ->
                    chatModel.controller.apiDeleteChatItems(
                      chat.remoteHostId, chat.chatInfo.chatType, chat.chatInfo.apiId, null,
                      group.map { it.item.id }, CIDeleteMode.cidmInternal
                    )
                    done += group.size
                    setBusy("Deleting $done / $total…")
                  }
                }
                setBusy("Refreshing…")
                reload(false)
              } finally {
                setBusy(null)
              }
            }
          }
        )
      }) { Icon(painterResource(MR.images.ic_delete), contentDescription = "Delete") }
      }
      WithTooltip("Clear selection") {
        IconButton(onClick = onClear) { Icon(painterResource(MR.images.ic_close), contentDescription = "Clear selection") }
      }
    }
  }
}

// --- Duplicates tab --------------------------------------------------------------------------------

@Composable
private fun DuplicatesTab(all: List<UnifiedFileEntry>, onReload: (kotlinx.coroutines.CoroutineScope) -> Unit) {
  val scope = rememberCoroutineScope()
  val groups = remember { mutableStateOf<List<DuplicateGroup>?>(null) }
  val scanning = remember { mutableStateOf(true) }

  LaunchedEffect(all) {
    scanning.value = true
    groups.value = findDuplicates(all)
    scanning.value = false
  }

  when {
    scanning.value -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    groups.value.isNullOrEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Text("No duplicate files found.", color = MaterialTheme.colors.secondary, modifier = Modifier.padding(24.dp))
    }
    else -> {
      val g = groups.value!!
      val totalReclaimable = g.sumOf { it.reclaimableBytes }
      Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
          Text("${g.size} duplicate groups · ${formatBytes(totalReclaimable)} reclaimable", fontSize = 13.sp, color = MaterialTheme.colors.secondary)
          Spacer(Modifier.weight(1f))
          TextButton(onClick = {
            AlertManager.shared.showAlertDialog(
              title = "Clean up ${g.size} duplicate groups?",
              text = "Removes the local copy of every duplicate, keeping the oldest of each. This device only — senders and other devices are not affected.",
              confirmText = "Clean up",
              destructive = true,
              onConfirm = {
                scope.launch {
                  g.forEach { grp -> deleteLocalCopies(grp.removable) }
                  onReload(scope)
                }
              }
            )
          }) { Text("Clean up all") }
        }
        Divider()
        LazyColumn(Modifier.fillMaxSize()) {
          items(g, key = { it.hash }) { grp ->
            DuplicateGroupRow(grp) { scope.launch { deleteLocalCopies(grp.removable); onReload(scope) } }
            Divider()
          }
        }
      }
    }
  }
}

@Composable
private fun DuplicateGroupRow(grp: DuplicateGroup, onCleanup: () -> Unit) {
  Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
    Icon(categoryIcon(grp.keep.category), contentDescription = null, tint = MaterialTheme.colors.secondary)
    Column(Modifier.padding(start = 12.dp).weight(1f)) {
      Text(grp.keep.fileName, fontSize = 14.sp, maxLines = 1)
      Text(
        "${grp.entries.size} copies · keeping oldest · ${formatBytes(grp.reclaimableBytes)} reclaimable",
        fontSize = 12.sp, color = MaterialTheme.colors.secondary
      )
    }
    TextButton(onClick = onCleanup) { Text("Clean up") }
  }
}

// Removes the local file copy only (cidmInternal = this device's copy, sender/other devices
// untouched) — never a network delete, never affects the chat for the other party.
private suspend fun deleteLocalCopies(entries: List<UnifiedFileEntry>) {
  entries.groupBy { it.chat }.forEach { (chat, es) ->
    chatModel.controller.apiDeleteChatItems(
      chat.remoteHostId, chat.chatInfo.chatType, chat.chatInfo.apiId, null,
      es.map { it.item.id }, CIDeleteMode.cidmInternal
    )
  }
}

// --- Storage tab -------------------------------------------------------------------------------

@Composable
private fun StorageTab(all: List<UnifiedFileEntry>, onReload: (CoroutineScope) -> Unit) {
  val scope = rememberCoroutineScope()
  val stats = remember(all) { storageStats(all) }
  val totalBytes = stats.values.sumOf { it.second }
  val totalCount = stats.values.sumOf { it.first }
  val perChat = remember(all) { perChatStorage(all) }
  val budgetDialogFor = remember { mutableStateOf<Chat?>(null) }

  Column(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
      Text("Local storage used", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
      Text("${formatBytes(totalBytes)} across $totalCount downloaded files", fontSize = 13.sp, color = MaterialTheme.colors.secondary)
      Spacer(Modifier.height(16.dp))
      FileCategory.entries.forEach { cat ->
        val (count, bytes) = stats[cat] ?: (0 to 0L)
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
          Icon(categoryIcon(cat), contentDescription = null, tint = MaterialTheme.colors.secondary, modifier = Modifier.size(20.dp))
          Text(cat.name + "s", modifier = Modifier.padding(start = 10.dp).weight(1f), fontSize = 14.sp)
          Text("$count · ${formatBytes(bytes)}", fontSize = 13.sp, color = MaterialTheme.colors.secondary)
        }
        if (totalBytes > 0) {
          LinearProgressIndicator(
            progress = if (totalBytes > 0) bytes.toFloat() / totalBytes.toFloat() else 0f,
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
          )
        }
      }
    }
    Divider()
    Text(
      "Per-chat storage budgets",
      fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
    )
    Text(
      "Set a limit for noisy chats — oldest local files are removed automatically once a chat goes over budget. This device only.",
      fontSize = 12.sp, color = MaterialTheme.colors.secondary,
      modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)
    )
    LazyColumn(Modifier.weight(1f)) {
      items(perChat, key = { it.chat.id }) { info ->
        Row(
          Modifier.fillMaxWidth().clickable { budgetDialogFor.value = info.chat }.padding(horizontal = 16.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Column(Modifier.weight(1f)) {
            Text(info.chat.chatInfo.displayName, fontSize = 14.sp, maxLines = 1)
            val budgetText = if (info.budgetBytes != null) "of ${formatBytes(info.budgetBytes)} budget" else "no budget set"
            Text("${formatBytes(info.totalBytes)} · $budgetText", fontSize = 12.sp, color = MaterialTheme.colors.secondary)
          }
          if (info.budgetBytes != null && info.totalBytes > info.budgetBytes) {
            Text("over budget", fontSize = 11.sp, color = MaterialTheme.colors.error)
          }
        }
        Divider()
      }
    }
    Text(
      "All figures are computed on-device from your local file index. Nothing here is uploaded, and duplicate detection never leaves this device.",
      fontSize = 12.sp, color = MaterialTheme.colors.secondary,
      modifier = Modifier.padding(16.dp)
    )
  }

  val target = budgetDialogFor.value
  if (target != null) {
    SetBudgetDialog(target, onDismiss = { budgetDialogFor.value = null; onReload(scope) })
  }
}

@Composable
private fun SetBudgetDialog(chat: Chat, onDismiss: () -> Unit) {
  val scope = rememberCoroutineScope()
  val current = ChatStorageBudgets.get(chat)
  val text = remember { mutableStateOf(if (current != null) (current / (1024 * 1024)).toString() else "") }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Storage budget — ${chat.chatInfo.displayName}") },
    text = {
      Column {
        Text("Max local storage for this chat, in MB. Oldest local files are auto-removed once exceeded.", fontSize = 13.sp, color = MaterialTheme.colors.secondary)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = text.value, onValueChange = { v -> if (v.all { it.isDigit() }) text.value = v }, placeholder = { Text("e.g. 500") }, singleLine = true)
      }
    },
    confirmButton = {
      TextButton(onClick = {
        val mb = text.value.toLongOrNull()
        scope.launch {
          ChatStorageBudgets.set(chatModel.remoteHostId(), chat, if (mb != null && mb > 0) mb * 1024 * 1024 else null)
          onDismiss()
        }
      }) { Text("Save") }
    },
    dismissButton = {
      Row {
        if (current != null) {
          TextButton(onClick = {
            scope.launch {
              ChatStorageBudgets.set(chatModel.remoteHostId(), chat, null)
              onDismiss()
            }
          }) { Text("Clear") }
        }
        TextButton(onClick = onDismiss) { Text("Cancel") }
      }
    }
  )
}

// --- Insights tab --------------------------------------------------------------------------------

@Composable
private fun InsightsTab(all: List<UnifiedFileEntry>) {
  val local = remember(all) { all.filter { it.isLocal } }
  val duplicates = remember(all) { findDuplicates(all) }
  val health = remember(local, duplicates) { storageHealth(all, duplicates) }
  val ageDist = remember(local) { ageDistribution(all) }
  val largest = remember(local) { local.maxByOrNull { it.fileSize } }
  val totalAgeBytes = ageDist.values.sum().coerceAtLeast(1)

  ColumnWithScrollBar {
    Column(Modifier.padding(16.dp)) {
      Text("Storage health", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
      Row(Modifier.padding(top = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
          "${health.score} / 100",
          fontSize = 24.sp, fontWeight = FontWeight.Bold,
          color = when {
            health.score >= 80 -> MaterialTheme.colors.primary
            health.score >= 50 -> Color(0xFFB8860B)
            else -> MaterialTheme.colors.error
          }
        )
      }
      health.notes.forEach { (note, good) ->
        Text(
          "${if (good) "✓" else "⚠"} $note",
          fontSize = 13.sp,
          color = if (good) MaterialTheme.colors.secondary else MaterialTheme.colors.error,
          modifier = Modifier.padding(vertical = 2.dp)
        )
      }

      Spacer(Modifier.height(20.dp))
      Text("File age distribution", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
      Spacer(Modifier.height(8.dp))
      AgeBucket.entries.forEach { bucket ->
        val bytes = ageDist[bucket] ?: 0L
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
          Text(bucket.label, modifier = Modifier.weight(1f), fontSize = 13.sp)
          Text(formatBytes(bytes), fontSize = 13.sp, color = MaterialTheme.colors.secondary)
        }
        LinearProgressIndicator(
          progress = bytes.toFloat() / totalAgeBytes.toFloat(),
          modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
        )
      }

      Spacer(Modifier.height(20.dp))
      Text("Largest local file", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
      Spacer(Modifier.height(6.dp))
      if (largest != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(categoryIcon(largest.category), contentDescription = null, tint = MaterialTheme.colors.secondary, modifier = Modifier.size(20.dp))
          Column(Modifier.padding(start = 10.dp)) {
            Text(largest.fileName, fontSize = 14.sp, maxLines = 1)
            Text("${formatBytes(largest.fileSize)} · ${largest.chatName}", fontSize = 12.sp, color = MaterialTheme.colors.secondary)
          }
        }
      } else {
        Text("No local files yet.", fontSize = 13.sp, color = MaterialTheme.colors.secondary)
      }

      Spacer(Modifier.height(20.dp))
      Text(
        "Health score and all figures above are computed on-device from your local file index only — nothing here is uploaded or shared.",
        fontSize = 12.sp, color = MaterialTheme.colors.secondary
      )
    }
  }
}
