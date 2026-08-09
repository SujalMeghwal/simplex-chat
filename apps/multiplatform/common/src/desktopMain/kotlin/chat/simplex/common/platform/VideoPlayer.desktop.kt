package chat.simplex.common.platform

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.*
import chat.simplex.common.views.helpers.*
import chat.simplex.res.MR
import kotlinx.coroutines.*
import org.jetbrains.compose.videoplayer.SkiaBitmapVideoSurface
import uk.co.caprica.vlcj.media.VideoOrientation
import uk.co.caprica.vlcj.player.base.*
import uk.co.caprica.vlcj.player.component.CallbackMediaPlayerComponent
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent
import uk.co.caprica.vlcj.player.component.MediaPlayerSpecs
import java.awt.Component
import java.awt.image.BufferedImage
import java.io.File
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

actual class VideoPlayer actual constructor(
  override val uri: URI,
  override val gallery: Boolean,
  private val defaultPreview: ImageBitmap,
  defaultDuration: Long,
  soundEnabled: Boolean
): VideoPlayerInterface {
  override val soundEnabled: MutableState<Boolean> = mutableStateOf(false)
  override val brokenVideo: MutableState<Boolean> = mutableStateOf(false)
  override val videoPlaying: MutableState<Boolean> = mutableStateOf(false)
  override val progress: MutableState<Long> = mutableStateOf(0L)
  override val duration: MutableState<Long> = mutableStateOf(defaultDuration)
  override val preview: MutableState<ImageBitmap> = mutableStateOf(defaultPreview)
  override val muted: MutableState<Boolean> = mutableStateOf(false)
  override val volume: MutableState<Int> = mutableStateOf(100)

  val mediaPlayerComponent by lazy { getOrCreatePlayer() }
  val player by lazy { mediaPlayerComponent.mediaPlayer() }

  init {
    setPreviewAndDuration()
  }

  private var isReleased: AtomicBoolean = AtomicBoolean(false)

  private val listener: MutableState<((position: Long?, state: TrackState) -> Unit)?> = mutableStateOf(null)
  private var progressJob: Job? = null

  enum class TrackState {
    PLAYING, PAUSED, STOPPED
  }

  /** Should be called in [playerThread]. Otherwise, it creates deadlocks in [player.stop] and [player.release] calls */
  private fun start(seek: Long? = null, onProgressUpdate: (position: Long?, state: TrackState) -> Unit): Boolean {
    val filepath = getAppFilePath(uri)
    if (filepath == null || !File(filepath).exists()) {
      Log.e(TAG, "No such file: $filepath")
      brokenVideo.value = true
      return false
    }

    if (soundEnabled.value)  {
      RecorderInterface.stopRecording?.invoke()
    }
    AudioPlayer.stop()
    VideoPlayerHolder.stopAll()
    if (listener.value == null) {
      runCatching {
        // Local files are fully on disk — a big demux/file cache only adds start-up latency. A small
        // cache + skipping audio time-stretch setup gets the first frame on screen noticeably faster.
        player.media().prepare(uri.toFile().absolutePath, ":file-caching=200", ":no-audio-time-stretch")
        // Do NOT seek here: the media is prepared but not yet playing, and seeking pre-play is
        // unreliable in VLCJ. The single authoritative seek happens right after player.start() below
        // (this path used to seek in both places — a redundant double-seek on first play).
      }.onFailure {
        Log.e(TAG, it.stackTraceToString())
        AlertManager.shared.showAlertMsg(generalGetString(MR.strings.unknown_error), it.stackTraceToString())
        brokenVideo.value = true
        return false
      }
    }
    player.start()
    if (seek != null) player.seekTo(seek.toInt())
    if (!player.isPlaying) {
      // Can happen when video file is broken
      AlertManager.shared.showAlertMsg(generalGetString(MR.strings.unknown_error))
      brokenVideo.value = true
      return false
    }
    listener.value = onProgressUpdate
    // Player can only be accessed in one specific thread ([playerThread]). Poll on it — NOT on the main
    // thread — so reads of currentPosition/isPlaying/duration serialize with start/stop/release (all on
    // playerThread too) instead of racing native VLCJ state across threads. isReleased is checked before
    // every native read, and release() runs on the same single thread, so no access-after-release.
    progressJob = CoroutineScope(playerThread.asCoroutineDispatcher()).launch {
      onProgressUpdate(player.currentPosition.toLong(), TrackState.PLAYING)
      while (isActive && !isReleased.get() && player.isPlaying) {
        // Even when current position is equal to duration, the player has isPlaying == true for some time,
        // so help to make the playback stopped in UI immediately
        if (player.currentPosition == player.duration) {
          onProgressUpdate(player.currentPosition.toLong(), TrackState.PLAYING)
          break
        }
        delay(50)
        onProgressUpdate(player.currentPosition.toLong(), TrackState.PLAYING)
      }
      if (isActive && !isReleased.get()) {
        onProgressUpdate(player.currentPosition.toLong(), TrackState.PAUSED)
      }
      onProgressUpdate(null, TrackState.PAUSED)
    }

    return true
  }

  override fun stop() {
    if (isReleased.get() || !videoPlaying.value) return
    playerThread.execute {
      player.stop()
      stopListener()
    }
  }

  private fun stopListener() {
    val afterCoroutineCancel: CompletionHandler = {
      // Notify prev video listener about stop
      listener.value?.invoke(null, TrackState.STOPPED)
    }
    /** Preventing race by calling a code AFTER coroutine ends, so [TrackState] will be:
     * [TrackState.PLAYING] -> [TrackState.PAUSED] -> [TrackState.STOPPED] (in this order)
     * */
    if (progressJob != null) {
      progressJob?.invokeOnCompletion(afterCoroutineCancel)
    } else {
      afterCoroutineCancel(null)
    }
    progressJob?.cancel()
    progressJob = null
  }

  override fun play(resetOnEnd: Boolean) {
    if (progress.value == duration.value) {
      progress.value = 0
    }
    playerThread.execute {
      videoPlaying.value = start(progress.value) { pro, _ ->
        if (pro != null) {
          progress.value = pro
        }
        if ((pro == null || pro == duration.value) && duration.value != 0L) {
          videoPlaying.value = false
          if (pro == duration.value) {
            progress.value = if (resetOnEnd) 0 else duration.value
          }/* else if (state == TrackState.STOPPED) {
          progress.value = 0 //
        }*/
        }
      }
    }
  }

  override fun pause() {
    if (isReleased.get()) return
    playerThread.execute { runCatching { player.pause() } }
  }

  override fun seekTo(ms: Long) {
    if (isReleased.get()) return
    val target = ms.coerceIn(0L, if (duration.value > 0) duration.value else Long.MAX_VALUE)
    progress.value = target
    playerThread.execute { runCatching { player.seekTo(target.toInt()) } }
  }

  override fun setMuted(m: Boolean) {
    muted.value = m
    if (isReleased.get()) return
    playerThread.execute { runCatching { player.audio().isMute = m } }
  }

  // Per-player volume. On Windows VLCJ routes this through WASAPI, which also nudges SimpleX's entry
  // in the Windows Volume Mixer — but that only happens now when the user deliberately drags the
  // slider, not on every playback (which was the original bug this code avoided). Acceptable tradeoff
  // for having an actual volume control.
  override fun setVolume(v: Int) {
    val vol = v.coerceIn(0, 100)
    volume.value = vol
    if (vol == 0) muted.value = true else if (muted.value) muted.value = false
    if (isReleased.get()) return
    playerThread.execute { runCatching { player.audio().setVolume(vol); player.audio().isMute = vol == 0 } }
  }

  // Playback speed (e.g. 0.5x..2x). Routed through [playerThread] like every other control so it never
  // touches native VLCJ state from the Compose/UI thread.
  fun setRate(rate: Float) {
    if (isReleased.get()) return
    playerThread.execute { runCatching { player.setRate(rate) } }
  }

  override fun enableSound(enable: Boolean): Boolean {
    // Impossible to change volume for only one player. It changes for every player
    // https://github.com/caprica/vlcj/issues/985
    return false
    /*if (isReleased.get() || soundEnabled.value == enable) return false
    soundEnabled.value = enable
    playerThread.execute {
      player.audio().isMute = !enable
    }
    return true*/
  }

  override fun release(remove: Boolean) {
    CoroutineScope(playerThread.asCoroutineDispatcher()).launch {
      if (isReleased.get()) return@launch
      isReleased.set(true)
      if (player.isPlaying) {
        player.stop()
      }
      if (usePool) {
        putPlayer(mediaPlayerComponent)
      } else {
        player.release()
      }
      if (remove) {
        VideoPlayerHolder.players.remove(uri to gallery)
      }
    }
  }

  private val MediaPlayer.currentPosition: Int
    get() = if (isReleased.get()) 0 else max(0, status().time().toInt())

  private fun setPreviewAndDuration() {
    // It freezes main thread, doing it in IO thread
    CoroutineScope(Dispatchers.IO).launch {
      val previewAndDuration = VideoPlayerHolder.previewsAndDurations.getOrPut(uri) { getBitmapFromVideo(defaultPreview, uri, withAlertOnException = false) }
      withContext(Dispatchers.Main) {
        preview.value = previewAndDuration.preview ?: defaultPreview
        duration.value = (previewAndDuration.duration ?: 0)
      }
    }
  }

  companion object {
    private val usePool = false

    private fun Component.mediaPlayer() = when (this) {
      is CallbackMediaPlayerComponent -> mediaPlayer()
      is EmbeddedMediaPlayerComponent -> mediaPlayer()
      else -> error("mediaPlayer() can only be called on vlcj player components")
    }

    private fun initializeMediaPlayerComponent(): Component {
      return if (desktopPlatform.isMac()) {
        CallbackMediaPlayerComponent(MediaPlayerSpecs.callbackMediaPlayerSpec().apply { withFactory(vlcFactory) })
      } else {
        EmbeddedMediaPlayerComponent(MediaPlayerSpecs.embeddedMediaPlayerSpec().apply { withFactory(vlcFactory) })
      }
    }

    suspend fun getBitmapFromVideo(defaultPreview: ImageBitmap?, uri: URI?, withAlertOnException: Boolean = true): VideoPlayerInterface.PreviewAndDuration = withContext(previewThread.asCoroutineDispatcher()) {
      // Check the file before taking a helper player from the pool, so the null/missing path can't
      // leak one.
      if (uri == null || !uri.toFile().exists()) {
        if (withAlertOnException) showVideoDecodingException()
        return@withContext VideoPlayerInterface.PreviewAndDuration(preview = defaultPreview, timestamp = 0L, duration = 0L)
      }
      val mediaComponent = getOrCreateHelperPlayer()
      val player = mediaComponent.mediaPlayer()
      // try/finally so the helper player is ALWAYS stopped and returned to the pool — on success, on a
      // missing-orientation/timeout, or on any decode exception. Without it, a throw (or the early
      // returns) leaked a VLCJ player out of the pool for the rest of the session.
      try {
        val surface = SkiaBitmapVideoSurface()
        player.videoSurface().set(surface)
        player.media().startPaused(uri.toFile().absolutePath)
        val snap = withTimeoutOrNull(1500L) {
          while (surface.bitmap.value == null) delay(50)
          surface.bitmap.value!!.toAwtImage()
        }
        val orientation = player.media().info().videoTracks().firstOrNull()?.orientation()
        if (orientation == null) {
          if (withAlertOnException) showVideoDecodingException()
          return@withContext VideoPlayerInterface.PreviewAndDuration(preview = defaultPreview, timestamp = 0L, duration = 0L)
        }
        val preview: ImageBitmap? = when (orientation) {
          VideoOrientation.TOP_LEFT -> snap
          VideoOrientation.TOP_RIGHT -> snap?.flip(false, true)
          VideoOrientation.BOTTOM_LEFT -> snap?.flip(true, false)
          VideoOrientation.BOTTOM_RIGHT -> snap?.rotate(180.0)
          VideoOrientation.LEFT_TOP -> snap  /* Transposed */
          VideoOrientation.LEFT_BOTTOM -> snap?.rotate(-90.0)
          VideoOrientation.RIGHT_TOP -> snap?.rotate(90.0)
          VideoOrientation.RIGHT_BOTTOM -> snap /* Anti-transposed */
          else -> snap
        }?.toComposeImageBitmap()
        val duration = player.duration.toLong()
        return@withContext VideoPlayerInterface.PreviewAndDuration(preview = preview, timestamp = 0L, duration = duration)
      } catch (e: Throwable) {
        Log.e(TAG, "getBitmapFromVideo failed: ${e.stackTraceToString()}")
        if (withAlertOnException) showVideoDecodingException()
        return@withContext VideoPlayerInterface.PreviewAndDuration(preview = defaultPreview, timestamp = 0L, duration = 0L)
      } finally {
        runCatching { player.stop() }
        putHelperPlayer(mediaComponent)
      }
    }

    val playerThread = Executors.newSingleThreadExecutor()
    private val previewThread = Executors.newSingleThreadExecutor()
    private val playersPool: ArrayList<Component> = ArrayList()
    private val helperPlayersPool: ArrayList<CallbackMediaPlayerComponent> = ArrayList()

    private fun getOrCreatePlayer(): Component = playersPool.removeFirstOrNull() ?: createNew()

    private fun createNew(): Component =
      initializeMediaPlayerComponent().apply {
        mediaPlayer().events().addMediaPlayerEventListener(object: MediaPlayerEventAdapter() {
          override fun mediaPlayerReady(mediaPlayer: MediaPlayer?) {
            playerThread.execute {
              // Do not call setVolume here: on Windows VLCJ routes it through WASAPI ISimpleAudioVolume,
              // which resets SimpleX Chat's per-app volume in the Windows Volume Mixer on every playback
              // (VLCJ issue #985). A fresh VLCJ MediaPlayer already defaults to volume 100, so this was redundant.
              mediaPlayer?.audio()?.isMute = false
            }
          }

          override fun stopped(mediaPlayer: MediaPlayer?) {
            //playerThread.execute { mediaPlayer().videoSurface().set(null) }
          }
        })
      }

    private fun putPlayer(player: Component) = playersPool.add(player)

    private fun getOrCreateHelperPlayer(): CallbackMediaPlayerComponent = helperPlayersPool.removeFirstOrNull() ?: CallbackMediaPlayerComponent(MediaPlayerSpecs.callbackMediaPlayerSpec().apply { withFactory(vlcPreviewFactory) })
    private fun putHelperPlayer(player: CallbackMediaPlayerComponent) = helperPlayersPool.add(player)
  }
}
