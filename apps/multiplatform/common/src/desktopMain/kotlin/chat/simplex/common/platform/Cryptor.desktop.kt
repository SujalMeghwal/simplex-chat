package chat.simplex.common.platform

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// Linux with a login keyring: saved secrets (database passphrase, app and self-destruct passcodes)
// are AES-256-GCM encrypted with a per-secret key kept in the keyring, so settings.properties only
// holds ciphertext. Otherwise (other platforms, or no keyring / libsecret-tools) the upstream
// behaviour is kept: the secret is stored as is, and the UI warns "Password saved on device".
actual val cryptor: CryptorInterface by lazy { if (LinuxKeyring.available) KeyringCryptor else PlainCryptor }

private object PlainCryptor : CryptorInterface {
  override fun decryptData(data: ByteArray, iv: ByteArray, alias: String): String? {
    return String(data) // LALAL
  }

  override fun encryptText(text: String, alias: String): Pair<ByteArray, ByteArray> {
    return text.toByteArray() to text.toByteArray() // LALAL
  }

  override fun deleteKey(alias: String) {
    // LALAL
  }
}

private object KeyringCryptor : CryptorInterface {
  private const val TRANSFORMATION = "AES/GCM/NoPadding"
  private const val TAG_BITS = 128
  private val random = SecureRandom()

  override fun decryptData(data: ByteArray, iv: ByteArray, alias: String): String? {
    // saved before keyring support (PlainCryptor format), re-encrypted by migratePlaintextSecretsToKeyring
    if (data.contentEquals(iv)) return String(data)
    val key = LinuxKeyring.lookup(alias) ?: return null
    return try {
      val cipher = Cipher.getInstance(TRANSFORMATION)
      cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
      String(cipher.doFinal(data))
    } catch (e: Exception) {
      null
    }
  }

  override fun encryptText(text: String, alias: String): Pair<ByteArray, ByteArray> {
    val key = LinuxKeyring.lookup(alias)?.takeIf { it.size == 32 }
      ?: ByteArray(32).also {
        random.nextBytes(it)
        if (!LinuxKeyring.store(alias, it)) throw IllegalStateException("login keyring is not available")
      }
    val iv = ByteArray(12).also { random.nextBytes(it) }
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
    return cipher.doFinal(text.toByteArray()) to iv
  }

  override fun deleteKey(alias: String) {
    LinuxKeyring.clear(alias)
  }
}
