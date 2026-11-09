package com.dshgo.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 存"不该出现在明文里"的小字符串 —— 目前只有 Tailscale 的 auth key。
 *
 * 和 AppLock 一样，直接用 Android Keystore 里的 AES 密钥加密，不引
 * androidx.security:security-crypto（长期 alpha，部分国产 ROM 会抛 keystore 异常）。
 * 密钥别名额外交付：AppLock 那把只保护访问密码，这把只保护 auth key，
 * 互不影响；任意一把被系统清掉（改锁屏密码时会发生），另一把仍然可用。
 */
object SecretStore {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "dshgo-secret-key"
    private const val PREFS = "dshgo_secrets"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    /** 读一条密文；解密失败（密钥被换、被清）就当没有，让调用方回到"未配置"。 */
    fun get(context: Context, name: String): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val blob = prefs.getString(name, null) ?: return null
        return runCatching { decrypt(blob) }.getOrNull()
    }

    fun put(context: Context, name: String, value: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(name, encrypt(value)).apply()
    }

    fun clear(context: Context, name: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().remove(name).apply()
    }

    fun has(context: Context, name: String): Boolean = get(context, name)?.isNotBlank() == true

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(body, Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): String {
        val parts = blob.split(":")
        require(parts.size == 2) { "bad blob" }
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val body = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(body), Charsets.UTF_8)
    }

    /** auth key 的固定存放名。 */
    const val TS_AUTH_KEY = "tailscale_auth_key"
}
