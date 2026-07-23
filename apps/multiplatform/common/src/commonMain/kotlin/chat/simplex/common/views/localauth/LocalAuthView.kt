package chat.simplex.common.views.localauth

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import chat.simplex.common.model.*
import chat.simplex.common.model.ChatModel.controller
import dev.icerock.moko.resources.compose.stringResource
import chat.simplex.common.views.helpers.*
import chat.simplex.common.views.helpers.DatabaseUtils.ksSelfDestructPassword
import chat.simplex.common.views.helpers.DatabaseUtils.ksAppPassword
import chat.simplex.common.views.onboarding.OnboardingStage
import chat.simplex.common.platform.*
import chat.simplex.common.views.database.*
import chat.simplex.res.MR
import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.math.pow

// Attempts below this don't get throttled at all (covers ordinary typos).
private const val LA_FREE_ATTEMPTS = 4
// Cap on how long a single lockout can last.
private const val LA_MAX_LOCKOUT_MS = 5 * 60 * 1000L

private fun lockoutMsForAttempt(attempts: Int): Long {
  if (attempts <= LA_FREE_ATTEMPTS) return 0L
  val step = attempts - LA_FREE_ATTEMPTS
  val secs = 5.0 * 2.0.pow((step - 1).toDouble())
  return min(secs.toLong(), LA_MAX_LOCKOUT_MS / 1000) * 1000
}

@Composable
fun LocalAuthView(m: ChatModel, authRequest: LocalAuthRequest) {
  val passcode = rememberSaveable { mutableStateOf("") }
  val allowToReact = rememberSaveable { mutableStateOf(true) }
  val lockedUntil = rememberSaveable { mutableStateOf(controller.appPrefs.laLockedUntil.get()) }
  val now = rememberSaveable { mutableStateOf(System.currentTimeMillis()) }
  if (!allowToReact.value) {
    BackHandler {
      // do nothing until submit action finishes to prevent concurrent removing of storage
    }
  }
  LaunchedEffect(lockedUntil.value) {
    while (now.value < lockedUntil.value) {
      delay(500)
      now.value = System.currentTimeMillis()
    }
  }
  val locked = now.value < lockedUntil.value
  val remainingSecs = if (locked) (lockedUntil.value - now.value + 999) / 1000 else 0
  PasscodeView(
    passcode,
    authRequest.title ?: stringResource(MR.strings.la_enter_app_passcode),
    if (locked) generalGetString(MR.strings.la_too_many_attempts_wait_seconds).format(remainingSecs) else authRequest.reason,
    stringResource(MR.strings.submit_passcode),
    submitEnabled = { !locked },
    buttonsEnabled = allowToReact,
    submit = {
      val sdPassword = ksSelfDestructPassword.get()
      if (sdPassword == passcode.value && authRequest.selfDestruct) {
        // Duress code must always work instantly, regardless of any lockout, so wiping is never blocked.
        allowToReact.value = false
        controller.appPrefs.laFailedAttempts.set(0)
        controller.appPrefs.laLockedUntil.set(0L)
        deleteStorageAndRestart(m, sdPassword) { r ->
          authRequest.completed(r)
        }
      } else if (locked) {
        authRequest.completed(LAResult.Error(generalGetString(MR.strings.la_too_many_attempts_wait_seconds).format(remainingSecs)))
      } else {
        val r: LAResult = if (passcode.value == authRequest.password) {
          controller.appPrefs.laFailedAttempts.set(0)
          controller.appPrefs.laLockedUntil.set(0L)
          if (authRequest.selfDestruct && sdPassword != null && controller.getChatCtrl() == -1L) {
            initChatControllerOnStart()
          }
          LAResult.Success
        } else {
          val attempts = controller.appPrefs.laFailedAttempts.get() + 1
          controller.appPrefs.laFailedAttempts.set(attempts)
          val lockMs = lockoutMsForAttempt(attempts)
          if (lockMs > 0) {
            val until = System.currentTimeMillis() + lockMs
            controller.appPrefs.laLockedUntil.set(until)
            lockedUntil.value = until
            now.value = System.currentTimeMillis()
          }
          LAResult.Error(generalGetString(MR.strings.incorrect_passcode))
        }
        authRequest.completed(r)
      }
    },
    cancel = {
      authRequest.completed(LAResult.Error(generalGetString(MR.strings.authentication_cancelled)))
    })
}

private fun deleteStorageAndRestart(m: ChatModel, password: String, completed: (LAResult) -> Unit) {
  withLongRunningApi {
    try {
      /** Waiting until [initChatController] finishes */
      while (m.ctrlInitInProgress.value) {
        delay(50)
      }
      if (m.chatRunning.value == true) {
        stopChatAsync(m)
      }
      val ctrl = m.controller.getChatCtrl()
      if (ctrl != null && ctrl != -1L) {
        /**
         * The following sequence can bring a user here:
         * the user opened the app, entered app passcode, went to background, returned back, entered self-destruct code.
         * In this case database should be closed to prevent possible situation when OS can deny database removal command
         * */
        chatCloseStore(ctrl)
      }
      deleteChatDatabaseFilesAndState()
      ksAppPassword.set(password)
      ksSelfDestructPassword.remove()
      ntfManager.cancelAllNotifications()
      val selfDestructPref = m.controller.appPrefs.selfDestruct
      val displayNamePref = m.controller.appPrefs.selfDestructDisplayName
      val displayName = displayNamePref.get()
      selfDestructPref.set(false)
      displayNamePref.set(null)
      reinitChatController()
      if (m.currentUser.value != null) {
        return@withLongRunningApi
      }
      var profile: Profile? = null
      if (!displayName.isNullOrEmpty()) {
        profile = Profile(displayName = displayName, fullName = "", shortDescr = null)
      }
      val createdUser = m.controller.apiCreateActiveUser(null, profile, pastTimestamp = true)
      m.currentUser.value = createdUser
      m.controller.appPrefs.onboardingStage.set(OnboardingStage.OnboardingComplete)
      if (createdUser != null) {
        m.controller.startChat(createdUser)
      }
      ModalManager.closeAllModalsEverywhere()
      AlertManager.shared.hideAllAlerts()
      AlertManager.privacySensitive.hideAllAlerts()
      completed(LAResult.Success)
    } catch (e: Exception) {
      Log.e(TAG, "Unable to delete storage: ${e.stackTraceToString()}")
      completed(LAResult.Error(generalGetString(MR.strings.incorrect_passcode)))
    }
  }
}

suspend fun reinitChatController() {
  chatModel.chatDbChanged.value = true
  chatModel.chatDbStatus.value = null
  try {
    initChatController()
  } catch (e: Exception) {
    Log.d(TAG, "initializeChat ${e.stackTraceToString()}")
  }
  chatModel.chatDbChanged.value = false
}
