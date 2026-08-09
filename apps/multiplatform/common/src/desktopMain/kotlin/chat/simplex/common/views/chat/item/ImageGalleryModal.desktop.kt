package chat.simplex.common.views.chat.item

import androidx.compose.runtime.Composable

// The filter tabs (All/Images/Videos/Downloaded) and the save-to-Downloads button were removed —
// the fullscreen viewer is now just the media with keyboard navigation. Browsing/filtering is done
// from the "Downloaded" grid instead.
@Composable
actual fun openGalleryModal(imageProvider: (Boolean) -> ImageGalleryProvider, close: () -> Unit) {
  // downloadedOnly = true: left/right only steps between media that is actually DOWNLOADED, and
  // autoplay-next only advances to downloaded clips. Stepping onto a not-downloaded video used to
  // land on a dead still-frame that couldn't play (looked "stuck"); restricting to downloaded fixes
  // that. If only one item is downloaded there simply is no next/prev — which is the correct result.
  ImageFullScreenView({ imageProvider(true) }, close)
}

// Kept for existing callers (e.g. CIImageView.desktop) — now just the plain fullscreen viewer.
@Composable
fun DesktopGalleryWithToggle(imageProvider: (Boolean) -> ImageGalleryProvider, close: () -> Unit) {
  ImageFullScreenView({ imageProvider(true) }, close)
}
