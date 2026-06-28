package chat.simplex.common.views.chat.item

import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import chat.simplex.res.MR
import dev.icerock.moko.resources.compose.painterResource

@Composable
actual fun openGalleryModal(imageProvider: (Boolean) -> ImageGalleryProvider, close: () -> Unit) {
  DesktopGalleryWithToggle(imageProvider, close)
}

@Composable
fun DesktopGalleryWithToggle(imageProvider: (Boolean) -> ImageGalleryProvider, close: () -> Unit) {
  val downloadedOnly = remember { mutableStateOf(false) }
  Box(Modifier.fillMaxSize()) {
    key(downloadedOnly.value) {
      ImageFullScreenView({ imageProvider(downloadedOnly.value) }, close)
    }
    DownloadedOnlyToggle(downloadedOnly)
  }
}

@Composable
private fun BoxScope.DownloadedOnlyToggle(downloadedOnly: MutableState<Boolean>) {
  IconToggleButton(
    checked = downloadedOnly.value,
    onCheckedChange = { downloadedOnly.value = it },
    modifier = Modifier
      .align(Alignment.TopEnd)
      .padding(top = 8.dp, end = 8.dp)
      .size(40.dp)
  ) {
    Icon(
      painter = painterResource(MR.images.ic_download),
      contentDescription = if (downloadedOnly.value) "Show all media" else "Show downloaded only",
      tint = if (downloadedOnly.value) MaterialTheme.colors.primary else Color.White,
      modifier = Modifier.size(24.dp)
    )
  }
}
