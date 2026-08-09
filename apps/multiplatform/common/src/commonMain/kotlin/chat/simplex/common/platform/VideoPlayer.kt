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

// Desktop only: the open fullscreen gallery registers its navigation/playback actions here so the
// top-level Window key handler can drive them. Routing keys through the Window (instead of a focused
// Box) is what makes arrow navigation reliable — the video player/surface can steal Compose focus,
// which is why arrows used to lock to one direction once a video was playing. All null when no
// gallery is open, so the Window handler no-ops.
object FullscreenGalleryController {
  var prev: (() -> Unit)? = null
  var next: (() -> Unit)? = null
  var togglePlayPause: (() -> Unit)? = null
  var seekRelative: ((Long) -> Unit)? = null
  var toggleMute: (() -> Unit)? = null
  var deleteCurrent: (() -> Unit)? = null
  fun clear() { prev = null; next = null; togglePlayPause = null; seekRelative = null; toggleMute = null; deleteCurrent = null }
}

// Bounded, access-ordered, synchronized LRU map. Used for the session caches below that would
// otherwise grow one entry per unique video for the whole session (a slow memory leak — the preview
// cache holds ImageBitmaps). Eviction just means the value is recomputed next time it's needed.
private fun <K, V> lruMap(maxSize: Int): MutableMap<K, V> =
  java.util.Collections.synchronizedMap(object : java.util.LinkedHashMap<K, V>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > maxSize
  })

object VideoPlayerHolder {
  val players: MutableMap<Pair<URI, Boolean>, VideoPlayer> = mutableMapOf()
  // Capped + LRU-evicted: an evicted preview is just re-decoded next time it's shown. Still purely
  // in-memory, never persisted, never leaves the device.
  val previewsAndDurations: MutableMap<URI, VideoPlayerInterface.PreviewAndDuration> = lruMap(128)
  // Last playback position per video (ms), so reopening a clip resumes where it was left off. Capped
  // + LRU. Purely in-memory for the session — never persisted, never leaves the device.
  val resumePositions: MutableMap<URI, Long> = lruMap(256)

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

  // Bounded LRU of gallery videos kept alive so stepping back/forward reuses an existing player
  // (instant) instead of cold-starting VLCJ each time — that cold start is why fast arrowing
  // couldn't "keep up". Capped so players don't accumulate and choke the audio device (the old
  // unbounded behavior froze the next clip). Call each time a gallery clip is shown.
  private val galleryShown = mutableListOf<URI>()
  fun noteGalleryShown(uri: URI, keep: Int = 4) {
    galleryShown.remove(uri)
    galleryShown.add(uri)
    while (galleryShown.size > keep) {
      val old = galleryShown.removeAt(0)
      release(old, true, true)
    }
  }
  fun clearGalleryShown() { galleryShown.clear() }

  fun stopAll() {
    players.values.forEach { it.stop() }
  }

  fun releaseAll() {
    players.values.forEach { it.release(false) }
    players.clear()
    previewsAndDurations.clear()
  }
}
