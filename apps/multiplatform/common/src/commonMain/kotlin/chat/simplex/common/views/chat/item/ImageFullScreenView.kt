package chat.simplex.common.views.chat.item

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.simplex.common.model.CryptoFile
import chat.simplex.common.platform.*
import chat.simplex.common.ui.theme.CurrentColors
import chat.simplex.common.views.chat.ProviderMedia
import chat.simplex.common.views.helpers.*
import chat.simplex.res.MR
import dev.icerock.moko.resources.compose.painterResource
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.net.URI
import kotlin.math.absoluteValue

interface ImageGalleryProvider {
  val initialIndex: Int
  val totalMediaSize: MutableState<Int>
  fun getMedia(index: Int): ProviderMedia?
  fun currentPageChanged(index: Int)
  fun scrollToStart()
  fun onDismiss(index: Int)
}

@Composable
fun ImageFullScreenView(imageProvider: () -> ImageGalleryProvider, close: () -> Unit) {
  val provider = remember { imageProvider() }
  val pagerState = rememberPagerState(
    initialPage = provider.initialIndex,
    initialPageOffsetFraction = 0f
  ) {
    provider.totalMediaSize.value
  }
  val firstValidPageBeforeScrollingToStart = remember { mutableStateOf(0) }
  val goBack = { provider.onDismiss(pagerState.currentPage); close() }
  BackHandler(onBack = goBack)
  // Pager doesn't ask previous page at initialization step who knows why. By not doing this, prev page is not checked and can be blank,
  // which makes this blank page visible for a moment. Prevent it by doing the check ourselves
  // Pager hack for Android only. On desktop we don't use the pager (see below), and calling
  // scrollToStart() here would mutate the provider's internal index and break direct getMedia().
  LaunchedEffect(Unit) {
    if (appPlatform.isAndroid && provider.getMedia(provider.initialIndex - 1) == null) {
      firstValidPageBeforeScrollingToStart.value = provider.initialIndex
      provider.scrollToStart()
      pagerState.scrollToPage(0)
      firstValidPageBeforeScrollingToStart.value = 0
    }
  }
  val scope = rememberCoroutineScope()
  val playersToRelease = rememberSaveable { mutableSetOf<URI>() }
  DisposableEffectOnGone(
    always = {
      platform.androidSetStatusAndNavigationBarAppearance(false, false, blackNavBar = true)
      chatModel.fullscreenGalleryVisible.value = true
    },
    whenDispose = {
      val c = CurrentColors.value.colors
      platform.androidSetStatusAndNavigationBarAppearance(c.isLight, c.isLight)
      chatModel.fullscreenGalleryVisible.value = false
    },
    whenGone = {
      playersToRelease.forEach { VideoPlayerHolder.release(it, true, true) }
    }
  )

  @Composable
  fun Content(index: Int) {
    // Index can be huge but in reality at that moment pager state scrolls to 0 and that page should have index 0 too if it's the first one.
    // Or index 1 if it's the second page
    val index = index - firstValidPageBeforeScrollingToStart.value
    Column(
      Modifier
        .fillMaxSize()
        .background(Color.Black)
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = goBack)
    ) {
      var settledCurrentPage by remember { mutableStateOf(pagerState.currentPage) }
      LaunchedEffect(pagerState) {
        snapshotFlow {
          if (!pagerState.isScrollInProgress) pagerState.currentPage else settledCurrentPage
        }.collect {
          settledCurrentPage = it
        }
      }
      LaunchedEffect(settledCurrentPage) {
        // Make this pager with infinity scrolling with only 3 pages at a time when left and right pages constructs in real time
        if (settledCurrentPage != provider.initialIndex)
          provider.currentPageChanged(index)
      }
      val media = provider.getMedia(index)
      if (media == null) {
        // No such image. Let's shrink total pages size or scroll to start of the list of pages to remove blank page automatically
        SideEffect {
          scope.launch {
            when (settledCurrentPage) {
              index - 1 -> provider.totalMediaSize.value = settledCurrentPage + 1
              index + 1 -> {
                provider.scrollToStart()
                pagerState.scrollToPage(0)
              }
              // Current media was deleted or moderated, close gallery
              index -> close()
            }
          }
        }
      } else {
        var scale by remember { mutableStateOf(1f) }
        var translationX by remember { mutableStateOf(0f) }
        var translationY by remember { mutableStateOf(0f) }
        var viewWidth by remember { mutableStateOf(0) }
        var allowTranslate by remember { mutableStateOf(true) }
        LaunchedEffect(settledCurrentPage) {
          scale = 1f
          translationX = 0f
          translationY = 0f
        }
        val modifier = Modifier
          .onGloballyPositioned {
            viewWidth = it.size.width
          }
          .graphicsLayer(
            scaleX = scale,
            scaleY = scale,
            translationX = translationX,
            translationY = translationY,
          )
          .pointerInput(Unit) {
            detectTransformGestures(
              { allowTranslate },
              onGesture = { _, pan, gestureZoom, _ ->
                scale = (scale * gestureZoom).coerceIn(1f, 20f)
                allowTranslate = viewWidth * (scale - 1f) - ((translationX + pan.x * scale).absoluteValue * 2) > 0
                if (scale > 1 && allowTranslate) {
                  translationX += pan.x * scale
                  translationY += pan.y * scale
                } else if (allowTranslate) {
                  translationX = 0f
                  translationY = 0f
                }
              }
            )
          }
          .pointerInput(Unit) {
            awaitPointerEventScope {
              while (true) {
                val event = awaitPointerEvent()
                if (event.type == PointerEventType.Scroll) {
                  val scrollDelta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                  scale = (scale * (1f - scrollDelta * 0.1f)).coerceIn(1f, 20f)
                  if (scale <= 1f) {
                    scale = 1f
                    translationX = 0f
                    translationY = 0f
                  }
                }
              }
            }
          }
          .fillMaxSize()
        // LALAL
        // https://github.com/JetBrains/compose-multiplatform/pull/2015/files#diff-841b3825c504584012e1d1c834d731bae794cce6acad425d81847c8bbbf239e0R24
        if (media is ProviderMedia.Image) {
          val (data: ByteArray, imageBitmap: ImageBitmap) = media
          FullScreenImageView(modifier, data, imageBitmap)
        } else if (media is ProviderMedia.Video) {
          val preview = remember(media.uri.path) { base64ToBitmap(media.preview) }
          val uriDecrypted = remember(media.uri.path) { mutableStateOf(if (media.fileSource?.cryptoArgs == null) media.uri else media.fileSource.decryptedGet()) }
          val decrypted = uriDecrypted.value
          if (decrypted != null) {
            // settledCurrentPage finishes **only** when fully swiped
            // So we use pagerState.currentPage that changes right away as the screen is being dragged
            val isCurrentPage = index == pagerState.currentPage && kotlin.math.abs(pagerState.currentPageOffsetFraction) < 0.3f
            VideoView(modifier, decrypted, preview, isCurrentPage, close)
            DisposableEffect(Unit) {
              onDispose { playersToRelease.add(decrypted) }
            }
          } else if (media.fileSource != null) {
            VideoViewEncrypted(uriDecrypted, media.fileSource, preview, close)
          }
        }
      }
    }
  }
  // Desktop renderer for a single media item at an absolute provider index. No pager dependency:
  // zoom/pan reset whenever the index changes, and video on the shown page is always "current".
  @Composable
  fun DesktopMediaContent(index: Int) {
    val media = provider.getMedia(index)
    if (media == null) return
    Column(
      Modifier
        .fillMaxSize()
        .background(Color.Black)
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = goBack)
    ) {
      var scale by remember(index) { mutableStateOf(1f) }
      var translationX by remember(index) { mutableStateOf(0f) }
      var translationY by remember(index) { mutableStateOf(0f) }
      var viewWidth by remember { mutableStateOf(0) }
      var allowTranslate by remember { mutableStateOf(true) }
      val modifier = Modifier
        .onGloballyPositioned { viewWidth = it.size.width }
        .graphicsLayer(scaleX = scale, scaleY = scale, translationX = translationX, translationY = translationY)
        .pointerInput(Unit) {
          detectTransformGestures(
            { allowTranslate },
            onGesture = { _, pan, gestureZoom, _ ->
              scale = (scale * gestureZoom).coerceIn(1f, 20f)
              allowTranslate = viewWidth * (scale - 1f) - ((translationX + pan.x * scale).absoluteValue * 2) > 0
              if (scale > 1 && allowTranslate) {
                translationX += pan.x * scale
                translationY += pan.y * scale
              } else if (allowTranslate) {
                translationX = 0f
                translationY = 0f
              }
            }
          )
        }
        .pointerInput(Unit) {
          awaitPointerEventScope {
            while (true) {
              val event = awaitPointerEvent()
              if (event.type == PointerEventType.Scroll) {
                val scrollDelta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                scale = (scale * (1f - scrollDelta * 0.1f)).coerceIn(1f, 20f)
                if (scale <= 1f) { scale = 1f; translationX = 0f; translationY = 0f }
              }
            }
          }
        }
        .fillMaxSize()
      if (media is ProviderMedia.Image) {
        val (data: ByteArray, imageBitmap: ImageBitmap) = media
        FullScreenImageView(modifier, data, imageBitmap)
      } else if (media is ProviderMedia.Video) {
        val preview = remember(media.uri.path) { base64ToBitmap(media.preview) }
        val uriDecrypted = remember(media.uri.path) { mutableStateOf(if (media.fileSource?.cryptoArgs == null) media.uri else media.fileSource.decryptedGet()) }
        val decrypted = uriDecrypted.value
        if (decrypted != null) {
          VideoView(modifier, decrypted, preview, true, close)
          DisposableEffect(Unit) { onDispose { playersToRelease.add(decrypted) } }
        } else if (media.fileSource != null) {
          VideoViewEncrypted(uriDecrypted, media.fileSource, preview, close)
        }
      }
    }
  }
  if (appPlatform.isAndroid) {
    HorizontalPager(state = pagerState) { index -> Content(index) }
  } else {
    // Desktop: no swipe. Each media item is a consecutive index in the provider (N = opened item,
    // N±1 = neighbours), so navigation is just stepping the index and asking the provider for that
    // media — no pager, no index "recentering" (which was the source of the broken arrows).
    val focusRequester = remember { FocusRequester() }
    val curIndex = remember { mutableStateOf(provider.initialIndex) }
    // Video shortcuts operate on whatever video is currently shown fullscreen. Arrows stay reserved
    // for gallery navigation (they also work for images), so playback keys are the player-standard
    // J/L (seek), Space/K (play-pause), M (mute), , / . (frame step) — no conflict.
    fun handleVideoKey(key: Key): Boolean {
      val p = ActiveFullscreenPlayer.player ?: return false
      when (key) {
        Key.Spacebar, Key.K -> if (p.videoPlaying.value) p.pause() else p.play(true)
        Key.J -> p.seekTo(p.progress.value - 10_000)
        Key.L -> p.seekTo(p.progress.value + 10_000)
        Key.M -> p.setMuted(!p.muted.value)
        Key.Comma -> p.seekTo(p.progress.value - 40)
        Key.Period -> p.seekTo(p.progress.value + 40)
        else -> return false
      }
      focusRequester.requestFocus()
      return true
    }
    // Autoplay-next: when a clip finishes (loop off), advance to the next gallery item.
    DisposableEffect(Unit) {
      GalleryAutoplay.onVideoEnded = {
        if (provider.getMedia(curIndex.value + 1) != null) { curIndex.value += 1; focusRequester.requestFocus() }
      }
      onDispose { GalleryAutoplay.onVideoEnded = null }
    }
    Box(
      Modifier
        .fillMaxSize()
        .focusRequester(focusRequester)
        .focusable()
        .onPreviewKeyEvent { e ->
          if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
          when (e.key) {
            Key.DirectionLeft, Key.DirectionUp ->
              if (provider.getMedia(curIndex.value - 1) != null) { curIndex.value -= 1; focusRequester.requestFocus(); true } else true
            Key.DirectionRight, Key.DirectionDown ->
              if (provider.getMedia(curIndex.value + 1) != null) { curIndex.value += 1; focusRequester.requestFocus(); true } else true
            Key.Escape -> { goBack(); true }
            else -> handleVideoKey(e.key)
          }
        }
    ) {
      DesktopMediaContent(curIndex.value)
      // After navigating, the newly shown media (a video player especially) can grab keyboard focus,
      // which is why the FIRST arrow key worked but the next one did nothing. Re-assert focus on this
      // Box every time the shown item changes so both arrows keep working through the whole gallery.
      LaunchedEffect(curIndex.value) { focusRequester.requestFocus() }
      // Visible prev/next arrows so navigation is discoverable (not just the ←/→ keys). Clicking them
      // keeps focus on this Box (they re-request it) so the keyboard shortcuts keep working too.
      val hasPrev = provider.getMedia(curIndex.value - 1) != null
      val hasNext = provider.getMedia(curIndex.value + 1) != null
      if (hasPrev) {
        GalleryNavButton(MR.images.ic_arrow_back_ios_new, "Previous", Modifier.align(Alignment.CenterStart)) {
          curIndex.value -= 1; focusRequester.requestFocus()
        }
      }
      if (hasNext) {
        GalleryNavButton(MR.images.ic_arrow_forward_ios, "Next", Modifier.align(Alignment.CenterEnd)) {
          curIndex.value += 1; focusRequester.requestFocus()
        }
      }
    }
    LaunchedEffect(Unit) {
      focusRequester.requestFocus()
    }
  }
}

