package chat.simplex.common.platform

import androidx.compose.runtime.MutableState
import androidx.compose.ui.graphics.ImageBitmap
import java.net.URI

interface VideoPlayerInterface {
  data class PreviewAndDuration(val preview: ImageBitmap?, val duration: Long?, val timestamp: Long)

  val uri: URI
  val gallery: Boolean
  val soundEnabled: MutableState<Boolean>
  val brokenVideo: MutableState<Boolean>
  val videoPlaying: MutableState<Boolean>
  val progress: MutableState<Long>
  val duration: MutableState<Long>
  val preview: MutableState<ImageBitmap>
  // Volume/mute exposed on the interface so common code (keyboard shortcuts, the fullscreen controls)
  // can drive them without reaching into platform players. volume is 0..100.
  val muted: MutableState<Boolean>
  val volume: MutableState<Int>

  fun stop()
  fun play(resetOnEnd: Boolean)
  fun enableSound(enable: Boolean): Boolean
  fun release(remove: Boolean)
  // Pause without tearing down the player (unlike stop) — keeps position so play() resumes in place.
  fun pause()
  // Seek to an absolute position in milliseconds (clamped by the implementation to [0, duration]).
  fun seekTo(ms: Long)
  fun setMuted(m: Boolean)
  fun setVolume(v: Int)
}

expect class VideoPlayer(
  uri: URI,
  gallery: Boolean,
  defaultPreview: ImageBitmap,
  defaultDuration: Long,
  soundEnabled: Boolean
): VideoPlayerInterface

// Bridges between the platform-agnostic fullscreen gallery (commonMain) and the desktop video
// controls without threading extra params through the expect/actual FullScreenVideoView signature.
//   - onVideoEnded: set by the gallery to advance to the next item; called by the player when a clip
//     finishes and loop is off (autoplay-next).
//   - player: the video currently shown fullscreen, so common keyboard shortcuts can drive it.
object GalleryAutoplay { var onVideoEnded: (() -> Unit)? = null }
object ActiveFullscreenPlayer { var player: VideoPlayerInterface? = null }

object VideoPlayerHolder {
  val players: MutableMap<Pair<URI, Boolean>, VideoPlayer> = mutableMapOf()
  val previewsAndDurations: MutableMap<URI, VideoPlayerInterface.PreviewAndDuration> = mutableMapOf()
  // Last playback position per video (ms), so reopening a clip resumes where it was left off.
  // Purely in-memory for the session — never persisted, never leaves the device.
  val resumePositions: MutableMap<URI, Long> = mutableMapOf()

  fun getOrCreate(
    uri: URI,
    gallery: Boolean,
    defaultPreview: ImageBitmap,
    defaultDuration: Long,
    soundEnabled: Boolean
  ): VideoPlayer =
    players.getOrPut(uri to gallery) { VideoPlayer(uri, gallery, defaultPreview, defaultDuration, soundEnabled) }

  private fun player(uri: URI?, gallery: Boolean): VideoPlayer? {
    uri ?: return null
    return players.values.firstOrNull { player -> player.uri == uri && player.gallery == gallery }
  }

  fun release(uri: URI, gallery: Boolean, remove: Boolean) =
    player(uri, gallery)?.release(remove).run { }

  fun stopAll() {
    players.values.forEach { it.stop() }
  }

  fun releaseAll() {
    players.values.forEach { it.release(false) }
    players.clear()
    previewsAndDurations.clear()
  }
}
