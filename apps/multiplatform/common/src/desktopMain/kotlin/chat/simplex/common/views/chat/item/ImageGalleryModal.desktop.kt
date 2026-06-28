package chat.simplex.common.views.chat.item

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.simplex.common.views.chat.ProviderMedia
import chat.simplex.common.views.helpers.withBGApi
import chat.simplex.res.MR
import dev.icerock.moko.resources.compose.painterResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class GalleryFilter { ALL, IMAGES, VIDEOS, DOWNLOADED }

@Composable
actual fun openGalleryModal(imageProvider: (Boolean) -> ImageGalleryProvider, close: () -> Unit) {
  DesktopGalleryWithToggle(imageProvider, close)
}

@Composable
fun DesktopGalleryWithToggle(imageProvider: (Boolean) -> ImageGalleryProvider, close: () -> Unit) {
  val filter = remember { mutableStateOf(GalleryFilter.ALL) }
  val currentIndex = remember { mutableStateOf(0) }
  val saveMessage = remember { mutableStateOf<String?>(null) }

  val downloadedOnly = filter.value == GalleryFilter.DOWNLOADED

  Box(Modifier.fillMaxSize()) {
    val rawProvider = remember(filter.value) { imageProvider(downloadedOnly) }
    val trackingProvider = remember(rawProvider, filter.value) {
      TrackingMediaProvider(
        MediaTypeFilteredProvider(rawProvider, filter.value),
        onPageChanged = { currentIndex.value = it }
      )
    }

    key(filter.value) {
      ImageFullScreenView({ trackingProvider }, close)
    }

    GalleryFilterRow(
      filter = filter,
      modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp)
    )

    IconButton(
      onClick = {
        val media = trackingProvider.getMedia(currentIndex.value)
        if (media != null) {
          withBGApi {
            val downloadsDir = File(System.getProperty("user.home"), "Downloads").also { it.mkdirs() }
            val ts = System.currentTimeMillis()
            val saved: File? = when (media) {
              is ProviderMedia.Image -> {
                val out = File(downloadsDir, "simplex-image-$ts.png")
                out.writeBytes(media.data)
                out
              }
              is ProviderMedia.Video -> {
                val src = File(media.uri)
                if (src.exists()) {
                  val ext = src.extension.ifEmpty { "mp4" }
                  val out = File(downloadsDir, "simplex-video-$ts.$ext")
                  src.copyTo(out, overwrite = true)
                  out
                } else null
              }
            }
            withContext(Dispatchers.Main) {
              saveMessage.value = if (saved != null) "Saved: ${saved.name}" else "File not found"
            }
          }
        }
      },
      modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 228.dp).size(40.dp)
    ) {
      Icon(
        painter = painterResource(MR.images.ic_download),
        contentDescription = "Save to Downloads",
        tint = Color.White.copy(alpha = 0.8f),
        modifier = Modifier.size(22.dp)
      )
    }

    saveMessage.value?.let { msg ->
      LaunchedEffect(msg) {
        kotlinx.coroutines.delay(3000)
        saveMessage.value = null
      }
      Surface(
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
        shape = RoundedCornerShape(8.dp),
        color = Color.Black.copy(alpha = 0.75f)
      ) {
        Text(msg, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = Color.White, fontSize = 13.sp)
      }
    }
  }
}

@Composable
private fun GalleryFilterRow(filter: MutableState<GalleryFilter>, modifier: Modifier) {
  Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
    listOf(
      Triple(GalleryFilter.ALL, null as dev.icerock.moko.resources.ImageResource?, "All"),
      Triple(GalleryFilter.IMAGES, MR.images.ic_image, "Images"),
      Triple(GalleryFilter.VIDEOS, MR.images.ic_videocam_filled, "Videos"),
      Triple(GalleryFilter.DOWNLOADED, MR.images.ic_download, "Downloaded"),
    ).forEach { (f, icon, label) ->
      val selected = filter.value == f
      IconToggleButton(
        checked = selected,
        onCheckedChange = { filter.value = f },
        modifier = Modifier.size(40.dp)
      ) {
        if (icon != null) {
          Icon(
            painter = painterResource(icon),
            contentDescription = label,
            tint = if (selected) MaterialTheme.colors.primary else Color.White.copy(alpha = 0.75f),
            modifier = Modifier.size(22.dp)
          )
        } else {
          Text(
            "All",
            color = if (selected) MaterialTheme.colors.primary else Color.White.copy(alpha = 0.75f),
            fontSize = 11.sp
          )
        }
      }
    }
  }
}

class MediaTypeFilteredProvider(
  private val inner: ImageGalleryProvider,
  private val filter: GalleryFilter
) : ImageGalleryProvider {
  override val initialIndex: Int get() = inner.initialIndex
  override val totalMediaSize: MutableState<Int> get() = inner.totalMediaSize

  override fun getMedia(index: Int): ProviderMedia? {
    val media = inner.getMedia(index) ?: return null
    return when (filter) {
      GalleryFilter.ALL, GalleryFilter.DOWNLOADED -> media
      GalleryFilter.IMAGES -> if (media is ProviderMedia.Image) media else null
      GalleryFilter.VIDEOS -> if (media is ProviderMedia.Video) media else null
    }
  }

  override fun currentPageChanged(index: Int) = inner.currentPageChanged(index)
  override fun scrollToStart() = inner.scrollToStart()
  override fun onDismiss(index: Int) = inner.onDismiss(index)
}

class TrackingMediaProvider(
  private val inner: ImageGalleryProvider,
  private val onPageChanged: (Int) -> Unit
) : ImageGalleryProvider {
  override val initialIndex: Int get() = inner.initialIndex
  override val totalMediaSize: MutableState<Int> get() = inner.totalMediaSize
  override fun getMedia(index: Int): ProviderMedia? = inner.getMedia(index)
  override fun scrollToStart() = inner.scrollToStart()
  override fun onDismiss(index: Int) = inner.onDismiss(index)

  override fun currentPageChanged(index: Int) {
    onPageChanged(index)
    inner.currentPageChanged(index)
  }
}
