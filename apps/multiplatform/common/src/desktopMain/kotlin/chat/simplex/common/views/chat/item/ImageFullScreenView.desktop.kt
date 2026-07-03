package chat.simplex.common.views.chat.item

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.simplex.common.platform.*
import chat.simplex.common.views.helpers.getBitmapFromByteArray
import chat.simplex.res.MR
import dev.icerock.moko.resources.compose.painterResource
import dev.icerock.moko.resources.compose.stringResource
import kotlinx.coroutines.delay
import org.jetbrains.compose.videoplayer.SkiaBitmapVideoSurface
import kotlin.math.max

@Composable
actual fun FullScreenImageView(modifier: Modifier, data: ByteArray, imageBitmap: ImageBitmap) {
  Image(
    getBitmapFromByteArray(data, false) ?: MR.images.decentralized.image.toComposeImageBitmap(),
    contentDescription = stringResource(MR.strings.image_descr),
    contentScale = ContentScale.Fit,
    modifier = modifier,
  )
}

@Composable
actual fun FullScreenVideoView(player: VideoPlayer, modifier: Modifier, close: () -> Unit) {
  val playing = remember(player) { player.videoPlaying }
  // Controls + top bar auto-hide while playing so the video isn't covered; any pointer movement
  // brings them back. Paused/ended => always visible.
  val controlsVisible = remember { mutableStateOf(true) }
  val lastMove = remember { mutableStateOf(System.currentTimeMillis()) }
  LaunchedEffect(Unit) {
    while (true) {
      delay(600)
      if (playing.value && System.currentTimeMillis() - lastMove.value > 2800) controlsVisible.value = false
    }
  }
  fun wake() { lastMove.value = System.currentTimeMillis(); controlsVisible.value = true }

  Box(
    // Initial pass = observe pointer movement without stealing it from the zoom/pan/click handlers.
    Modifier.pointerInput(Unit) {
      awaitPointerEventScope {
        while (true) {
          val e = awaitPointerEvent(PointerEventPass.Initial)
          if (e.type == PointerEventType.Move || e.type == PointerEventType.Press) wake()
        }
      }
    }
  ) {
    Box(Modifier.fillMaxSize().padding(bottom = 50.dp)) {
      // Tap the frame to toggle play/pause (and wake controls) instead of closing the viewer;
      // close is the back arrow / Esc. Standard video-player feel.
      SurfaceFromPlayer(player, modifier, onTap = {
        wake()
        togglePlayPause(player)
      })
    }
    AnimatedVisibility(controlsVisible.value, enter = fadeIn(), exit = fadeOut()) {
      Box(Modifier.fillMaxSize()) {
        IconButton(onClick = close, Modifier.padding(top = 5.dp).align(Alignment.TopStart)) {
          Icon(painterResource(MR.images.ic_arrow_back_ios_new), "Close", Modifier.size(30.dp), tint = Color.White)
        }
        Controls(player, Modifier.align(Alignment.BottomCenter)) { wake() }
      }
    }
  }
}

@Composable
fun BoxScope.SurfaceFromPlayer(player: VideoPlayer, modifier: Modifier, onTap: (() -> Unit)? = null) {
  val surface = remember {
    SkiaBitmapVideoSurface().also {
      player.player.videoSurface().set(it)
    }
  }
  val bitmap = surface.bitmap.value
  if (bitmap != null) {
    // detectTapGestures (not clickable) so tapping the frame doesn't grab keyboard focus — the
    // gallery's arrow-key navigation keeps working after you click the video.
    val m = if (onTap != null) modifier.pointerInput(onTap) { detectTapGestures(onTap = { onTap() }) } else modifier
    Image(
      bitmap,
      modifier = m.align(Alignment.Center),
      contentDescription = null,
      contentScale = ContentScale.Fit,
      alignment = Alignment.Center,
    )
  } else {
    // No frame decoded yet — show a spinner instead of a black void so it reads as "loading".
    CircularProgressIndicator(Modifier.align(Alignment.Center).size(40.dp), color = Color.White, strokeWidth = 3.dp)
  }
}

private fun togglePlayPause(player: VideoPlayer) {
  val ended = player.progress.value >= player.duration.value && player.duration.value > 0
  when {
    ended -> { player.progress.value = 0; player.play(true) }
    player.videoPlaying.value -> player.player.pause()
    else -> player.play(true)
  }
}

