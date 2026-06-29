package chat.simplex.common.views.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
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
import chat.simplex.common.platform.base64ToBitmap
import chat.simplex.common.platform.chatModel
import chat.simplex.common.platform.getLoadedFilePath
import chat.simplex.common.views.chat.item.openGalleryModal
import chat.simplex.common.views.helpers.ModalManager
import chat.simplex.res.MR
import dev.icerock.moko.resources.compose.painterResource
import kotlinx.datetime.Clock
import kotlin.time.Duration.Companion.hours

// Browser that shows only already-downloaded media from the WHOLE chat history (not just the
// loaded window), split into Images and Videos sections. It pulls all image/video items via
// apiGetChat pagination (read-only — doesn't disturb the open chat), keeps the downloaded ones,
// and shows their embedded previews. Tapping opens the fullscreen gallery for that item.
@Composable
fun DownloadedMediaView(rhId: Long?, chatInfo: ChatInfo, close: () -> Unit) {
  val images = remember { mutableStateOf<List<ChatItem>>(emptyList()) }
  val videos = remember { mutableStateOf<List<ChatItem>>(emptyList()) }
  val loading = remember { mutableStateOf(true) }

  LaunchedEffect(Unit) {
    val imgs = loadAllDownloadedMedia(rhId, chatInfo, MsgContentTag.Image)
    val vids = loadAllDownloadedMedia(rhId, chatInfo, MsgContentTag.Video)
    images.value = imgs
    videos.value = vids
    loading.value = false
  }

  // Combined chronological list (oldest -> newest) so the gallery provider can locate a tapped item.
  val galleryItems by remember {
    derivedStateOf { (images.value + videos.value).sortedBy { it.meta.createdAt } }
  }
  fun openItem(item: ChatItem) {
    val items = galleryItems
    ModalManager.fullscreen.showCustomModal { gClose ->
      openGalleryModal({ downloadedOnly -> providerForGallery(items, item.id, downloadedOnly) {} }, gClose)
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
      Text("Downloaded", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp))
      if (!loading.value) {
        Text(
          "  ${images.value.size + videos.value.size} items",
          fontSize = 13.sp,
          color = MaterialTheme.colors.secondary
        )
      }
    }
    Divider()
    when {
      loading.value -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MaterialTheme.colors.primary)
      }
      images.value.isEmpty() && videos.value.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
          "No downloaded media in this chat.",
          color = MaterialTheme.colors.secondary,
          textAlign = TextAlign.Center,
          modifier = Modifier.padding(24.dp)
        )
      }
      else -> LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 110.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
      ) {
        if (images.value.isNotEmpty()) {
          item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader("Images", images.value.size) }
          items(images.value, key = { "img-${it.id}" }) { item -> MediaThumb(item, isVideo = false) { openItem(item) } }
        }
        if (videos.value.isNotEmpty()) {
          item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader("Videos", videos.value.size) }
          items(videos.value, key = { "vid-${it.id}" }) { item -> MediaThumb(item, isVideo = true) { openItem(item) } }
        }
      }
    }
  }
}

// Pull every image/video item of the chat via paged apiGetChat calls, keep only downloaded ones,
// newest first.
private suspend fun loadAllDownloadedMedia(rhId: Long?, chatInfo: ChatInfo, tag: MsgContentTag): List<ChatItem> {
  val all = ArrayList<ChatItem>()
  val pageSize = 200
  var pagination: ChatPagination = ChatPagination.Last(pageSize)
  var guard = 0
  while (guard++ < 200) {
    val res = chatModel.controller.apiGetChat(rhId, chatInfo.chatType, chatInfo.apiId, null, tag, pagination, "") ?: break
    val items = res.first.chatItems
    if (items.isEmpty()) break
    all.addAll(items)
    if (items.size < pageSize) break
    pagination = ChatPagination.Before(items.first().id, pageSize)
  }
  return all.distinctBy { it.id }
    .filter { getLoadedFilePath(it.file) != null }
    .sortedByDescending { it.meta.createdAt }
}

