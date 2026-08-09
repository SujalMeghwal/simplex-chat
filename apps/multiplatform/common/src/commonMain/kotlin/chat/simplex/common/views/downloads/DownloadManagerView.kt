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

private enum class ViewMode { Grid, List }
private enum class GroupMode { None, Chat, Date }
private enum class DateRange(val label: String, val maxAgeDays: Long?) {
  All("All time", null), Today("Today", 1), Week("7 days", 7), Month("30 days", 30), Year("1 year", 365)
}

@Composable
fun DownloadManagerView(close: () -> Unit) {
  val allFiles = remember { mutableStateOf<List<UnifiedFileEntry>>(emptyList()) }
  val loading = remember { mutableStateOf(true) }
  val speedBps = remember { mutableStateOf(0L) } // aggregate download speed (bytes/s), sampled by the live loop
  // True while a reload is in flight, so the periodic reconcile loop doesn't stack a second heavy
  // cross-chat scan on top of one already running.
  val reloading = remember { mutableStateOf(false) }

  // showSpinner=false does an in-place refresh (list stays visible) — used for post-download and the
  // periodic catch-up reloads so the view doesn't flash the full-screen spinner every couple seconds.
  suspend fun reload(showSpinner: Boolean = true) {
    reloading.value = true
    try {
      if (showSpinner) loading.value = true
      val rhId = chatModel.remoteHostId()
      LocalFileVault.load(rhId)
      // Storage budgets are deliberately NOT auto-enforced here. Silently deleting the oldest local
      // media every time this screen opens (or every few seconds during a download) is unconfirmed
      // data loss — and, run over the content-deduped list, it would delete exactly the downloaded
      // copy the user can open. Budget enforcement must be an explicit, user-confirmed action.
      val files = loadAllFilesAcrossChats()
      allFiles.value = files
      // Reconcile the live-progress overlays with reality: keep only files the core still reports as
      // actively receiving. A transfer that finished, failed, or was cancelled between events would
      // otherwise leave a stale entry — the "stuck at 0 / stuck at 75%" tiles the user saw. After this,
      // a stalled tile means the core genuinely still lists it as transferring (a real stall to retry).
      val active = allFiles.value.filter { it.isDownloading }.mapNotNull { it.fileId }.toHashSet()
      chatModel.fileProgress.keys.retainAll(active)
      chatModel.fileSpeed.keys.retainAll(active)
    } finally {
      if (showSpinner) loading.value = false
      reloading.value = false
    }
  }

  LaunchedEffect(Unit) {
    // Reset content filters if the active user profile changed since this screen was last open, so one
    // profile's category/search/collection selection never carries into another profile's file list.
    BrowseState.onUser(chatModel.currentUser.value?.userId)
    reload()
  }

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
      if (chatModel.fileProgress.isNotEmpty() && !reloading.value) reload(false)
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
    Divider()
    if (loading.value) {
      Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MaterialTheme.colors.primary)
      }
    } else {
      BrowseTab(allFiles.value) { showSpinner -> reload(showSpinner) }
    }
  }
}

// --- Browse tab: filters, search, sort, grid/list, multi-select, batch actions ------------------

// Filter/sort/view selections live in this holder, NOT in composable `remember`, so they survive the
// fullscreen media viewer being pushed on top of the Download Manager. Opening an image/video pushes
// a new modal on the same ModalManager.fullscreen stack, which disposes this screen's composition;
// with `remember` the filters snapped back to "All" on return (the reported bug). Object-level
// snapshot state persists across that push/pop.
private object BrowseState {
  val category = mutableStateOf<FileCategory?>(null)
  val status = mutableStateOf(DownloadStatusFilter.All)
  val favoritesOnly = mutableStateOf(false)
  val sort = mutableStateOf(SortOrder.Newest)
  val viewMode = mutableStateOf(ViewMode.Grid)
  val query = mutableStateOf("")
  val collection = mutableStateOf<String?>(null) // active collection filter, null = all
  val dateRange = mutableStateOf(DateRange.All)
  val group = mutableStateOf(GroupMode.None)

