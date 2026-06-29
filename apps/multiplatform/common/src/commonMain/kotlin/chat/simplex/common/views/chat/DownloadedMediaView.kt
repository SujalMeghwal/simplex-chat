package chat.simplex.common.views.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
private fun MediaThumb(item: ChatItem, isVideo: Boolean, onClick: () -> Unit) {
  val b64 = when (val mc = item.content.msgContent) {
    is MsgContent.MCImage -> mc.image
    is MsgContent.MCVideo -> mc.image
    else -> ""
  }
  val bitmap = remember(item.id) { if (b64.isNotEmpty()) runCatching { base64ToBitmap(b64) }.getOrNull() else null }
  Box(
    Modifier
      .aspectRatio(1f)
      .clip(RoundedCornerShape(6.dp))
      .background(Color.Black.copy(alpha = 0.2f))
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
  }
}