// A downloaded media item together with the chat it came from (needed to forward it).
class MediaEntry(val item: ChatItem, val chat: Chat)

// Picker: downloaded images/videos from ALL chats with multi-select. On "Send" it hands back the
// chosen entries (caller forwards them via the core's forward command — proper Forward behaviour).
@Composable
fun SimplexMediaPickerView(close: () -> Unit, onSend: (List<MediaEntry>) -> Unit) {
  val images = remember { mutableStateOf<List<MediaEntry>>(emptyList()) }
  val videos = remember { mutableStateOf<List<MediaEntry>>(emptyList()) }
  val loading = remember { mutableStateOf(true) }
  val selected = remember { mutableStateListOf<Long>() }

  LaunchedEffect(Unit) {
    val all = loadDownloadedMediaAllChats()
    images.value = all.filter { it.item.content.msgContent is MsgContent.MCImage }
    videos.value = all.filter { it.item.content.msgContent is MsgContent.MCVideo }
    loading.value = false
  }
  val byId = remember { derivedStateOf { (images.value + videos.value).associateBy { it.item.id } } }

  fun toggle(id: Long) { if (!selected.remove(id)) selected.add(id) }

  Column(Modifier.fillMaxSize().background(MaterialTheme.colors.background)) {
    Row(
      Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      IconButton(onClick = close) {
        Icon(painterResource(MR.images.ic_arrow_back_ios_new), contentDescription = "Back", tint = MaterialTheme.colors.primary)
      }
      Text("Send from SimpleX", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp))
      Spacer(Modifier.weight(1f))
      TextButton(enabled = selected.isNotEmpty(), onClick = { onSend(selected.mapNotNull { byId.value[it] }) }) {
        Text(if (selected.isEmpty()) "Send" else "Send (${selected.size})")
      }
    }
    Divider()
    when {
      loading.value -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MaterialTheme.colors.primary)
      }
      images.value.isEmpty() && videos.value.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("No downloaded media found.", color = MaterialTheme.colors.secondary, modifier = Modifier.padding(24.dp))
      }
      else -> LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 110.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
      ) {
        if (images.value.isNotEmpty()) {
          item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader("Images", images.value.size) }
          items(images.value, key = { "pi-${it.item.id}" }) { e ->
            MediaThumb(e.item, isVideo = false, selected = selected.contains(e.item.id)) { toggle(e.item.id) }
          }
        }
        if (videos.value.isNotEmpty()) {
          item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader("Videos", videos.value.size) }
          items(videos.value, key = { "pv-${it.item.id}" }) { e ->
            MediaThumb(e.item, isVideo = true, selected = selected.contains(e.item.id)) { toggle(e.item.id) }
          }
        }
      }
    }
  }
}

// Load downloaded images/videos across every chat (most recent page per type per chat — fast and
// bounded), keeping the source chat for each so it can be forwarded. Read-only.
private suspend fun loadDownloadedMediaAllChats(): List<MediaEntry> {
  val all = ArrayList<MediaEntry>()
  for (chat in chatModel.chats.value.toList()) {
    val info = chat.chatInfo
    if (info !is ChatInfo.Direct && info !is ChatInfo.Group && info !is ChatInfo.Local) continue
    for (tag in listOf(MsgContentTag.Image, MsgContentTag.Video)) {
      val res = chatModel.controller.apiGetChat(chat.remoteHostId, info.chatType, info.apiId, null, tag, ChatPagination.Last(300), "") ?: continue
      res.first.chatItems.filter { getLoadedFilePath(it.file) != null }.forEach { all.add(MediaEntry(it, chat)) }
    }
  }
  return all.distinctBy { it.item.id }.sortedByDescending { it.item.meta.createdAt }
}

