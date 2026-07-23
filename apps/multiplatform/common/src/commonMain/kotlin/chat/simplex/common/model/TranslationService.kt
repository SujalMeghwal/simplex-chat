package chat.simplex.common.model

import chat.simplex.common.platform.dataDir
import java.io.File
import java.util.concurrent.ConcurrentHashMap

// Fully-offline, on-device translation of received messages to English.
//
// PRIVACY CONTRACT (must never be violated):
//   * Inference is 100% local. No text is ever sent to any server, service, or telemetry sink.
//   * The only file this reads is the locally-provisioned model under translationModelDir().
//   * With no local engine attached (the default), everything here is inert and returns null —
//     so it can never change behavior or leak anything until a model is deliberately installed.
//
// This file is the platform-agnostic surface. The heavy engine (DJL + ONNX Runtime running an
// NLLB-200-distilled model, forced into offline mode) is attached later by platform code via
// Translation.attach(). Kept separate so the abstraction compiles and stays a no-op with no ML
// dependencies on the classpath yet.

interface TranslationEngine {
  // True only when a local model has been fully loaded and can translate without any network.
  fun isReady(): Boolean
  // Translate arbitrary-language text to English, entirely on-device. Returns null on failure or
  // when the input is already English / not worth translating.
  suspend fun translateToEnglish(text: String): String?
}

object Translation {
  // Where a locally-provisioned NLLB model is expected. Never downloaded silently; the model is
  // dropped in / bundled, and loaded from here. Under the app data dir so it rides the same
  // on-device storage as everything else and is never part of any export/sync path.
  fun modelDir(): File = File(dataDir, "models" + File.separator + "nllb-200-distilled-600M")

  @Volatile
  private var engine: TranslationEngine? = null

  // In-memory cache: original text -> English. Session-only, never persisted, never leaves device.
  private val cache = ConcurrentHashMap<String, String>()

  // Attach the local inference engine (called by platform init once the model is present). Passing
  // null detaches and clears the cache — used if the user turns the feature off or removes the model.
  fun attach(e: TranslationEngine?) {
    engine = e
    if (e == null) cache.clear()
  }

  fun isReady(): Boolean = engine?.isReady() == true

  // Public entry point. Returns null (i.e. "show original as-is") whenever there is no ready local
  // engine or nothing to translate — so callers can safely no-op. Never touches the network.
  suspend fun toEnglish(text: String): String? {
    val e = engine ?: return null
    if (!e.isReady()) return null
    val key = text.trim()
    if (key.isEmpty()) return null
    cache[key]?.let { return it }
    val out = e.translateToEnglish(key) ?: return null
    cache[key] = out
    return out
  }
}
