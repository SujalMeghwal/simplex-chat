package chat.simplex.common.views.chat.item

import androidx.compose.runtime.Composable

@Composable
expect fun openGalleryModal(imageProvider: (Boolean) -> ImageGalleryProvider, close: () -> Unit)