// Forward the selected media into the given destination chat, grouped by source chat (forward is
// per-source). Uses the destination's real group scope + sendAsGroup (same as a normal send), so
// scoped chats like "Chat with admins" work and don't fail with "not current member". The core
// handles the files — no local decryption / re-upload, so no fileNotFound.
suspend fun forwardMediaToChat(dest: Chat, entries: List<MediaEntry>) {
  val destInfo = dest.chatInfo
  entries.groupBy { it.chat.id }.values.forEach { group ->
    val src = group.first().chat
    chatModel.controller.apiForwardChatItems(
      rh = dest.remoteHostId,
      toChatType = destInfo.chatType, toChatId = destInfo.apiId, toScope = destInfo.groupChatScope(), sendAsGroup = destInfo.sendAsGroup,
      fromChatType = src.chatInfo.chatType, fromChatId = src.chatInfo.apiId, fromScope = src.chatInfo.groupChatScope(),
      itemIds = group.map { it.item.id }, ttl = null
    )
  }
}

// When the setting is on, locally remove received images/videos that are still NOT downloaded and
// are older than 48 hours, across all direct chats and groups. "Local" delete (cidmInternal) — it
// only clears them from this device; senders are not affected.
suspend fun cleanupOldUndownloadedMedia() {
  if (!chatModel.controller.appPrefs.autoDeleteOldUndownloadedMedia.get()) return
  val cutoff = Clock.System.now() - 48.hours
  for (chat in chatModel.chats.value.toList()) {
    val info = chat.chatInfo
    if (info !is ChatInfo.Direct && info !is ChatInfo.Group) continue
    val toDelete = ArrayList<Long>()
    for (tag in listOf(MsgContentTag.Image, MsgContentTag.Video)) {
      val res = chatModel.controller.apiGetChat(chat.remoteHostId, info.chatType, info.apiId, null, tag, ChatPagination.Last(1000), "") ?: continue
      res.first.chatItems.forEach { ci ->
        if (ci.file != null && getLoadedFilePath(ci.file) == null && ci.meta.itemTs < cutoff) toDelete.add(ci.id)
      }
    }
    toDelete.distinct().chunked(100).forEach { chunk ->
      chatModel.controller.apiDeleteChatItems(chat.remoteHostId, info.chatType, info.apiId, null, chunk, CIDeleteMode.cidmInternal)
    }
  }
}

@Composable
private fun SectionHeader(title: String, count: Int) {
  Text(
    "$title ($count)",
    fontWeight = FontWeight.SemiBold,
    fontSize = 15.sp,
    color = MaterialTheme.colors.secondary,
    modifier = Modifier.padding(top = 10.dp, bottom = 4.dp, start = 4.dp)
  )
}

@Composable
private fun MediaThumb(item: ChatItem, isVideo: Boolean, selected: Boolean = false, onClick: () -> Unit) {
  val b64 = when (val mc = item.content.msgContent) {
    is MsgContent.MCImage -> mc.image
    is MsgContent.MCVideo -> mc.image
    else -> ""
  }
  val bitmap = remember(item.id) { if (b64.isNotEmpty()) runCatching { base64ToBitmap(b64) }.getOrNull() else null }
  val base = Modifier
    .aspectRatio(1f)
    .clip(RoundedCornerShape(6.dp))
    .background(Color.Black.copy(alpha = 0.2f))
  Box(
    (if (selected) base.border(3.dp, MaterialTheme.colors.primary, RoundedCornerShape(6.dp)) else base)
      .clickable(onClick = onClick),
    contentAlignment = Alignment.Center
  ) {
    if (bitmap != null) {
      Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
    if (isVideo) {
      Icon(
        painterResource(MR.images.ic_play_arrow_filled),
        contentDescription = null,
        tint = Color.White,
        modifier = Modifier.size(38.dp).background(Color.Black.copy(alpha = 0.45f), CircleShape).padding(7.dp)
      )
    }
    if (selected) {
      Box(Modifier.fillMaxSize().background(MaterialTheme.colors.primary.copy(alpha = 0.25f)))
      Box(
        Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp)
          .background(MaterialTheme.colors.primary, CircleShape)
      )
    }
  }
}
