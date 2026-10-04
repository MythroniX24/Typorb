package com.typorb.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Builds Keystore-backed encrypted [SharedPreferences] stores.
 *
 * Two stores use this: the Groq API key and the dictated transcript history. Both hold content the
 * user would not want readable in a backup or by another app.
 *
 * ### Why "16dp blur" is faked rather than rendered
 * A true backdrop blur needs `RenderEffect` + `FLAG_BLUR_BEHIND`, which only exists from API 31 and
 * only blurs content behind an app *window*. Typorb supports API 26 and draws its overlay into
 * another app's window, so neither is available in the general case. The frosted look is therefore
 * composed from a 60%-opaque fill, a 1dp hairline border and a diagonal sheen (see
 * [com.typorb.ui.components.glassSurface]), which reads identically on every supported API level.
 */
internal object SecurePreferences {

    private const val TAG = "SecurePreferences"

    /**
     * @return an encrypted store, falling back to a plaintext one if the Keystore entry cannot be
     *   created or has been invalidated (device restore, OEM bug). Losing dictation history or a
     *   credential is worse than losing at-rest encryption for the rest of the session.
     *
     * The catch is deliberately [Throwable], not [Exception]. This runs during
     * `TyporbApp.onCreate`, so anything it lets escape takes the whole app down before the launcher
     * activity is ever created. The realistic failure modes here are not I/O errors anyway: a
     * Minified build that strips or renames something Tink resolves reflectively throws
     * [NoClassDefFoundError] or [ExceptionInInitializerError], and those are [Error]s, which an
     * `except Exception` clause does not catch. Catching only [Exception] turned a recoverable
     * "fall back to plaintext" into a hard crash on launch.
     */
    fun create(context: Context, name: String): SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            name,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (error: Throwable) {
        Log.e(TAG, "Falling back to plaintext preferences for '$name'", error)
        context.getSharedPreferences(name, Context.MODE_PRIVATE)
    }
}