  // This state is process-global (survives the fullscreen viewer disposing this screen — see the note
  // above). That persistence must NOT cross user profiles: a collection name or search string from
  // profile A is meaningless — and mildly leaky — under profile B. Reset the content filters whenever
  // the active user changes. viewMode/sort are harmless display prefs, kept across profiles.
  private var lastUserId: Long? = null
  fun onUser(userId: Long?) {
    if (userId != lastUserId) {
      category.value = null
      status.value = DownloadStatusFilter.All
      favoritesOnly.value = false
      query.value = ""
      collection.value = null
      dateRange.value = DateRange.All
      group.value = GroupMode.None
      lastUserId = userId
    }
  }
}

@Composable
private fun BrowseTab(all: List<UnifiedFileEntry>, reload: suspend (Boolean) -> Unit) {
  val scope = rememberCoroutineScope()
  val actionMsg = remember { mutableStateOf("") }
  val busy = remember { mutableStateOf<String?>(null) } // non-null while a long delete/cleanup runs
  val category = BrowseState.category
  val status = BrowseState.status
  val favoritesOnly = BrowseState.favoritesOnly
  val sort = BrowseState.sort
  val viewMode = BrowseState.viewMode
  val query = BrowseState.query
  val collection = BrowseState.collection
  val dateRange = BrowseState.dateRange
  val group = BrowseState.group
  val selected = remember { mutableStateListOf<Long>() }
  val favToggle = remember { mutableStateOf(0) } // bump to force favorite-icon recomposition
  val collectionsBump = remember { mutableStateOf(0) } // bump after collection membership changes
  val collectionNames = remember(collectionsBump.value, all) { LocalFileVault.collectionNames() }

  val filtered = remember(all, category.value, status.value, favoritesOnly.value, query.value, sort.value, favToggle.value, collection.value, dateRange.value, collectionsBump.value) {
    val now = kotlinx.datetime.Clock.System.now()
    all.applyFilters(category.value, status.value, favoritesOnly.value, collection.value, query.value)
      .filter { e -> dateRange.value.maxAgeDays?.let { (now - e.createdAt).inWholeDays <= it } ?: true }
      .sortedBy(sort.value)
  }
  val byId = remember(filtered) { filtered.associateBy { it.fileId } }

  // Non-null while the add-to-collection dialog is open (for one entry or the whole selection).
  val collectionTarget = remember { mutableStateOf<List<UnifiedFileEntry>?>(null) }
  val fileActions = remember {
    FileEntryActions(
      open = { e -> if (e.isLocal) openEntryFile(e) else scope.launch { actionMsg.value = downloadRespectingPrivacy(listOf(e)); reload(false) } },
      reveal = { e -> revealEntry(e) },
      favorite = { e -> scope.launch { e.fileId?.let { LocalFileVault.toggleFavorite(chatModel.remoteHostId(), it) }; favToggle.value++ } },
      retry = { e -> scope.launch { actionMsg.value = downloadRespectingPrivacy(listOf(e)); reload(false) } },
      delete = { e ->
        AlertManager.shared.showAlertDialog(
          title = "Delete file?",
          text = "Removes the local copy on this device only. Senders and other devices are not affected.",
          confirmText = "Delete", destructive = true,
          onConfirm = { scope.launch { busy.value = "Deleting…"; try { deleteEntriesLocal(listOf(e)); reload(false) } finally { busy.value = null } } }
        )
      },
      addToCollection = { e -> collectionTarget.value = listOf(e) },
    )
  }

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
    val mediaEntries = all.filter { it.category == FileCategory.Image || it.category == FileCategory.Video }
    val items = mediaEntries.map { it.item }.sortedBy { it.meta.createdAt }
    val entryByItemId = mediaEntries.associateBy { it.item.id }
    ModalManager.fullscreen.showCustomModal { gClose ->
      openGalleryModal(
        { downloadedOnly ->
          providerForGallery(
            items, entry.item.id, downloadedOnly,
            // Delete key: remove this file from this device and from the download list.
            deleteMedia = { ci -> entryByItemId[ci.id]?.let { deleteEntriesLocal(listOf(it)); reload(false) } }
          ) {}
        },
        gClose
      )
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

    // All filters on one horizontally-scrollable line: category · status · date-range · collections,
    // separated by thin dividers. One tap each. (Favorites filter removed per request.)
    LazyRow(
      Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      item { FilterChip("All", category.value == null) { category.value = null } }
      item { FilterChip("Images", category.value == FileCategory.Image) { category.value = FileCategory.Image } }
      item { FilterChip("Videos", category.value == FileCategory.Video) { category.value = FileCategory.Video } }
      item { FilterChip("Voice", category.value == FileCategory.Voice) { category.value = FileCategory.Voice } }
      item { FilterChip("Documents", category.value == FileCategory.File) { category.value = FileCategory.File } }
      item { Divider(Modifier.width(1.dp).height(22.dp)) }
      item { FilterChip("Downloaded", status.value == DownloadStatusFilter.Downloaded) { status.value = if (status.value == DownloadStatusFilter.Downloaded) DownloadStatusFilter.All else DownloadStatusFilter.Downloaded } }
      item { FilterChip("Downloading", status.value == DownloadStatusFilter.Downloading) { status.value = if (status.value == DownloadStatusFilter.Downloading) DownloadStatusFilter.All else DownloadStatusFilter.Downloading } }
      item { FilterChip("Not downloaded", status.value == DownloadStatusFilter.Pending) { status.value = if (status.value == DownloadStatusFilter.Pending) DownloadStatusFilter.All else DownloadStatusFilter.Pending } }
      item { FilterChip("Failed", status.value == DownloadStatusFilter.Failed) { status.value = if (status.value == DownloadStatusFilter.Failed) DownloadStatusFilter.All else DownloadStatusFilter.Failed } }
      item { Divider(Modifier.width(1.dp).height(22.dp)) }
      items(DateRange.entries) { r -> FilterChip(r.label, dateRange.value == r) { dateRange.value = r } }
      if (collectionNames.isNotEmpty()) {
        item { Divider(Modifier.width(1.dp).height(22.dp)) }
        item { FilterChip("All collections", collection.value == null) { collection.value = null } }
        items(collectionNames) { name -> FilterChip("▤ $name", collection.value == name) { collection.value = if (collection.value == name) null else name } }
      }
    }

    // Sort + group + view-mode toggle, as bordered pills grouped in a faint toolbar bar so they read
    // as controls instead of loose blue links floating on black.
    Surface(
      color = MaterialTheme.colors.onSurface.copy(alpha = 0.03f),
      shape = RoundedCornerShape(12.dp),
      modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
      Row(
        Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        SortDropdown(sort.value) { sort.value = it }
        GroupDropdown(group.value) { group.value = it }
        Spacer(Modifier.weight(1f))
        ToolbarPill(
          if (viewMode.value == ViewMode.Grid) "List view" else "Grid view",
          onClick = { viewMode.value = if (viewMode.value == ViewMode.Grid) ViewMode.List else ViewMode.Grid },
          caret = false
        )
      }
    }

    // Bulk actions — pick many at once, or grab everything pending, without tapping each tile.
    val filteredIds = remember(filtered) { filtered.mapNotNull { it.fileId } }
    val pending = remember(filtered) { filtered.filter { it.isPending } }
    val failed = remember(filtered) { filtered.filter { it.isFailed } }
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
      if (failed.isNotEmpty()) {
        TextButton(onClick = {
          scope.launch { actionMsg.value = downloadRespectingPrivacy(failed); reload(false) }
        }) { Text("Retry failed (${failed.size})") }
      }
    }
    if (actionMsg.value.isNotEmpty()) {
      Text(actionMsg.value, fontSize = 11.sp, color = MaterialTheme.colors.primary, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
    }
    Divider()

    Box(Modifier.weight(1f)) {
      when {
        filtered.isEmpty() -> {
          // Tell apart "you truly have nothing" from "your filters hid everything" — otherwise a busy
          // download queue behind a Today/Downloaded filter reads as an empty, broken screen.
          val filtersActive = category.value != null || status.value != DownloadStatusFilter.All ||
            favoritesOnly.value || collection.value != null || dateRange.value != DateRange.All || query.value.isNotBlank()
          Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
              Icon(
                painterResource(if (filtersActive) MR.images.ic_search else MR.images.ic_download),
                contentDescription = null,
                tint = MaterialTheme.colors.secondary.copy(alpha = 0.5f),
                modifier = Modifier.size(48.dp).padding(bottom = 12.dp)
              )
              Text(
                when {
                  !filtersActive -> "No files here yet. Images, videos, voice messages, and attachments from your chats show up here once you receive them."
                  all.isNotEmpty() -> "No files match your filters. You have ${all.size} file(s) — try widening or clearing the filters."
                  else -> "No files here yet."
                },
                color = MaterialTheme.colors.secondary,
                textAlign = TextAlign.Center
              )
              if (filtersActive) {
                TextButton(
                  onClick = {
                    category.value = null; status.value = DownloadStatusFilter.All; favoritesOnly.value = false
                    collection.value = null; dateRange.value = DateRange.All; query.value = ""
                  },
                  modifier = Modifier.padding(top = 8.dp)
                ) { Text("Clear filters") }
              }
            }
          }
        }
        // Grouping only makes sense with headers, so any active grouping renders as a list.
        viewMode.value == ViewMode.Grid && group.value == GroupMode.None ->
          FileGrid(filtered, selected, fileActions, onOpen = { openItem(it) }) { toggleSelect(it) }
        else -> FileList(filtered, selected, fileActions, group.value, onOpen = { openItem(it) }) { toggleSelect(it) }
      }
    }

    // Batch action bar — only appears once something is selected, keeps the default view uncluttered.
    // NOTE: the bar runs its long operations on BrowseTab's `scope` (passed in), NOT its own — clearing
    // the selection removes the bar, and a coroutine started on the bar's own scope would be cancelled
    // mid-delete, freezing the progress overlay. BrowseTab's scope outlives the selection.
    if (selected.isNotEmpty()) {
      BatchActionBar(scope, selected.mapNotNull { byId[it] }, onClear = { selected.clear() }, onChanged = { favToggle.value++ }, reload = reload, onMsg = { actionMsg.value = it }, setBusy = { busy.value = it }, onAddToCollection = { es -> collectionTarget.value = es })
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

    val ct = collectionTarget.value
    if (ct != null) {
      AddToCollectionDialog(
        existing = collectionNames,
        count = ct.size,
        onDismiss = { collectionTarget.value = null },
        onPick = { name ->
          scope.launch {
            addEntriesToCollection(name, ct)
            collectionsBump.value++
            actionMsg.value = "Added ${ct.size} to \"$name\""
            collectionTarget.value = null
          }
        }
      )
    }
  }
}

