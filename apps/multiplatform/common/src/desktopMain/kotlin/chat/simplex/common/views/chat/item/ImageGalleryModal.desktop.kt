package chat.simplex.common.views.chat.item

import androidx.compose.runtime.Composable

// The filter tabs (All/Images/Videos/Downloaded) and the save-to-Downloads button were removed —
// the fullscreen viewer is now just the media with keyboard navigation. Browsing/filtering is done
// from the "Downloaded" grid instead.
@Composable
actual fun openGalleryModal(imageProvider: (Boolean) -> ImageGalleryProvider, close: () -> Unit) {
  // downloadedOnly = true: left/right steps only between fully-downloaded media, skipping items that
  // aren't downloaded (their low-res previews were dead ends). The opened item is always downloaded.
  ImageFullScreenView({ imageProvider(true) }, close)
}

// Kept for existing callers (e.g. CIImageView.desktop) — now just the plain fullscreen viewer.
@Composable
fun DesktopGalleryWithToggle(imageProvider: (Boolean) -> ImageGalleryProvider, close: () -> Unit) {
  ImageFullScreenView({ imageProvider(true) }, close)
}
