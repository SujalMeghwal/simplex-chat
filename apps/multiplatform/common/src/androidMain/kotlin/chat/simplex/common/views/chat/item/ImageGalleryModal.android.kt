package chat.simplex.common.views.chat.item

import androidx.compose.runtime.Composable

@Composable
actual fun openGalleryModal(imageProvider: (Boolean) -> ImageGalleryProvider, close: () -> Unit) {
  ImageFullScreenView({ imageProvider(false) }, close)
}