@Composable
private fun AddToCollectionDialog(existing: List<String>, count: Int, onDismiss: () -> Unit, onPick: (String) -> Unit) {
  val newName = remember { mutableStateOf("") }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Add $count file${if (count == 1) "" else "s"} to a collection") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (existing.isNotEmpty()) {
          Text("PICK AN EXISTING COLLECTION", fontSize = 11.sp, color = MaterialTheme.colors.secondary, letterSpacing = 0.8.sp)
          existing.forEach { name ->
            Surface(
              color = MaterialTheme.colors.onSurface.copy(alpha = 0.04f),
              shape = RoundedCornerShape(10.dp),
              modifier = Modifier.fillMaxWidth()
            ) {
              Row(
                Modifier.clickable { onPick(name) }.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
              ) {
                Icon(painterResource(MR.images.ic_folder_closed), null, Modifier.size(20.dp), tint = MaterialTheme.colors.primary)
                Text(name, fontSize = 15.sp, modifier = Modifier.padding(start = 10.dp).weight(1f))
                Icon(painterResource(MR.images.ic_add), null, Modifier.size(18.dp), tint = MaterialTheme.colors.secondary)
              }
            }
          }
          Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Divider(Modifier.weight(1f))
            Text("  or  ", fontSize = 12.sp, color = MaterialTheme.colors.secondary)
            Divider(Modifier.weight(1f))
          }
        }
        Text("CREATE A NEW COLLECTION", fontSize = 11.sp, color = MaterialTheme.colors.secondary, letterSpacing = 0.8.sp)
        OutlinedTextField(
          value = newName.value,
          onValueChange = { newName.value = it },
          placeholder = { Text("Collection name") },
          leadingIcon = { Icon(painterResource(MR.images.ic_folder_open), null, Modifier.size(20.dp)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth()
        )
      }
    },
    confirmButton = {
      TextButton(enabled = newName.value.isNotBlank(), onClick = { onPick(newName.value.trim()) }) { Text("Create & add") }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
  )
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
  Surface(
    // Unselected chips get a faint fill (not bare surface, which vanishes on the black background)
    // plus a soft border; selected chips fill with the accent. Reads as a proper pill either way.
    color = if (selected) MaterialTheme.colors.primary else MaterialTheme.colors.onSurface.copy(alpha = 0.06f),
    contentColor = if (selected) Color.White else MaterialTheme.colors.onSurface,
    shape = RoundedCornerShape(6.dp),
    border = if (!selected) BorderStroke(1.dp, MaterialTheme.colors.secondary.copy(alpha = 0.22f)) else null,
    modifier = Modifier.clickable(onClick = onClick)
  ) {
    Text(
      label,
      fontSize = 13.sp,
      fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
    )
  }
}

