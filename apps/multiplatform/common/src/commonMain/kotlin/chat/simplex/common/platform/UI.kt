package chat.simplex.common.platform

import androidx.compose.runtime.*
import chat.simplex.common.views.helpers.KeyboardState

expect fun showToast(text: String, timeout: Long = 2500L)

@Composable
expect fun LockToCurrentOrientationUntilDispose()

@Composable
expect fun LocalMultiplatformView(): Any?

// Wraps content with a hover tooltip on desktop (shows [text] on mouse-over); no-op on Android,
// where the same info is conveyed by long-press content descriptions.
@Composable
expect fun WithTooltip(text: String, content: @Composable () -> Unit)

@Composable
expect fun getKeyboardState(): State<KeyboardState>
expect fun hideKeyboard(view: Any?, clearFocus: Boolean = false)

expect fun androidIsFinishingMainActivity(): Boolean

fun registerGlobalErrorHandler() {
  Thread.setDefaultUncaughtExceptionHandler(GlobalExceptionsHandler())
}

expect class GlobalExceptionsHandler(): Thread.UncaughtExceptionHandler {
  override fun uncaughtException(thread: Thread, e: Throwable)
}
