package com.example.app_clavier.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Coffre des extraits protégés (phase 6, ADR-0029) : clé AES-256-GCM du Keystore utilisable seulement dans les
 * 30 s qui suivent une authentification forte (biométrie de classe 3 ou code de l'appareil), et seulement
 * quand l'appareil est déverrouillé. L'invite est affichée par UnlockActivity : un IME ne peut pas l'afficher
 * lui-même de façon fiable.
 */
object SnippetVault {
    private const val ALIAS="keyra_snippets"
    const val VALIDITY_SECONDS=30

    private fun keyStore()=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}

    private fun key():SecretKey? {
        (keyStore().getKey(ALIAS,null) as? SecretKey)?.let{return it}
        val spec=KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).setUserAuthenticationRequired(true).setUnlockedDeviceRequired(true)
        if(Build.VERSION.SDK_INT>=30)spec.setUserAuthenticationParameters(VALIDITY_SECONDS,KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
        else @Suppress("DEPRECATION") spec.setUserAuthenticationValidityDurationSeconds(VALIDITY_SECONDS)
        return runCatching{KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply{init(spec.build())}.generateKey()}.getOrNull()
    }

    /** Chiffré en Base64 (`iv ‖ texte chiffré`), ou null si l'authentification n'est pas récente. */
    fun seal(text:String):String? = runCatching{
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.ENCRYPT_MODE,key() ?: return null)}
        val plain=text.toByteArray(Charsets.UTF_8)
        try{Base64.encodeToString(cipher.iv+cipher.doFinal(plain),Base64.NO_WRAP)}finally{plain.fill(0)}
    }.getOrNull()

    fun open(sealed:String):String? = runCatching{
        val data=Base64.decode(sealed,Base64.NO_WRAP)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key() ?: return null,GCMParameterSpec(128,data,0,12))
        val plain=cipher.doFinal(data,12,data.size-12)
        try{plain.toString(Charsets.UTF_8)}finally{plain.fill(0)}
    }.getOrNull()

    /** Vrai si la clé est utilisable maintenant (authentification récente). */
    fun ready():Boolean = runCatching{Cipher.getInstance("AES/GCM/NoPadding").init(Cipher.ENCRYPT_MODE,key() ?: return false);true}.getOrDefault(false)

    /** Geste panique : la clé est détruite. */
    fun destroy(){runCatching{keyStore().deleteEntry(ALIAS)}}
}
