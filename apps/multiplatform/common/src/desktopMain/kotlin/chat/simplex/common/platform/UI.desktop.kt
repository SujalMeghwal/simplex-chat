package chat.simplex.common.platform

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.simplex.common.simplexWindowState
import chat.simplex.common.views.helpers.KeyboardState

actual fun showToast(text: String, timeout: Long) {
  simplexWindowState.toasts.add(text to timeout)
}

@Composable
actual fun LockToCurrentOrientationUntilDispose() {}

@Composable
actual fun LocalMultiplatformView(): Any? = null

@OptIn(ExperimentalFoundationApi::class)
@Composable
actual fun WithTooltip(text: String, content: @Composable () -> Unit) {
  TooltipArea(
    tooltip = {
      Surface(elevation = 4.dp, shape = RoundedCornerShape(6.dp), color = MaterialTheme.colors.surface) {
        Text(text, fontSize = 13.sp, modifier = androidx.compose.ui.Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
      }
    }
  ) {
    content()
  }
}

@Composable
actual fun getKeyboardState(): State<KeyboardState> = remember { mutableStateOf(KeyboardState.Opened) }
actual fun hideKeyboard(view: Any?, clearFocus: Boolean) {}

actual fun androidIsFinishingMainActivity(): Boolean = false

actual class GlobalExceptionsHandler: Thread.UncaughtExceptionHandler {
  actual override fun uncaughtException(thread: Thread, e: Throwable) {
    Log.e(TAG, "App crashed, thread name: " + thread.name + ", exception: " + e.stackTraceToString())
  }
}