private val speedSteps = listOf(0.5f, 1f, 1.25f, 1.5f, 2f)

private fun formatTime(ms: Long): String {
  val total = (ms / 1000).coerceAtLeast(0)
  val m = total / 60
  val s = total % 60
  return "%d:%02d".format(m, s)
}

@Composable
private fun Controls(player: VideoPlayer, modifier: Modifier, onInteract: () -> Unit) {
  val playing = remember(player) { player.videoPlaying }
  val progress = remember(player) { player.progress }
  val duration = remember(player) { player.duration }
  val speedIdx = remember(player) { mutableStateOf(1) } // index into speedSteps, default 1x
  val muted = remember(player) { mutableStateOf(false) }
  val loop = remember(player) { mutableStateOf(false) }
  // Scrub state: while dragging we DON'T seek on every pixel (that floods VLCJ and causes the drag
  // to stutter). We track the drag position locally and issue a single seek when the finger lifts.
  val scrubbing = remember(player) { mutableStateOf(false) }
  val scrubFrac = remember(player) { mutableStateOf(0f) }
  val ended = progress.value >= duration.value && duration.value > 0

  // Loop: when the clip ends and loop is on, restart it. Fires once on the transition to ended.
  LaunchedEffect(ended) {
    if (ended && loop.value) { progress.value = 0; player.play(true) }
  }

  fun seekBy(deltaMs: Long) {
    onInteract()
    val target = (progress.value + deltaMs).coerceIn(0L, duration.value)
    player.player.seekTo(target.toInt())
    progress.value = target
  }

  Column(modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.45f))) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
      val shownMs = if (scrubbing.value) (scrubFrac.value * duration.value).toLong() else progress.value
      Text(formatTime(shownMs), color = Color.White, fontSize = 12.sp)
      Slider(
        value = if (scrubbing.value) scrubFrac.value else progress.value.toFloat() / max(0.0001f, duration.value.toFloat()),
        onValueChange = { onInteract(); scrubbing.value = true; scrubFrac.value = it },
        onValueChangeFinished = {
          val target = (scrubFrac.value * duration.value).toLong()
          player.player.seekTo(target.toInt())
          progress.value = target
          scrubbing.value = false
        },
        modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
      )
      Text(formatTime(duration.value), color = Color.White, fontSize = 12.sp)
    }
    Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
      // Left: mute toggle (safe: VLCJ isMute, not setVolume — see VideoPlayer.desktop.kt note).
      IconButton(onClick = {
        onInteract()
        muted.value = !muted.value
        player.player.audio().isMute = muted.value
      }) {
        Icon(painterResource(if (muted.value) MR.images.ic_volume_off else MR.images.ic_volume_up), "Mute", Modifier.size(24.dp), tint = Color.White)
      }
      Spacer(Modifier.weight(1f))
      IconButton(onClick = { seekBy(-10_000) }) {
        Icon(painterResource(MR.images.ic_replay), "Back 10s", Modifier.size(26.dp), tint = Color.White)
      }
      IconButton(onClick = {
        onInteract()
        togglePlayPause(player)
      }) {
        Icon(
          painterResource(if (playing.value) MR.images.ic_pause_filled else MR.images.ic_play_arrow_filled),
          if (playing.value) "Pause" else "Play", Modifier.size(36.dp), tint = Color.White
        )
      }
      IconButton(onClick = { seekBy(10_000) }) {
        Icon(painterResource(MR.images.ic_forward), "Forward 10s", Modifier.size(26.dp), tint = Color.White)
      }
      Spacer(Modifier.weight(1f))
      // Right: loop toggle + speed cycle.
      IconButton(onClick = { onInteract(); loop.value = !loop.value }) {
        Icon(
          painterResource(MR.images.ic_repeat_one), "Loop", Modifier.size(24.dp),
          tint = if (loop.value) MaterialTheme.colors.primary else Color.White
        )
      }
      TextButton(onClick = {
        onInteract()
        speedIdx.value = (speedIdx.value + 1) % speedSteps.size
        player.player.setRate(speedSteps[speedIdx.value])
      }) {
        Text("${speedSteps[speedIdx.value]}×", color = Color.White, fontSize = 14.sp)
      }
    }
  }
}