// Compact toolbar control (Sort / Group / view toggle). Bordered pill so these read as buttons on
// the black content area instead of bare blue links.
@Composable
private fun ToolbarPill(label: String, onClick: () -> Unit, caret: Boolean = true) {
  Surface(
    color = MaterialTheme.colors.onSurface.copy(alpha = 0.05f),
    shape = RoundedCornerShape(10.dp),
    border = BorderStroke(1.dp, MaterialTheme.colors.secondary.copy(alpha = 0.2f)),
    modifier = Modifier.clickable(onClick = onClick)
  ) {
    Text(
      label + if (caret) "  ▾" else "",
      fontSize = 13.sp,
      color = MaterialTheme.colors.onSurface,
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
    )
  }
}

@Composable
private fun SortDropdown(current: SortOrder, onSelect: (SortOrder) -> Unit) {
  val expanded = remember { mutableStateOf(false) }
  Box {
    ToolbarPill("Sort: ${sortLabel(current)}", onClick = { expanded.value = true })
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
private fun FileGrid(entries: List<UnifiedFileEntry>, selected: SnapshotStateList<Long>, actions: FileEntryActions, onOpen: (UnifiedFileEntry) -> Unit, onToggle: (Long) -> Unit) {
  LazyVerticalGrid(
    columns = GridCells.Adaptive(minSize = 168.dp),
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(8.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp)
  ) {
    items(entries, key = { it.item.id }) { e ->
      GridTile(e, selected.contains(e.fileId), actions, onOpen = { onOpen(e) }) { onToggle(e.fileId ?: return@GridTile) }
    }
  }
}

// Buckets entries for the group-by-date header. Uses the same coarse buckets people expect.
private fun dateBucket(e: UnifiedFileEntry): String {
  val days = (kotlinx.datetime.Clock.System.now() - e.createdAt).inWholeDays
  return when {
    days <= 0 -> "Today"
    days <= 1 -> "Yesterday"
    days <= 7 -> "This week"
    days <= 30 -> "This month"
    days <= 365 -> "This year"
    else -> "Older"
  }
}

@Composable
private fun FileList(entries: List<UnifiedFileEntry>, selected: SnapshotStateList<Long>, actions: FileEntryActions, group: GroupMode, onOpen: (UnifiedFileEntry) -> Unit, onToggle: (Long) -> Unit) {
  // Preserve the incoming sort order within each group by using a LinkedHashMap.
  val groups: List<Pair<String?, List<UnifiedFileEntry>>> = when (group) {
    GroupMode.None -> listOf(null to entries)
    GroupMode.Chat -> entries.groupByTo(LinkedHashMap()) { it.chatName }.map { it.key to it.value }
    GroupMode.Date -> entries.groupByTo(LinkedHashMap()) { dateBucket(it) }.map { it.key to it.value }
  }
  LazyColumn(Modifier.fillMaxSize()) {
    groups.forEach { (header, groupEntries) ->
      if (header != null) {
        item(key = "hdr_$header") {
          Text(
            "$header · ${groupEntries.size}",
            fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colors.secondary,
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colors.background).padding(horizontal = 12.dp, vertical = 6.dp)
          )
        }
      }
      items(groupEntries, key = { it.item.id }) { e ->
        ListRow(e, selected.contains(e.fileId), actions, onOpen = { onOpen(e) }) { onToggle(e.fileId ?: return@ListRow) }
        Divider()
      }
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridTile(e: UnifiedFileEntry, isSelected: Boolean, actions: FileEntryActions, onOpen: () -> Unit, onToggle: () -> Unit) {
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
  val menu = remember { mutableStateOf(false) }
  val base = Modifier.aspectRatio(1f).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.15f))
  Box(
    (if (isSelected) base.border(3.dp, MaterialTheme.colors.primary, RoundedCornerShape(6.dp)) else base)
      .combinedClickable(onClick = { onToggle() }, onDoubleClick = { onOpen() }, onLongClick = { menu.value = true })
      .onRightClick { menu.value = true },
    contentAlignment = Alignment.Center
  ) {
    EntryContextMenu(e, menu, actions)
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
private fun ListRow(e: UnifiedFileEntry, isSelected: Boolean, actions: FileEntryActions, onOpen: () -> Unit, onToggle: () -> Unit) {
  val liveProg = e.fileId?.let { chatModel.fileProgress[it] }
  val downloading = liveProg != null || e.isDownloading
  val livePct = when {
    liveProg != null && liveProg.second > 0 -> (liveProg.first * 100 / liveProg.second).toInt().coerceIn(0, 100)
    else -> e.progressPct
  }
  val menu = remember { mutableStateOf(false) }
  Row(
    Modifier.fillMaxWidth()
      .combinedClickable(onClick = { onToggle() }, onDoubleClick = { onOpen() }, onLongClick = { menu.value = true })
      .onRightClick { menu.value = true }
      .background(if (isSelected) MaterialTheme.colors.primary.copy(alpha = 0.1f) else Color.Transparent)
      .padding(horizontal = 12.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    EntryContextMenu(e, menu, actions)
    Icon(categoryIcon(e.category), contentDescription = null, tint = MaterialTheme.colors.secondary, modifier = Modifier.size(28.dp))
    Column(Modifier.padding(start = 12.dp).weight(1f)) {
      Text(e.fileName, fontSize = 14.sp, maxLines = 1)
      Text(
        "${e.chatName} · ${e.senderName} · ${formatBytes(e.fileSize)}",
        fontSize = 12.sp, color = MaterialTheme.colors.secondary, maxLines = 1
      )
      // While downloading, show exactly how much of THIS file has arrived, its live speed, and ETA —
      // "3.2 MB / 8.0 MB · 1.1 MB/s · 4s left" — so per-file progress is visible without opening.
      if (downloading && liveProg != null) {
        val spd = e.fileId?.let { chatModel.fileSpeed[it] } ?: 0L
        val speedPart = if (spd > 0) " · ${formatSpeed(spd)}" else ""
        val eta = formatEta(liveProg.second - liveProg.first, spd)
        val etaPart = if (eta.isNotEmpty()) " · $eta" else ""
        Text(
          "${formatBytes(liveProg.first)} / ${formatBytes(liveProg.second)}$speedPart$etaPart",
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

// Rough time-remaining from live speed. Empty when we can't estimate (no speed / already done).
fun formatEta(remainingBytes: Long, bytesPerSec: Long): String {
  if (bytesPerSec <= 0 || remainingBytes <= 0) return ""
  val secs = remainingBytes / bytesPerSec
  return when {
    secs < 60 -> "${secs}s left"
    secs < 3600 -> "${secs / 60}m ${secs % 60}s left"
    else -> "${secs / 3600}h ${(secs % 3600) / 60}m left"
  }
}

// Open a downloaded file in the OS default app. No-op if the file isn't local yet.
fun openEntryFile(e: UnifiedFileEntry) {
  val fs = e.item.file?.fileSource ?: return
  if (e.isLocal) openFile(fs)
}

// Show the file's folder in the system file manager (desktop only; Android has no folder concept here).
fun revealEntry(e: UnifiedFileEntry) {
  val path = e.item.file?.let { getLoadedFilePath(it) } ?: return
  val parent = java.io.File(path).parentFile ?: return
  desktopOpenDir(parent)
}

// Downloads only what won't leak the user's IP. Files already reachable via trusted relays start
// downloading; any file whose download would need an unknown XFTP relay (exposing the user's IP) is
// simply left untouched — still offered, nothing downloaded, nothing deleted. Skipping already keeps
// the IP private (the download never happens); the old behaviour also DELETED those items locally,
// which is surprising, unconfirmed data loss on what the user invoked as a "download" action. No
// approval popup is shown. Returns a short summary for feedback.
suspend fun downloadRespectingPrivacy(entries: List<UnifiedFileEntry>): String {
  val ids = entries.mapNotNull { it.fileId }
  if (ids.isEmpty()) return ""
  val rhId = entries.firstOrNull()?.chat?.remoteHostId
  val user = chatModel.currentUser.value ?: return ""
  val notApproved = chatModel.controller.receiveFilesSkippingUnapproved(rhId, user, ids).toHashSet()
  val started = ids.size - notApproved.size
  return when {
    notApproved.isEmpty() -> if (started > 0) "Downloading $started" else ""
    started > 0 -> "Downloading $started · skipped ${notApproved.size} that would expose your IP"
    else -> "Skipped ${notApproved.size} that would expose your IP"
  }
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

// Local-only delete of the given files' copies on this device (chunked, grouped per chat).
suspend fun deleteEntriesLocal(entries: List<UnifiedFileEntry>) {
  entries.chunked(50).forEach { chunk ->
    chunk.groupBy { it.chat }.forEach { (chat, group) ->
      chatModel.controller.apiDeleteChatItems(
        chat.remoteHostId, chat.chatInfo.chatType, chat.chatInfo.apiId, null,
        group.map { it.item.id }, CIDeleteMode.cidmInternal
      )
    }
  }
}

// Add files to a named user collection (created on first use). Stored in chat.db, this device only.
suspend fun addEntriesToCollection(name: String, entries: List<UnifiedFileEntry>) {
  val rhId = chatModel.remoteHostId()
  entries.forEach { e -> e.fileId?.let { LocalFileVault.addToCollection(rhId, name, it) } }
}

// Per-file actions wired once in BrowseTab and passed down to every tile/row context menu.
class FileEntryActions(
  val open: (UnifiedFileEntry) -> Unit,
  val reveal: (UnifiedFileEntry) -> Unit,
  val favorite: (UnifiedFileEntry) -> Unit,
  val retry: (UnifiedFileEntry) -> Unit,
  val delete: (UnifiedFileEntry) -> Unit,
  val addToCollection: (UnifiedFileEntry) -> Unit,
)

@Composable
private fun GroupDropdown(current: GroupMode, onSelect: (GroupMode) -> Unit) {
  val expanded = remember { mutableStateOf(false) }
  Box {
    ToolbarPill("Group: " + when (current) { GroupMode.None -> "None"; GroupMode.Chat -> "Chat"; GroupMode.Date -> "Date" }, onClick = { expanded.value = true })
    DropdownMenu(expanded = expanded.value, onDismissRequest = { expanded.value = false }) {
      GroupMode.entries.forEach { g ->
        DropdownMenuItem(onClick = { onSelect(g); expanded.value = false }) {
          Text(when (g) { GroupMode.None -> "None"; GroupMode.Chat -> "By chat"; GroupMode.Date -> "By date" })
        }
      }
    }
  }
}

// Right-click / long-press menu shared by grid tiles and list rows.
@Composable
private fun EntryContextMenu(e: UnifiedFileEntry, expanded: MutableState<Boolean>, actions: FileEntryActions) {
  DropdownMenu(expanded = expanded.value, onDismissRequest = { expanded.value = false }) {
    if (e.isLocal && (e.category == FileCategory.Image || e.category == FileCategory.Video || e.category == FileCategory.File)) {
      DropdownMenuItem(onClick = { expanded.value = false; actions.open(e) }) { Text("Open") }
      if (appPlatform.isDesktop) {
        DropdownMenuItem(onClick = { expanded.value = false; actions.reveal(e) }) { Text("Reveal in folder") }
      }
    }
    if (!e.isLocal && e.item.file != null) {
      DropdownMenuItem(onClick = { expanded.value = false; actions.retry(e) }) { Text(if (e.isFailed) "Retry download" else "Download") }
    }
    DropdownMenuItem(onClick = { expanded.value = false; actions.favorite(e) }) {
      Text(if (e.fileId != null && LocalFileVault.isFavorite(e.fileId!!)) "Unfavorite" else "Favorite")
    }
    DropdownMenuItem(onClick = { expanded.value = false; actions.addToCollection(e) }) { Text("Add to collection…") }
    DropdownMenuItem(onClick = { expanded.value = false; actions.delete(e) }) { Text("Delete local copy") }
  }
}

// --- Batch action bar ----------------------------------------------------------------------------

@Composable
private fun BatchActionBar(scope: CoroutineScope, entries: List<UnifiedFileEntry>, onClear: () -> Unit, onChanged: () -> Unit, reload: suspend (Boolean) -> Unit, onMsg: (String) -> Unit, setBusy: (String?) -> Unit, onAddToCollection: (List<UnifiedFileEntry>) -> Unit) {
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
      WithTooltip("Add to collection") {
        IconButton(onClick = { onAddToCollection(entries) }) { Icon(painterResource(MR.images.ic_folder_open), contentDescription = "Add to collection") }
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
