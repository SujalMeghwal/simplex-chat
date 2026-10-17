package chat.simplex.common.platform

import chat.simplex.common.views.helpers.DatabaseUtils
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLong
import java.io.OutputStream
import java.io.PrintStream
import java.util.Base64
import java.util.concurrent.TimeUnit

// Linux: keep everything SimpleX handles inside SimpleX's own encrypted storage. Nothing readable by
// other users, no logs in the system journal, no core dumps with decrypted data in /var/crash.

private interface LinuxLibC : Library {
  fun prctl(option: Int, arg2: NativeLong, arg3: NativeLong, arg4: NativeLong, arg5: NativeLong): Int
  fun umask(mask: Int): Int
  fun open(path: String, flags: Int): Int
  fun dup2(oldFd: Int, newFd: Int): Int
  fun close(fd: Int): Int
}

private const val PR_SET_DUMPABLE = 4
private const val O_WRONLY = 1
private const val UMASK_OWNER_ONLY = 0x3F // 077

// SIMPLEX_DEBUG_LOGS=1 (./launch-fork.sh --debug) keeps logs on the terminal and allows attaching a debugger.
val linuxDebugMode: Boolean = System.getenv("SIMPLEX_DEBUG_LOGS") == "1"

// Must run first in main(), before any file is created and before the core is loaded.
fun hardenLinuxProcess() {
  if (!desktopPlatform.isLinux()) return
  val libc = try {
    Native.load("c", LinuxLibC::class.java)
  } catch (e: Throwable) {
    return
  }
  // Files and folders created by the app, the core and VLC are readable by this user only.
  libc.umask(UMASK_OWNER_ONLY)
  if (linuxDebugMode) return
  // No core dumps (e.g. by apport into /var/crash), and other processes of this user can't attach
  // to the app or read its memory, where messages and keys are decrypted.
  val zero = NativeLong(0)
  libc.prctl(PR_SET_DUMPABLE, NativeLong(0), zero, zero, zero)
  // Drop all output: app logs, the core and VLC write to stdout/stderr, which a desktop launcher
  // forwards to the system journal (stored on disk).
  val devNull = libc.open("/dev/null", O_WRONLY)
  if (devNull >= 0) {
    libc.dup2(devNull, 1)
    libc.dup2(devNull, 2)
    libc.close(devNull)
  }
  val nullStream = PrintStream(OutputStream.nullOutputStream())
  System.setOut(nullStream)
  System.setErr(nullStream)
}

// Login keyring via the Secret Service API (GNOME Keyring / KWallet), using `secret-tool` from
// libsecret-tools. Keys are passed on stdin/stdout, never on the command line.
internal object LinuxKeyring {
  private const val TOOL = "secret-tool"
  // Long enough for the user to unlock the keyring if the desktop asks for it
  private const val TIMEOUT_SEC = 60L
  private val attributes = listOf("application", "chat.simplex.desktop")

  val available: Boolean by lazy {
    desktopPlatform.isLinux() && run {
      val probe = "probe"
      val stored = store(probe, ByteArray(32) { 1 })
      val ok = stored && lookup(probe)?.size == 32
      clear(probe)
      ok
    }
  }

  fun lookup(alias: String): ByteArray? {
    val out = exec(listOf(TOOL, "lookup") + attributes + listOf("alias", alias), null) ?: return null
    return try {
      Base64.getDecoder().decode(out.trim())
    } catch (e: IllegalArgumentException) {
      null
    }
  }

  fun store(alias: String, key: ByteArray): Boolean =
    exec(listOf(TOOL, "store", "--label=SimpleX $alias") + attributes + listOf("alias", alias), Base64.getEncoder().encodeToString(key)) != null

  fun clear(alias: String) {
    exec(listOf(TOOL, "clear") + attributes + listOf("alias", alias), null)
  }

  // stdout on success, null on any failure (tool missing, keyring locked/unavailable, not found)
  private fun exec(cmd: List<String>, input: String?): String? = try {
    val p = ProcessBuilder(cmd)
      .redirectError(ProcessBuilder.Redirect.DISCARD)
      .start()
    p.outputStream.use { os -> if (input != null) os.write(input.toByteArray()) }
    // wait before reading: output is a single short line, and a stuck tool must not block forever
    if (p.waitFor(TIMEOUT_SEC, TimeUnit.SECONDS) && p.exitValue() == 0) {
      p.inputStream.bufferedReader().use { it.readText() }
    } else {
      p.destroyForcibly()
      null
    }
  } catch (e: Exception) {
    null
  }
}

// Secrets saved before keyring support are stored as plain text. Re-save them so they get encrypted.
fun migratePlaintextSecretsToKeyring() {
  if (!desktopPlatform.isLinux() || !LinuxKeyring.available) return
  listOf(DatabaseUtils.ksDatabasePassword, DatabaseUtils.ksAppPassword, DatabaseUtils.ksSelfDestructPassword).forEach { item ->
    val data = item.passphrase.get()
    if (data != null && data == item.initVector.get()) {
      item.get()?.let { item.set(it) }
    }
  }
}
