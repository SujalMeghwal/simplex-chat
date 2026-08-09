package chat.simplex.common.views.chat.item

import androidx.compose.runtime.Composable

@Composable
actual fun openGalleryModal(imageProvider: (Boolean) -> ImageGalleryProvider, close: () -> Unit) {
  // downloadedOnly = true: navigation + autoplay only visit downloaded media (no stuck previews).
  ImageFullScreenView({ imageProvider(true) }, close)
}
