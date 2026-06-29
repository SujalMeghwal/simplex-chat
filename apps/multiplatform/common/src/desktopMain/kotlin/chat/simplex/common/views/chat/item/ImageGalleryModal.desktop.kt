package chat.simplex.common.views.chat.item

import androidx.compose.runtime.Composable

// The filter tabs (All/Images/Videos/Downloaded) and the save-to-Downloads button were removed —
// the fullscreen viewer is now just the media with keyboard navigation. Browsing/filtering is done
// from the "Downloaded" grid instead.
@Composable
actual fun openGalleryModal(imageProvider: (Boolean) -> ImageGalleryProvider, close: () -> Unit) {
  ImageFullScreenView({ imageProvider(false) }, close)
}

// Kept for existing callers (e.g. CIImageView.desktop) — now just the plain fullscreen viewer.
@Composable
fun DesktopGalleryWithToggle(imageProvider: (Boolean) -> ImageGalleryProvider, close: () -> Unit) {
  ImageFullScreenView({ imageProvider(false) }, close)
}
