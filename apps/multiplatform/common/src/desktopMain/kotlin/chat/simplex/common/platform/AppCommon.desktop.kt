package chat.simplex.common.platform

import chat.simplex.common.model.*
import chat.simplex.common.simplexWindowState
import chat.simplex.common.views.call.RcvCallInvitation
import chat.simplex.common.views.database.deleteOldChatArchive
import chat.simplex.common.views.helpers.*
import java.util.*
import chat.simplex.res.MR
import java.io.File

actual val appPlatform = AppPlatform.DESKTOP

actual val deviceName = generalGetString(MR.strings.desktop_device)

actual fun isAppVisibleAndFocused() = simplexWindowState.windowFocused.value

@Suppress("ConstantLocale")
val defaultLocale: Locale = Locale.getDefault()

fun initApp() {
  ntfManager = object : NtfManager() {
    override fun notifyCallInvitation(invitation: RcvCallInvitation): Boolean = chat.simplex.common.model.NtfManager.notifyCallInvitation(invitation)
    override fun hasNotificationsForChat(chatId: String): Boolean = chat.simplex.common.model.NtfManager.hasNotificationsForChat(chatId)
    override fun cancelNotificationsForChat(chatId: String) = chat.simplex.common.model.NtfManager.cancelNotificationsForChat(chatId)
    override fun cancelNotificationsForUser(userId: Long) = chat.simplex.common.model.NtfManager.cancelNotificationsForUser(userId)
    override fun displayNotification(user: UserLike, chatId: String, displayName: String, msgText: String, image: String?, actions: List<Pair<NotificationAction, () -> Unit>>) = chat.simplex.common.model.NtfManager.displayNotification(user, chatId, displayName, msgText, image, actions)
    override fun androidCreateNtfChannelsMaybeShowAlert() {}
    override fun cancelCallNotification() {}
    override fun cancelAllNotifications() = chat.simplex.common.model.NtfManager.cancelAllNotifications()
    override fun showMessage(title: String, text: String) = chat.simplex.common.model.NtfManager.showMessage(title, text)
  }
  applyAppLocale()
  deleteOldChatArchive()
  cleanupBrokenMediaFiles()
  if (DatabaseUtils.ksSelfDestructPassword.get() == null) {
    initChatControllerOnStart()
  }
  // Auto-remove old undownloaded media if enabled — wait for chats to load first.
  withBGApi {
    var tries = 0
    while (chatModel.chats.value.isEmpty() && tries++ < 60) kotlinx.coroutines.delay(500)
    chat.simplex.common.views.chat.cleanupOldUndownloadedMedia()
  }
  // LALAL
  //testCrypto()
}

// Failed/aborted downloads leave 0-byte files in the files dir. They can't be decoded or played
// and only produce "file does not exist / cannot be decoded" errors, so remove them on startup.
// Files modified in the last 10 minutes are skipped so an in-progress download is never touched.
private fun cleanupBrokenMediaFiles() {
  withBGApi {
    try {
      val cutoff = System.currentTimeMillis() - 10 * 60_000L
      var removed = 0
      appFilesDir.listFiles()?.forEach { f ->
        if (f.isFile && f.length() == 0L && f.lastModified() < cutoff && f.delete()) removed++
      }
      if (removed > 0) Log.i("SimpleX", "cleanupBrokenMediaFiles: removed $removed empty files")
    } catch (e: Exception) {
      Log.e("SimpleX", "cleanupBrokenMediaFiles failed: ${e.stackTraceToString()}")
    }
  }
}

//fun discoverVlcLibs(path: String) {
//  uk.co.caprica.vlcj.binding.LibC.INSTANCE.setenv("VLC_PLUGIN_PATH", path, 1)
//}

private fun applyAppLocale() {
  val lang = ChatController.appPrefs.appLanguage.get()
  if (lang == null || lang == Locale.getDefault().language) return
  Locale.setDefault(Locale.forLanguageTag(lang))
}