@Composable
private fun GalleryNavButton(icon: dev.icerock.moko.resources.ImageResource, descr: String, modifier: Modifier, onClick: () -> Unit) {
  Box(modifier.padding(horizontal = 8.dp)) {
    Surface(color = Color.Black.copy(alpha = 0.4f), shape = androidx.compose.foundation.shape.CircleShape) {
      IconButton(onClick = onClick) {
        Icon(painterResource(icon), descr, Modifier.size(28.dp), tint = Color.White)
      }
    }
  }
}

@Composable
expect fun FullScreenImageView(modifier: Modifier, data: ByteArray, imageBitmap: ImageBitmap)

@Composable
private fun VideoViewEncrypted(uriUnencrypted: MutableState<URI?>, fileSource: CryptoFile, defaultPreview: ImageBitmap, close: () -> Unit) {
  LaunchedEffect(Unit) {
    withBGApi {
      uriUnencrypted.value = fileSource.decryptedGetOrCreate()
      if (uriUnencrypted.value == null) {
        close()
      }
    }
  }
  Box(contentAlignment = Alignment.Center) {
    VideoPreviewImageViewFullScreen(defaultPreview, {}, {})
    VideoDecryptionProgress() {}
  }
}

@Composable
private fun VideoView(modifier: Modifier, uri: URI, defaultPreview: ImageBitmap, currentPage: Boolean, close: () -> Unit) {
  val player = remember(uri) { VideoPlayerHolder.getOrCreate(uri, true, defaultPreview, 0L, true) }
  val isCurrentPage = rememberUpdatedState(currentPage)
  // Resume where this clip was last left off (session-only, in-memory). Seed progress before the
  // first play() so its seek honours it; skip if we're within 3s of the start or end.
  remember(uri) {
    val r = VideoPlayerHolder.resumePositions[uri] ?: 0L
    if (r > 3000) player.progress.value = r
    true
  }
  val play = {
    player.play(true)
  }
  val stop = {
    player.stop()
  }
  LaunchedEffect(Unit) {
    snapshotFlow { isCurrentPage.value }
      .distinctUntilChanged()
      .collect {
        if (it) play() else stop()
        player.enableSound(true)
      }
  }
  // Expose this player to the gallery's keyboard shortcuts while it's the shown item, and remember
  // the playback position so reopening resumes it.
  DisposableEffect(uri) {
    ActiveFullscreenPlayer.player = player
    onDispose {
      if (ActiveFullscreenPlayer.player === player) ActiveFullscreenPlayer.player = null
      val p = player.progress.value
      val d = player.duration.value
      if (p > 3000 && (d == 0L || p < d - 3000)) VideoPlayerHolder.resumePositions[uri] = p
      else VideoPlayerHolder.resumePositions.remove(uri)
    }
  }

  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    FullScreenVideoView(player, modifier, close)
  }
}

@Composable
expect fun FullScreenVideoView(player: VideoPlayer, modifier: Modifier, close: () -> Unit)
